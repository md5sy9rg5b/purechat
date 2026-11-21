/*
 * PureChat 前端应用逻辑。
 *
 * 设计原则：
 * 1. 纯净：不预置任何系统提示词、不带联网工具、不做行为约束；用户发什么就问什么。
 * 2. 本地优先：会话与设置只存在本机，通过原生桥写入应用私有存储。
 * 3. 原生代理网络：页面以 file:// 加载，直连模型接口会被同源策略拦截，因此请求全部交给原生发起。
 *
 * 原生桥接口（window.PureChatNative，缺失时自动降级为浏览器预览模式）：
 *   storeGet/storeSet/storeClear、deviceInfo、setDarkChrome、haptic、copyText、
 *   chatStart(payloadJson)、chatAbort(id)、verify(payloadJson, callbackId)、renderMarkdown(md)
 * 原生回传：window.__pcEvent(requestId, json) / window.__pcCallback(callbackId, ok, json)
 */
(function () {
  'use strict';

  var STORE_SETTINGS = 'settings';
  var STORE_SESSIONS = 'sessions';
  var STORE_CURRENT = 'current';

  var DEFAULT_SETTINGS = {
    apiKey: '',
    origin: 'https://api.deepseek.com',
    model: 'deepseek-flash',
    thinking: false,
    // 思考强度：default 表示不发送 output_config，由服务端用默认档
    effort: 'default',
    // 单次回复输出上限
    maxTokens: 8192,
    // 联网搜索（思考模式下为深度思考搜索）
    webSearch: false,
    // 阅读用户消息中的链接
    readUrl: false,
    // 让服务端生成会话标题
    autoTitle: false,
    // 内容安全审核
    safety: false,
    // 朗读音色：stella（女声）/ tide（男声）
    voice: 'stella',
    // 把当前日期/地点/语言作为 latest_reminder 传给模型
    reminder: false,
    location: '',
    system: '',
    theme: 'system',
  };

  // ---------------- 原生桥 ----------------

  var bridge = window.PureChatNative || null;

  var native = {
    available: !!bridge,
    storeGet: function (k) {
      if (bridge) return bridge.storeGet(k) || '';
      try { return window.localStorage.getItem(k) || ''; } catch (e) { return ''; }
    },
    storeSet: function (k, v) {
      if (bridge) { bridge.storeSet(k, v); return; }
      try { window.localStorage.setItem(k, v); } catch (e) { /* 忽略 */ }
    },
    storeClear: function () {
      if (bridge) { bridge.storeClear(); return; }
      try { window.localStorage.clear(); } catch (e) { /* 忽略 */ }
    },
    deviceInfo: function () {
      if (bridge) {
        try { return JSON.parse(bridge.deviceInfo()); } catch (e) { /* 落到默认 */ }
      }
      return { sdk: 0, model: 'browser', dark: !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) };
    },
    setDarkChrome: function (dark) { if (bridge) { try { bridge.setDarkChrome(dark); } catch (e) { /* 忽略 */ } } },
    haptic: function () { if (bridge) { try { bridge.haptic(); } catch (e) { /* 忽略 */ } } },
    readClipboard: function () {
      if (bridge && bridge.readClipboard) {
        try { return bridge.readClipboard() || ''; } catch (e) { return ''; }
      }
      return '';
    },
    copy: function (text) {
      if (bridge) { try { bridge.copyText(text); return true; } catch (e) { /* 忽略 */ } }
      if (navigator.clipboard) { navigator.clipboard.writeText(text); return true; }
      return false;
    },
    abortAll: function () { if (bridge) { try { bridge.abortAll(); } catch (e) { /* 忽略 */ } } },
    renderMarkdown: function (md) {
      if (bridge) {
        try { return bridge.renderMarkdown(md); } catch (e) { return '<p class="md-p">' + esc(md) + '</p>'; }
      }
      return previewMarkdown(md);
    },
    chatStart: function (payload, onEvent) {
      if (bridge) { bridge.chatStart(JSON.stringify(payload)); return; }
      previewChat(payload, onEvent);
    },
    chatAbort: function (id) { if (bridge) { try { bridge.chatAbort(id); } catch (e) { /* 忽略 */ } } },
    verify: function (payload, callbackId) {
      if (bridge) { bridge.verify(JSON.stringify(payload), callbackId); return; }
      window.setTimeout(function () { callback(callbackId, false, { message: '预览模式无法校验连接' }); }, 200);
    },
    hasAsr: function () { return !!(bridge && bridge.asrStart); },
    asrStart: function (payload) { if (bridge && bridge.asrStart) bridge.asrStart(JSON.stringify(payload)); },
    asrStop: function (id) { if (bridge && bridge.asrStop) bridge.asrStop(id); },
    hasFiles: function () { return !!(bridge && bridge.pickFiles); },
    imageThumb: function (path) {
      if (bridge && bridge.imageThumb) {
        try { return bridge.imageThumb(path) || ''; } catch (e) { return ''; }
      }
      return '';
    },
    pickFiles: function (callbackId) { if (bridge && bridge.pickFiles) bridge.pickFiles(callbackId); },
    uploadFile: function (payload) { if (bridge && bridge.uploadFile) bridge.uploadFile(JSON.stringify(payload)); },
    hasTts: function () { return !!(bridge && bridge.ttsSpeak); },
    ttsSpeak: function (payload) { if (bridge && bridge.ttsSpeak) bridge.ttsSpeak(JSON.stringify(payload)); },
    ttsStop: function () { if (bridge && bridge.ttsStop) bridge.ttsStop(); },
  };

  // 等待中的回调
  var callbacks = {};
  var callbackSeq = 0;
  var listeners = {};

  window.__pcEvent = function (requestId, json) {
    var handler = listeners[requestId];
    if (!handler) return;
    var event;
    try { event = JSON.parse(json); } catch (e) { return; }
    handler(event);
  };

  window.__pcCallback = function (callbackId, ok, json) { callback(callbackId, ok, json); };

  function callback(callbackId, ok, json) {
    var fn = callbacks[callbackId];
    if (!fn) return;
    delete callbacks[callbackId];
    var data;
    try { data = typeof json === 'string' ? JSON.parse(json) : (json || {}); } catch (e) { data = {}; }
    fn(ok, data);
  }

  function invoke(payload, onEvent) {
    var id = payload.id;
    listeners[id] = function (event) {
      if (event.t === 'end') { delete listeners[id]; }
      onEvent(event);
    };
    native.chatStart(payload, onEvent);
  }

  // ---------------- 工具函数 ----------------

  function esc(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function uid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function parseJson(text, fallback) {
    if (!text) return fallback;
    try {
      var value = JSON.parse(text);
      return value == null ? fallback : value;
    } catch (e) { return fallback; }
  }

  // ---------------- 状态 ----------------

  var settings = mergeSettings(parseJson(native.storeGet(STORE_SETTINGS), {}));
  var sessions = normalizeSessions(parseJson(native.storeGet(STORE_SESSIONS), []));
  var currentId = native.storeGet(STORE_CURRENT) || '';
  var streaming = null;
  var deviceInfo = native.deviceInfo();
  // 待发送的附件：{ name, mime, size, path, blockType, fileId, uploading, error }
  var draftFiles = [];
  // 语音输入状态：{ id, active }
  var asrState = null;

  function mergeSettings(raw) {
    var out = {};
    for (var key in DEFAULT_SETTINGS) out[key] = DEFAULT_SETTINGS[key];
    if (raw && typeof raw === 'object') {
      for (var k in raw) if (raw[k] !== undefined && raw[k] !== null) out[k] = raw[k];
    }
    out.thinking = !!out.thinking;
    out.webSearch = !!out.webSearch;
    out.readUrl = !!out.readUrl;
    out.autoTitle = !!out.autoTitle;
    out.safety = !!out.safety;
    out.reminder = !!out.reminder;
    var maxTokens = parseInt(out.maxTokens, 10);
    out.maxTokens = isFinite(maxTokens) && maxTokens >= 256
      ? Math.min(maxTokens, 393216) : DEFAULT_SETTINGS.maxTokens;
    return out;
  }

  function normalizeSessions(raw) {
    if (!Array.isArray(raw)) return [];
    var list = [];
    for (var i = 0; i < raw.length; i++) {
      var item = raw[i];
      if (!item || typeof item !== 'object' || !item.id) continue;
      list.push({
        id: String(item.id),
        title: typeof item.title === 'string' ? item.title : '',
        titleAuto: !!item.titleAuto,
        updatedAt: item.updatedAt || 0,
        messages: Array.isArray(item.messages) ? item.messages.filter(function (m) {
          return m && (m.role === 'user' || m.role === 'assistant');
        }) : [],
      });
    }
    list.sort(function (a, b) { return (b.updatedAt || 0) - (a.updatedAt || 0); });
    return list;
  }

  function saveSessions() {
    native.storeSet(STORE_SESSIONS, JSON.stringify(sessions));
  }

  function saveSettings() {
    native.storeSet(STORE_SETTINGS, JSON.stringify(settings));
  }

  function currentSession() {
    for (var i = 0; i < sessions.length; i++) if (sessions[i].id === currentId) return sessions[i];
    return null;
  }

  function ensureSession() {
    var session = currentSession();
    if (session) return session;
    session = { id: uid(), title: '', updatedAt: Date.now(), messages: [] };
    sessions.unshift(session);
    currentId = session.id;
    native.storeSet(STORE_CURRENT, currentId);
    return session;
  }

  function touch(session) {
    session.updatedAt = Date.now();
    sessions.sort(function (a, b) { return (b.updatedAt || 0) - (a.updatedAt || 0); });
    // 本地标题只是占位：服务端返回的标题会覆盖它（autoTitle 未开启时不会覆盖）。
    if (!session.title) {
      for (var i = 0; i < session.messages.length; i++) {
        var m = session.messages[i];
        if (m.role === 'user' && m.content) {
          session.title = firstLine(m.content);
          session.titleAuto = false;
          break;
        }
      }
    }
    saveSessions();
  }

  function firstLine(text) {
    var line = String(text).replace(/\s+/g, ' ').trim();
    return line.length > 24 ? line.slice(0, 24) + '…' : line;
  }

  // ---------------- DOM ----------------

  var $ = function (id) { return document.getElementById(id); };
  var el = {
    body: document.body,
    scroller: $('scroller'),
    messages: $('messages'),
    welcome: $('welcome'),
    title: $('title'),
    input: $('input'),
    btnSend: $('btn-send'),
    btnStop: $('btn-stop'),
    composer: $('composer'),
    drawer: $('drawer'),
    scrim: $('scrim'),
    sessions: $('sessions'),
    settings: $('settings'),
    toast: $('toast'),
    confirm: $('confirm'),
    confirmText: $('confirm-text'),
    scrollBottom: $('scroll-bottom'),
    attachRow: $('attach-row'),
    btnMic: $('btn-mic'),
    btnAttach: $('btn-attach'),
    editBar: $('edit-bar'),
    editBarText: $('edit-bar-text'),
  };

  // ---------------- 主题 ----------------

  function systemDark() {
    if (settings.theme === 'dark') return true;
    if (settings.theme === 'light') return false;
    return !!deviceInfo.dark;
  }

  function applyTheme() {
    var dark = systemDark();
    el.body.setAttribute('data-theme', dark ? 'dark' : 'light');
    native.setDarkChrome(dark);
  }

  // ---------------- 渲染：消息 ----------------

  /**
   * 用 KaTeX 排版渲染结果里的公式占位符。
   * 公式内容来自模型输出，KaTeX 只产出排版 DOM，不执行脚本。
   */
  function typesetMath(root) {
    if (!root || typeof window.katex === 'undefined') return;
    var nodes = root.querySelectorAll('.md-math');
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.getAttribute('data-typeset') === '1') continue;
      var source = node.textContent || '';
      var display = node.getAttribute('data-display') === '1';
      try {
        window.katex.render(source, node, {
          displayMode: display,
          throwOnError: false,
          strict: false,
          output: 'html',
        });
        node.setAttribute('data-typeset', '1');
      } catch (e) {
        node.setAttribute('data-typeset', '1');
      }
    }
  }

  function renderAll() {
    var session = currentSession();
    el.messages.innerHTML = '';
    if (!session || session.messages.length === 0) {
      el.welcome.hidden = false;
      updateTitle();
      scrollToBottom(true);
      return;
    }
    el.welcome.hidden = true;
    for (var i = 0; i < session.messages.length; i++) {
      // 正在流式输出的回复由 live view 单独渲染，这里跳过占位消息，避免出现两条。
      if (session.messages[i].pending) continue;
      el.messages.appendChild(buildMessageNode(session.messages[i], i));
    }
    typesetMath(el.messages);
    updateTitle();
    scrollToBottom(true);
  }

  function updateTitle() {
    var session = currentSession();
    el.title.textContent = session && session.title ? session.title : '新对话';
  }

  function buildMessageNode(message, index) {
    var node = document.createElement('div');
    node.className = 'msg ' + message.role;
    node.setAttribute('data-index', String(index));

    var body = document.createElement('div');
    body.className = 'msg-body';
    node.appendChild(body);

    if (message.role === 'user') {
      if (message.files && message.files.length) {
        body.appendChild(buildMessageFiles(message.files));
      }
      if (message.content) {
        var textNode = document.createElement('div');
        textNode.className = 'user-text';
        textNode.textContent = message.content;
        body.appendChild(textNode);
      }
      if (!message.content && (!message.files || !message.files.length)) {
        body.textContent = '';
      }
    } else {
      fillAssistant(body, message);
    }

    var tools = document.createElement('div');
    tools.className = 'msg-tools';
    tools.appendChild(buildToolButton('复制', function () {
      var text = message.role === 'user' ? message.content : collectAnswer(message);
      if (native.copy(text || '')) toast('已复制');
    }));
    if (message.role === 'assistant' && message.content) {
      var speakBtn = buildToolButton('朗读', function () { speakMessage(message, index); });
      speakBtn.setAttribute('data-speak-index', String(index));
      tools.appendChild(speakBtn);
    }
    // 用户消息可编辑：改完会截断该轮之后的所有内容并重新提问。
    if (message.role === 'user') {
      tools.appendChild(buildToolButton('编辑', function () { startEdit(index); }));
    }
    // 编辑回复：纯本地改写，不调接口，改动会在下一轮作为历史回传给模型。
    if (message.role === 'assistant' && message.content) {
      tools.appendChild(buildToolButton('编辑', function () { startEditOutput(index); }));
    }
    // 续写：保留这条回复，让模型接着往下写（不同于编辑，不改动已有文字）。
    var canContinue = message.role === 'assistant' && message.content
      && !(Array.isArray(message.tools) && message.tools.length);
    if (canContinue) {
      tools.appendChild(buildToolButton('续写', function () { startContinue(index); }));
    }
    if (message.role === 'assistant' && index === lastIndex()) {
      tools.appendChild(buildToolButton('重新生成', function () { regenerate(index); }));
    }
    node.appendChild(tools);

    if (message.ts) {
      var time = document.createElement('div');
      time.className = 'msg-time';
      time.textContent = formatTime(message.ts);
      node.appendChild(time);
    }

    return node;
  }

  function formatTime(ts) {
    var d = new Date(ts);
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }

  function lastIndex() {
    var session = currentSession();
    return session ? session.messages.length - 1 : -1;
  }

  function buildToolButton(label, onClick) {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'tool-btn';
    btn.textContent = label;
    btn.addEventListener('click', function (event) {
      event.stopPropagation();
      native.haptic();
      onClick();
    });
    return btn;
  }

  /** 把助手消息（含思考与正文）填充到容器。 */
  function fillAssistant(container, message) {
    container.innerHTML = '';
    if (Array.isArray(message.tools)) {
      for (var ti = 0; ti < message.tools.length; ti++) {
        container.appendChild(buildToolCard(message.tools[ti]));
      }
    }
    if (message.reasoning) {
      container.appendChild(buildThinking(message.reasoning, !!message.reasoningOpen));
    }
    var answer = document.createElement('div');
    answer.className = 'answer';
    if (message.error) {
      var box = document.createElement('div');
      box.className = 'error-box';
      box.textContent = message.error;
      answer.appendChild(box);
    }
    if (message.content) {
      answer.insertAdjacentHTML('beforeend', native.renderMarkdown(message.content));
      typesetMath(answer);
    }
    if (!message.content && !message.error && message.reasoning) {
      answer.insertAdjacentHTML('beforeend', '<div class="empty-hint">已思考</div>');
    }
    container.appendChild(answer);
    var sources = buildSourceList(message.citations);
    if (sources) container.appendChild(sources);
    return answer;
  }

  function buildThinking(text, open) {
    var box = document.createElement('div');
    box.className = 'thinking-box' + (open ? ' open' : '');
    var head = document.createElement('div');
    head.className = 'thinking-head';
    head.innerHTML = '<span class="thinking-title">思考过程</span><span class="thinking-toggle">'
      + (open ? '收起' : '展开') + '</span>';
    head.addEventListener('click', function () {
      box.classList.toggle('open');
      head.querySelector('.thinking-toggle').textContent = box.classList.contains('open') ? '收起' : '展开';
    });
    var body = document.createElement('div');
    body.className = 'thinking-body';
    // 思考内容同样含 Markdown（**加粗**、`代码`、列表等），需要解析后再插入；
    // 之前用 textContent 会把标记原样显示出来。
    body.innerHTML = native.renderMarkdown(text);
    typesetMath(body);
    box.appendChild(head);
    box.appendChild(body);
    return box;
  }

  function collectAnswer(message) {
    return message.content || '';
  }

  /**
   * 渲染消息里的附件。
   *
   * <p>图片用缩略图展示（由原生按需读取私有缓存并压缩），其它类型显示文件名与大小；
   * 点击图片可全屏查看原图。</p>
   */
  function buildMessageFiles(files) {
    var box = document.createElement('div');
    box.className = 'msg-files';
    files.forEach(function (f) {
      var isImage = f.blockType === 'image';
      if (isImage && f.path) {
        var thumb = native.imageThumb(f.path);
        if (thumb) {
          var wrap = document.createElement('button');
          wrap.type = 'button';
          wrap.className = 'msg-thumb';
          var img = document.createElement('img');
          img.src = thumb;
          img.alt = f.name || '图片';
          img.loading = 'lazy';
          wrap.appendChild(img);
          wrap.addEventListener('click', function () { openImageViewer(thumb, f.name); });
          box.appendChild(wrap);
          return;
        }
      }
      var chip = document.createElement('div');
      chip.className = 'msg-file';
      var label = isImage ? '图片' : '文件';
      var size = f.size ? ' · ' + formatSize(f.size) : '';
      chip.textContent = label + ' · ' + (f.name || '') + size;
      box.appendChild(chip);
    });
    return box;
  }

  /** 全屏查看图片。 */
  function openImageViewer(src, name) {
    var mask = document.createElement('div');
    mask.className = 'image-viewer';
    var img = document.createElement('img');
    img.src = src;
    img.alt = name || '图片';
    mask.appendChild(img);
    mask.addEventListener('click', function () { mask.remove(); });
    document.body.appendChild(mask);
    native.haptic();
  }

  // ---------------- 服务端工具块（联网搜索 / 阅读链接）渲染 ----------------

  /** 把一次工具调用 + 结果渲染成可折叠卡片。 */
  function buildToolCard(block) {
    var card = document.createElement('div');
    card.className = 'tool-card';
    var head = document.createElement('div');
    head.className = 'tool-card-head';
    var body = document.createElement('div');
    body.className = 'tool-card-body';

    var title = '';
    var detail = '';
    var items = [];

    if (block.type === 'server_tool_use') {
      var input = block.input || {};
      if (block.name === 'deepseek_web_search') {
        if (input.action === 'search') {
          var queries = input.queries || [];
          title = '搜索 ' + queries.length + ' 个关键词';
          detail = queries.join(' · ');
        } else if (input.action === 'open_page') {
          title = '打开网页';
          detail = input.url || '';
        } else if (input.action === 'find_in_page') {
          title = '页内查找 "‹' + (input.pattern || '') + '›"';
          detail = input.url || '';
        } else {
          title = '联网搜索';
        }
      } else if (block.name === 'deepseek_read_url') {
        title = '阅读链接';
        detail = input.url || '';
      } else {
        title = block.name || '工具调用';
      }
    } else if (block.type === 'web_search_tool_result') {
      var list = Array.isArray(block.content) ? block.content : [];
      var results = list.filter(function (x) { return x && x.type === 'web_search_result'; });
      var failures = list.filter(function (x) { return x && x.type === 'web_search_tool_result_error'; });
      if (results.length === 0) {
        title = failures.length ? '搜索失败' : '没有找到结果';
      } else {
        title = '找到 ' + results.length + ' 条结果';
      }
      items = results;
    } else if (block.type === 'web_fetch_tool_result') {
      var content = block.content || {};
      if (content.type === 'web_fetch_result') {
        title = '已读取网页';
        detail = content.title || content.url || '';
      } else {
        title = '网页读取失败';
        detail = (content.error_code === 'url_not_allowed') ? '该链接不被允许读取'
          : (content.error_code === 'url_not_accessible' ? '无法访问该链接' : '服务暂不可用');
      }
    }

    head.innerHTML = '<span class="tool-card-title">' + esc(title) + '</span>'
      + '<span class="tool-card-toggle">展开</span>';
    if (detail) {
      var sub = document.createElement('div');
      sub.className = 'tool-card-sub';
      sub.textContent = detail;
      head.appendChild(sub);
    }

    head.addEventListener('click', function () {
      card.classList.toggle('open');
      head.querySelector('.tool-card-toggle').textContent =
        card.classList.contains('open') ? '收起' : '展开';
    });

    for (var i = 0; i < items.length; i++) {
      var r = items[i];
      var a = document.createElement('a');
      a.className = 'tool-result';
      a.href = r.url;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      var t = document.createElement('div');
      t.className = 'tool-result-title';
      t.textContent = r.title || r.url;
      var m = document.createElement('div');
      m.className = 'tool-result-meta';
      m.textContent = r.site_name || hostOf(r.url);
      a.appendChild(t);
      if (r.snippet) {
        var s = document.createElement('div');
        s.className = 'tool-result-snippet';
        s.textContent = r.snippet;
        a.appendChild(s);
      }
      a.appendChild(m);
      body.appendChild(a);
    }

    if (!items.length && !detail) {
      body.appendChild(document.createTextNode(''));
    }

    card.appendChild(head);
    if (items.length) card.appendChild(body);
    return card;
  }

  /** 让工具卡片按内容块序号排列，并保持在思考区与正文之前。 */
  function sortToolCards(body) {
    var cards = [];
    for (var i = 0; i < body.children.length; i++) {
      var node = body.children[i];
      if (node.classList && node.classList.contains('tool-card')) cards.push(node);
    }
    if (!cards.length) return;
    cards.sort(function (a, b) {
      return parseInt(a.getAttribute('data-block-index'), 10)
        - parseInt(b.getAttribute('data-block-index'), 10);
    });
    // 依次插到第一个非卡片节点之前，保持卡片区整体在最上方。
    var anchor = null;
    for (var k = 0; k < body.children.length; k++) {
      var n = body.children[k];
      if (!(n.classList && n.classList.contains('tool-card'))) { anchor = n; break; }
    }
    for (var c = 0; c < cards.length; c++) {
      if (anchor) body.insertBefore(cards[c], anchor);
      else body.appendChild(cards[c]);
    }
  }

  function hostOf(url) {
    try { return new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return url || ''; }
  }

  /** 正文引用角标：按 url 去重，点击跳到该来源。 */
  function buildCitationBadge(citation, index) {
    var badge = document.createElement('a');
    badge.className = 'citation-badge';
    badge.href = citation.url;
    badge.target = '_blank';
    badge.rel = 'noopener noreferrer';
    badge.textContent = String(index);
    badge.title = citation.title || citation.url;
    return badge;
  }

  /** 消息底部的来源列表。 */
  function buildSourceList(citations) {
    if (!citations || !citations.length) return null;
    var box = document.createElement('div');
    box.className = 'source-list';
    var head = document.createElement('div');
    head.className = 'source-list-head';
    head.textContent = '参考来源 ' + citations.length;
    box.appendChild(head);
    citations.forEach(function (c, i) {
      var a = document.createElement('a');
      a.className = 'source-item';
      a.href = c.url;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      var idx = document.createElement('span');
      idx.className = 'source-index';
      idx.textContent = String(i + 1);
      var txt = document.createElement('span');
      txt.className = 'source-title';
      txt.textContent = c.title || hostOf(c.url);
      a.appendChild(idx);
      a.appendChild(txt);
      box.appendChild(a);
    });
    return box;
  }

  // ---------------- 渲染：流式更新 ----------------

  function createLiveView(sessionId, initialContent) {
    var session = currentSession();
    if (!session || session.id !== sessionId) return null;
    var node = document.createElement('div');
    node.className = 'msg assistant streaming';
    var body = document.createElement('div');
    body.className = 'msg-body';
    node.appendChild(body);
    el.messages.appendChild(node);
    el.welcome.hidden = true;
    var created = { node: node, body: body, thinkingBox: null, thinkingBody: null, answer: null, caret: null };
    // 续写时先把前缀铺到界面上，用户能立刻看到自己编辑过的开头。
    if (initialContent) {
      var answer = ensureAnswer(created);
      if (created.caret && created.caret.parentNode === answer) answer.removeChild(created.caret);
      answer.innerHTML = native.renderMarkdown(initialContent);
      if (created.caret) answer.appendChild(created.caret);
    }
    return created;
  }

  function ensureAnswer(view) {
    if (view.answer) return view.answer;
    var answer = document.createElement('div');
    answer.className = 'answer';
    view.answer = answer;
    view.body.appendChild(answer);
    var caret = document.createElement('span');
    caret.className = 'typing-caret';
    view.caret = caret;
    answer.appendChild(caret);
    return answer;
  }

  function ensureThinking(view) {
    if (view.thinkingBox) return view.thinkingBody;
    var box = buildThinking('', true);
    var body = box.querySelector('.thinking-body');
    view.thinkingBox = box;
    view.thinkingBody = body;
    view.body.insertBefore(box, view.body.firstChild);
    return body;
  }

  function updateLiveView(view, message) {
    // 结束后的迟到的 rAF 回调不能再次插入光标或覆盖最终内容。
    if (!view || view.done) return;
    // 工具卡片按块顺序追加，避免每帧重建。
    if (Array.isArray(message.tools)) {
      view.toolCount = view.toolCount || 0;
      while (view.toolCount < message.tools.length) {
        // 卡片一律挂在容器最前面，之后按块序号重排，保证与协议顺序一致。
        var card = buildToolCard(message.tools[view.toolCount]);
        card.setAttribute('data-block-index', String(message.tools[view.toolCount].__i));
        view.body.appendChild(card);
        view.toolCount++;
      }
      sortToolCards(view.body);
    }
    if (message.reasoning) {
      var target = ensureThinking(view);
      // 流式期间按增量重渲染 Markdown；用缓存字符串避免每帧都重新解析。
      if (view.thinkingText !== message.reasoning) {
        view.thinkingText = message.reasoning;
        target.innerHTML = native.renderMarkdown(message.reasoning);
        typesetMath(target);
      }
    }
    if (message.content) {
      var answer = ensureAnswer(view);
      var caret = view.caret;
      if (caret && caret.parentNode === answer) answer.removeChild(caret);
      answer.innerHTML = native.renderMarkdown(message.content);
      typesetMath(answer);
      if (caret) answer.appendChild(caret);
    } else if (message.error) {
      var box = view.body.querySelector('.error-box');
      if (!box) {
        box = document.createElement('div');
        box.className = 'error-box';
        view.body.appendChild(box);
      }
      box.textContent = message.error;
    }
  }

  function finishLiveView(view, message) {
    if (!view) return;
    view.done = true;
    // 生成结束后自动收起思考过程，保持界面干净；用户仍可点击展开。
    if (view.thinkingBox) {
      view.thinkingBox.classList.remove('open');
      var toggle = view.thinkingBox.querySelector('.thinking-toggle');
      if (toggle) toggle.textContent = '展开';
    }
    if (view.caret && view.caret.parentNode) view.caret.parentNode.removeChild(view.caret);
    var tools = document.createElement('div');
    tools.className = 'msg-tools';
    tools.appendChild(buildToolButton('复制', function () {
      if (native.copy(collectAnswer(message) || '')) toast('已复制');
    }));
    if (message.content) {
      var speakBtn = buildToolButton('朗读', function () { speakMessage(message, lastIndex()); });
      speakBtn.setAttribute('data-speak-index', String(lastIndex()));
      tools.appendChild(speakBtn);
      // 与静态渲染保持一致：可编辑回复，最新一条且未用过联网工具时可续写。
      var latest = lastIndex();
      tools.appendChild(buildToolButton('编辑', function () { startEditOutput(latest); }));
      if (!(Array.isArray(message.tools) && message.tools.length)) {
        tools.appendChild(buildToolButton('续写', function () { startContinue(latest); }));
      }
    }
    tools.appendChild(buildToolButton('重新生成', function () { regenerate(lastIndex()); }));
    var sources = buildSourceList(message.citations);
    if (sources) view.node.appendChild(sources);
    view.node.appendChild(tools);
    var time = document.createElement('div');
    time.className = 'msg-time';
    time.textContent = formatTime(Date.now());
    view.node.appendChild(time);
    view.node.classList.remove('streaming');
  }

  // ---------------- 滚动 ----------------

  var stickToBottom = true;

  function isNearBottom() {
    var gap = el.scroller.scrollHeight - el.scroller.scrollTop - el.scroller.clientHeight;
    return gap < 80;
  }

  function scrollToBottom(force) {
    if (!force && !stickToBottom) return;
    el.scroller.scrollTop = el.scroller.scrollHeight;
    stickToBottom = true;
    el.scrollBottom.hidden = true;
  }

  el.scroller.addEventListener('scroll', function () {
    stickToBottom = isNearBottom();
    el.scrollBottom.hidden = stickToBottom || el.scroller.scrollHeight <= el.scroller.clientHeight + 8;
  }, { passive: true });

  el.scrollBottom.addEventListener('click', function () { scrollToBottom(true); });

  // ---------------- 发送与流式接收 ----------------

  /** 中文星期名，latest_reminder 要求“提醒语言”下的星期。 */
  var WEEKDAYS_CN = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];

  /** 构造 latest_reminder 内容：<date>,<weekday>,<location>,<platform>,<language>。 */
  function buildReminder() {
    var d = new Date();
    var date = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2)
      + '-' + ('0' + d.getDate()).slice(-2);
    var parts = [date, WEEKDAYS_CN[d.getDay()], (settings.location || '').trim(), 'App', '中文'];
    return parts.join(',');
  }

  /** 把一条用户消息转成请求体里的形式：有附件时用内容块数组。 */
  function toUserParam(m) {
    var files = (m.files || []).filter(function (f) { return f.fileId; });
    if (!files.length) return { role: 'user', content: m.content || '' };
    var blocks = files.map(function (f) {
      return {
        type: f.blockType === 'image' ? 'image' : 'document',
        source: { type: 'file', file_id: f.fileId },
      };
    });
    if (m.content) blocks.push({ type: 'text', text: m.content });
    return { role: 'user', content: blocks };
  }

  /**
   * 把一条助手消息转成请求体里的形式。
   *
   * <p>服务端要求把上一轮的回复原样回传：搜索结果块被丢弃不影响上下文，
   * 但阅读链接的网页正文只存在于 web_fetch_tool_result.signature 中，
   * 不回传就等于把该网页从上下文里删掉，后续提问会凭空回答。
   * 因此这里按原始顺序重建 server_tool_use 与工具结果块，并保留 signature。</p>
   */
  function toAssistantParam(m) {
    var blocks = [];
    var tools = Array.isArray(m.tools) ? m.tools : [];
    if (!tools.length) {
      return { role: 'assistant', content: m.content || '' };
    }
    for (var i = 0; i < tools.length; i++) {
      var block = tools[i];
      if (!block || !block.type) continue;
      if (block.type === 'server_tool_use') {
        var call = { type: 'server_tool_use', id: block.id, name: block.name, input: block.input, kind: block.kind };
        blocks.push(call);
      } else if (block.type === 'web_search_tool_result' || block.type === 'web_fetch_tool_result') {
        blocks.push(block);
      }
    }
    // 正文始终放在最后，与服务端下发的块顺序一致。
    if (m.content) blocks.push({ type: 'text', text: m.content });
    if (!blocks.length) return { role: 'assistant', content: m.content || '' };
    return { role: 'assistant', content: blocks };
  }

  /** 请求是否引用了已上传文件：引用 file_id 时必须带 files-api 的 beta 头。 */
  function anyFileRef(messages) {
    for (var i = 0; i < messages.length; i++) {
      if (Object.prototype.toString.call(messages[i].content) === '[object Array]') return true;
    }
    return false;
  }

  function buildRequestBody(session, options) {
    var messages = [];
    for (var i = 0; i < session.messages.length; i++) {
      var m = session.messages[i];
      if (m.role === 'assistant' && m.error && !m.content) continue;
      var hasFiles = m.role === 'user' && m.files && m.files.some(function (f) { return f.fileId; });
      if (!m.content && !hasFiles) continue;
      messages.push(m.role === 'user' ? toUserParam(m) : toAssistantParam(m));
    }

    // 用户元信息必须紧挨在它所属的 user 消息之前，服务端只接受这个位置。
    if (settings.reminder) {
      var reminder = buildReminder();
      var firstUser = -1;
      for (var k = 0; k < messages.length; k++) {
        if (messages[k].role === 'user') { firstUser = k; break; }
      }
      if (firstUser >= 0) {
        messages.splice(firstUser, 0, { role: 'latest_reminder', content: reminder });
      }
    }

    var body = {
      model: settings.model || DEFAULT_SETTINGS.model,
      messages: messages,
      max_tokens: settings.maxTokens || DEFAULT_SETTINGS.maxTokens,
      stream: true,
      thinking: { type: settings.thinking ? 'enabled' : 'disabled' },
    };

    // 思考强度由 output_config.effort 控制；default 时不发送，走服务端默认档。
    if (settings.thinking && settings.effort && settings.effort !== 'default') {
      body.output_config = { effort: settings.effort };
    }
    // 服务端联网工具：声明即启用，是否真的搜索由模型判断。
    if (settings.webSearch || settings.readUrl) {
      var tools = [];
      if (settings.webSearch) tools.push({ type: 'deepseek_web_search', name: 'deepseek_web_search' });
      if (settings.readUrl) tools.push({ type: 'deepseek_read_url', name: 'deepseek_read_url' });
      body.tools = tools;
    }
    if (settings.autoTitle) body.title = { enabled: true };
    if (settings.safety) body.safety_check = { level: 'high' };

    // 续写：末尾放一条 assistant 消息，服务端会接着它继续生成。
    if (options && options.continuePrefix) {
      var last = messages.length ? messages[messages.length - 1] : null;
      // 若最后一条已经是 assistant（说明被续写的是最新回复），替换而不是叠加。
      if (last && last.role === 'assistant') {
        messages[messages.length - 1] = { role: 'assistant', content: options.continuePrefix };
      } else {
        messages.push({ role: 'assistant', content: options.continuePrefix });
      }
    }

    // 续写时附上任务说明：与用户自己写的系统提示词合并，用户的内容排在前面。
    var system = (settings.system || '').trim();
    if (options && options.continueSystem) {
      system = system ? (system + '\n\n' + options.continueSystem) : options.continueSystem;
    }
    if (system) body.system = system;
    // 通知原生层：本次请求引用了 Files API 的文件，需要额外请求头。
    body.__filesBeta = anyFileRef(messages);
    return body;
  }

  function send(text) {
    var content = (text || '').trim();
    if (streaming) return;
    // 有附件时允许不输入文字（图片本身即内容）。
    var ready = draftFiles.filter(function (f) { return f.fileId && !f.error; });
    if (!content && !ready.length) return;
    if (draftFiles.some(function (f) { return f.uploading; })) { toast('附件还在上传，请稍候'); return; }
    if (!settings.apiKey) { openSettings(); toast('请先填写 API Key'); return; }

    var session = ensureSession();
    var userMessage = { role: 'user', content: content, ts: Date.now() };
    if (ready.length) {
      userMessage.files = ready.map(function (f) {
        // path 指向应用私有缓存，用于在消息里显示缩略图；
        // fileId 才是回传给模型的东西，两者都要留。
        return {
          name: f.name, mime: f.mime, size: f.size,
          blockType: f.blockType, fileId: f.fileId, path: f.path,
        };
      });
    }
    session.messages.push(userMessage);
    draftFiles = [];
    renderDraftFiles();
    session.messages.push({ role: 'assistant', content: '', reasoning: '', ts: Date.now(), pending: true });
    touch(session);
    renderAll();
    el.welcome.hidden = true;
    scrollToBottom(true);

    var index = session.messages.length - 1;
    var assistant = session.messages[index];
    var view = createLiveView(session.id);
    startStream(session, assistant, view);
  }

  function startStream(session, assistant, view, options) {
    var requestId = uid();
    streaming = { requestId: requestId, sessionId: session.id, assistant: assistant, view: view };
    setComposerState(true);

    var body = buildRequestBody(session, options);

    var filesBeta = !!body.__filesBeta;
    delete body.__filesBeta;
    var payload = {
      id: requestId,
      origin: settings.origin,
      apiKey: settings.apiKey,
      filesBeta: filesBeta,
      body: JSON.stringify(body),
    };

    var blocks = {};
    var receivedStop = false;
    var pendingRender = false;

    function scheduleRender() {
      if (pendingRender) return;
      pendingRender = true;
      window.requestAnimationFrame(function () {
        pendingRender = false;
        updateLiveView(view, assistant);
        scrollToBottom(false);
      });
    }

    invoke(payload, function (event) {
      var block;
      switch (event.t) {
        case 'start':
          if (event.model) assistant.model = event.model;
          break;
        case 'bs':
          block = blocks[event.i] = { type: event.bt || 'text', signature: '' };
          if (block.type === 'thinking') {
            assistant.reasoning = (assistant.reasoning || '') + (event.x || '');
          } else if (block.type === 'text') {
            // 前缀已在 assistant.content 里，续写内容直接追加。
            assistant.content = (assistant.content || '') + (event.x || '');
          } else if (event.b) {
            // 服务端工具块：解析后挂到消息上，渲染成卡片。
            var payload;
            try { payload = JSON.parse(event.b); } catch (e) { payload = null; }
            if (payload) {
              payload.__i = event.i;
              block.payload = payload;
              assistant.tools = assistant.tools || [];
              assistant.tools.push(payload);
              scheduleRender();
            }
          }
          break;
        case 'd':
          block = blocks[event.i];
          if (!block) { block = blocks[event.i] = { type: event.dt === 'thinking_delta' ? 'thinking' : 'text', signature: '' }; }
          if (event.dt === 'text_delta') {
            assistant.content = (assistant.content || '') + (event.x || '');
          } else if (event.dt === 'thinking_delta') {
            assistant.reasoning = (assistant.reasoning || '') + (event.x || '');
          } else if (event.dt === 'signature_delta') {
            block.signature += event.s || '';
            assistant.signature = block.signature;
          } else if (event.dt === 'citations_delta') {
            var c;
            try { c = JSON.parse(event.x); } catch (e) { c = null; }
            if (c && c.url) {
              assistant.citations = assistant.citations || [];
              var seen = false;
              for (var ci = 0; ci < assistant.citations.length; ci++) {
                if (assistant.citations[ci].url === c.url) { seen = true; break; }
              }
              if (!seen) assistant.citations.push({ url: c.url, title: c.title || '' });
              scheduleRender();
            }
          }
          scheduleRender();
          break;
        case 'md':
          if (event.stop) assistant.stopReason = event.stop;
          // 服务端生成的标题优先于本地取首句的占位标题。
          if (event.title && !session.titleAuto) {
            session.title = event.title;
            session.titleAuto = true;
            updateTitle();
          }
          break;
        case 'stop':
          receivedStop = true;
          break;
        case 'cut':
          assistant.error = '回复被中断';
          break;
        case 'err':
          assistant.error = event.msg || '请求失败';
          break;
        case 'end':
          finishStream(session, assistant, view, receivedStop);
          break;
        default:
          break;
      }
    });
  }

  function finishStream(session, assistant, view, receivedStop) {
    streaming = null;
    setComposerState(false);
    delete assistant.pending;
    if (!assistant.content && !assistant.error) {
      assistant.error = receivedStop ? '模型没有返回内容' : '回复未完成';
    }
    assistant.ts = Date.now();
    updateLiveView(view, assistant);
    finishLiveView(view, assistant);
    touch(session);
    updateTitle();
    scrollToBottom(false);
  }

  function stopStream() {
    if (!streaming) return;
    native.chatAbort(streaming.requestId);
  }

  // ---------------- 编辑消息 ----------------

  /** 正在编辑的消息下标；-1 表示没有在编辑。 */
  var editingIndex = -1;

  /**
   * 进入编辑：把该条用户消息放回输入框，输入区上方显示编辑提示条。
   *
   * <p>发送后会删除这条消息及其后所有内容再重新提问 —— 服务端是无状态的，
   * 每次请求都要拼接完整历史，保留被改掉的那轮之后的回答会让上下文自相矛盾。</p>
   */
  function startEdit(index) {
    var session = currentSession();
    if (!session || streaming) return;
    var message = session.messages[index];
    if (!message || message.role !== 'user') return;
    editingIndex = index;
    el.input.value = message.content || '';
    autoGrow();
    setComposerState(false);
    renderEditBar(message);
    el.input.focus();
    // 光标移到末尾，方便直接续写或全选重写。
    try {
      el.input.setSelectionRange(el.input.value.length, el.input.value.length);
    } catch (e) { /* 忽略 */ }
    el.scroller.scrollTop = el.scroller.scrollHeight;
  }

  /** 渲染输入区上方的编辑提示条。 */
  function renderEditBar(message) {
    if (editingIndex < 0) { el.editBar.hidden = true; return; }
    el.editBar.hidden = false;
    el.editBarText.textContent = message.files && message.files.length
      ? '正在编辑这条消息（含附件）'
      : '正在编辑这条消息';
  }

  /** 退出编辑状态，清空输入框。 */
  function cancelEdit() {
    editingIndex = -1;
    if (editOutputIndex >= 0) editOutputIndex = -1;
    if (continueIndex >= 0) continueIndex = -1;
    el.input.value = '';
    autoGrow();
    el.editBar.hidden = true;
    setComposerState(!!streaming);
  }

  /** 提交编辑：截断到该轮之前，再用新内容重新提问。 */
  function submitEdit(text) {
    var session = currentSession();
    if (!session) return;
    var index = editingIndex;
    var message = session.messages[index];
    var files = (message && message.files) || [];
    // 删除被编辑的这条及其后所有消息。
    session.messages.splice(index, session.messages.length - index);
    if (!session.titleAuto) session.title = '';
    saveSessions();
    editingIndex = -1;
    el.editBar.hidden = true;
    renderAll();
    // 附件随原消息保留，重新发送。
    draftFiles = files.map(function (f) {
      return {
        name: f.name, mime: f.mime, size: f.size, path: f.path,
        blockType: f.blockType, fileId: f.fileId, uploading: false, error: null,
      };
    });
    renderDraftFiles();
    send(text);
  }

  // ---------------- 编辑输出（纯本地，不调 API） ----------------

  /** 正在编辑的助手消息下标；-1 表示没有。 */
  var editOutputIndex = -1;

  /**
   * 编辑助手回复：纯本地改写，不请求接口。
   *
   * <p>改完的内容直接替换这条回复，并在下一轮对话时作为历史 assistant 消息回传给模型，
   * 这样后续回答会基于你修正后的版本继续，而不是基于原来的文字。
   * 与「续写」的区别：续写会调接口让模型接着写；编辑只改本地文本。</p>
   *
   * @param index 助手消息下标
   */
  function startEditOutput(index) {
    var session = currentSession();
    if (!session || streaming) return;
    var message = session.messages[index];
    if (!message || message.role !== 'assistant') return;
    editOutputIndex = index;
    el.input.value = message.content || '';
    autoGrow();
    setComposerState(false);
    el.editBar.hidden = false;
    el.editBarText.textContent = '正在编辑这条回复，保存后下一轮会用它作为历史';
    el.input.focus();
    try {
      el.input.setSelectionRange(el.input.value.length, el.input.value.length);
    } catch (e) { /* 忽略 */ }
    el.scroller.scrollTop = el.scroller.scrollHeight;
  }

  /** 保存编辑后的回复：只改本地，不发请求。 */
  function submitEditOutput(text) {
    var session = currentSession();
    if (!session) return;
    var index = editOutputIndex;
    editOutputIndex = -1;
    el.editBar.hidden = true;
    var message = session.messages[index];
    if (!message) return;
    var changed = (message.content || '') !== text;
    message.content = text;
    message.edited = true;
    message.ts = Date.now();
    saveSessions();
    renderAll();
    toast(changed ? '已保存，下一轮会使用修改后的内容' : '已保存');
  }

  // ---------------- 续写（不是编辑） ----------------

  /** 正在续写的助手消息下标；-1 表示没有。 */
  var continueIndex = -1;

  /**
   * 进入续写：把这条回复放回输入框，可以修改，再以它为前缀让模型接着写。
   *
   * <p>与「编辑」的区别：编辑只改本地文本、不请求接口；续写会拿你改过的这段内容
   * 作为 assistant 前缀重新请求，让模型在它后面继续生成。</p>
   *
   * @param index 要续写的助手消息下标
   */
  function startContinue(index) {
    var session = currentSession();
    if (!session || streaming) return;
    var message = session.messages[index];
    if (!message || message.role !== 'assistant') return;
    // 联网工具用过的回复不能续写，服务端会返回 400。
    if (Array.isArray(message.tools) && message.tools.length) {
      toast('这条回复用了联网工具，不能续写，请用重新生成');
      return;
    }
    continueIndex = index;
    el.input.value = message.content || '';
    autoGrow();
    setComposerState(false);
    el.editBar.hidden = false;
    el.editBarText.textContent = '正在续写这条回复，发送后从这里接着写';
    el.input.focus();
    try {
      el.input.setSelectionRange(el.input.value.length, el.input.value.length);
    } catch (e) { /* 忽略 */ }
    el.scroller.scrollTop = el.scroller.scrollHeight;
  }

  /** 提交续写：截断该条及其后内容，以编辑后的文本为前缀重新请求。 */
  function submitContinue(text) {
    var session = currentSession();
    if (!session) return;
    var index = continueIndex;
    var original = session.messages[index];
    session.messages.splice(index, session.messages.length - index);
    continueIndex = -1;
    el.editBar.hidden = true;
    saveSessions();
    renderAll();
    sendContinue(session, text, original);
  }

  /**
   * 续写用的系统说明。
   *
   * <p>只把原文作为末尾 assistant 消息发出去时，模型常把它当成"待评价的句子"，
   * 于是回出「这句话可以这样补完：」这类元话语，或干脆整段重写。
   * 明确告诉它这是续写任务后，五种典型场景（半句/完整句/英文/代码/列表）都实测正常。</p>
   */
  var CONTINUE_SYSTEM = '用户给出一段已有的开头，你要自然地接着往下写。'
    + '只输出续写的内容，不要重复开头，不要解释、评价或加标题。';

  /**
   * 以给定前缀请求续写，并把「前缀 + 续写」合并成一条新回复。
   *
   * @param session  当前会话
   * @param prefix   用户编辑后的前缀文本
   * @param original 被编辑的原消息（沿用其工具块与引用）
   */
  function sendContinue(session, prefix, original) {
    var assistant = {
      role: 'assistant', content: prefix, reasoning: '', ts: Date.now(), pending: true,
    };
    if (original && Array.isArray(original.tools)) assistant.tools = original.tools.slice();
    if (original && Array.isArray(original.citations)) assistant.citations = original.citations.slice();
    session.messages.push(assistant);
    touch(session);
    renderAll();
    var view = createLiveView(session.id, prefix);
    startStream(session, assistant, view, { continuePrefix: prefix, continueSystem: CONTINUE_SYSTEM });
  }

  function regenerate(index) {
    var session = currentSession();
    if (!session || streaming) return;
    if (index !== session.messages.length - 1) return;
    var last = session.messages[index];
    if (!last || last.role !== 'assistant') return;
    var userIndex = index - 1;
    if (userIndex < 0 || session.messages[userIndex].role !== 'user') return;
    var userText = session.messages[userIndex].content;
    session.messages.splice(index, 1);
    session.messages.splice(userIndex, 1);
    saveSessions();
    renderAll();
    send(userText);
  }

  // ---------------- 附件 ----------------

  function formatSize(bytes) {
    if (!bytes && bytes !== 0) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  }

  function renderDraftFiles() {
    var row = el.attachRow;
    row.innerHTML = '';
    if (!draftFiles.length) { row.hidden = true; return; }
    row.hidden = false;
    draftFiles.forEach(function (file, index) {
      var chip = document.createElement('div');
      chip.className = 'attach-chip' + (file.error ? ' error' : '') + (file.uploading ? ' uploading' : '');
      var name = document.createElement('span');
      name.className = 'attach-name';
      name.textContent = file.name || '文件';
      var meta = document.createElement('span');
      meta.className = 'attach-meta';
      meta.textContent = file.error ? file.error
        : (file.uploading ? '上传中…' : (formatSize(file.size) + (file.blockType === 'image' ? ' · 图片' : '')));
      var del = document.createElement('button');
      del.type = 'button';
      del.className = 'attach-del';
      del.setAttribute('aria-label', '移除附件');
      del.textContent = '×';
      del.addEventListener('click', function () {
        draftFiles.splice(index, 1);
        renderDraftFiles();
        setComposerState(!!streaming);
      });
      chip.appendChild(name);
      chip.appendChild(meta);
      chip.appendChild(del);
      row.appendChild(chip);
    });
  }

  /** 选文件 → 逐个上传，拿到 file_id 后才能发送。 */
  function pickAttachments() {
    if (!native.hasFiles()) { toast('当前环境不支持选择文件'); return; }
    var callbackId = 'pick' + (++callbackSeq);
    callbacks[callbackId] = function (ok, data) {
      if (!ok) { toast(data && data.message ? data.message : '选择文件失败'); return; }
      var files = (data && data.files) || [];
      if (!files.length) return;
      files.forEach(function (f) {
        var item = {
          name: f.name, mime: f.mime, size: f.size, path: f.path,
          blockType: f.blockType || 'document', fileId: null, uploading: true, error: null,
        };
        draftFiles.push(item);
        uploadDraftFile(item);
      });
      renderDraftFiles();
      setComposerState(!!streaming);
    };
    native.pickFiles(callbackId);
  }

  function uploadDraftFile(item) {
    var callbackId = 'up' + (++callbackSeq);
    callbacks[callbackId] = function (ok, data) {
      item.uploading = false;
      if (ok && data && data.fileId) {
        item.fileId = data.fileId;
        if (data.blockType) item.blockType = data.blockType;
      } else {
        item.error = (data && data.message) || '上传失败';
      }
      renderDraftFiles();
      setComposerState(!!streaming);
    };
    native.uploadFile({
      callbackId: callbackId,
      origin: settings.origin,
      apiKey: settings.apiKey,
      path: item.path,
      name: item.name,
      mime: item.mime,
      purpose: 'auto',
    });
  }

  // ---------------- 语音输入 ----------------

  function toggleVoice() {
    if (asrState && asrState.active) { stopVoice(); return; }
    if (!native.hasAsr()) { toast('当前环境不支持语音输入'); return; }
    if (!settings.apiKey) { openSettings(); toast('请先填写 API Key'); return; }
    var id = 'asr' + (++callbackSeq);
    asrState = { id: id, active: true };
    callbacks[id] = function (ok, data) {
      asrState = null;
      el.btnMic.classList.remove('recording');
      el.input.placeholder = '发消息…';
      if (ok && data && data.text) {
        var existing = el.input.value;
        el.input.value = existing ? (existing + ' ' + data.text) : data.text;
        autoGrow();
        setComposerState(!!streaming);
      } else {
        toast((data && data.message) || '语音识别失败');
      }
    };
    native.asrStart({ id: id, origin: settings.origin, apiKey: settings.apiKey });
    el.btnMic.classList.add('recording');
    el.input.placeholder = '正在录音，再点一次结束…';
    native.haptic();
  }

  function stopVoice() {
    if (!asrState) return;
    var id = asrState.id;
    asrState = null;
    el.btnMic.classList.remove('recording');
    el.input.placeholder = '识别中…';
    native.asrStop(id);
  }

  // ---------------- 朗读 ----------------

  var speakingIndex = -1;

  function speakMessage(message, index) {
    if (!native.hasTts()) { toast('当前环境不支持朗读'); return; }
    if (!settings.apiKey) { openSettings(); toast('请先填写 API Key'); return; }
    var text = message.content || '';
    if (!text) { toast('没有可朗读的内容'); return; }
    if (speakingIndex === index) { stopSpeaking(); return; }
    stopSpeaking();
    speakingIndex = index;
    refreshSpeakButtons();
    var callbackId = 'tts' + (++callbackSeq);
    callbacks[callbackId] = function (ok, data) {
      var ev = data && data.event;
      if (ev === 'end') {
        speakingIndex = -1;
        refreshSpeakButtons();
      } else if (ev === 'error') {
        speakingIndex = -1;
        refreshSpeakButtons();
        toast((data && data.message) || '朗读失败');
      }
    };
    native.ttsSpeak({
      callbackId: callbackId,
      origin: settings.origin,
      apiKey: settings.apiKey,
      text: text,
      voiceId: settings.voice || 'stella',
    });
  }

  function stopSpeaking() {
    if (speakingIndex < 0) return;
    speakingIndex = -1;
    native.ttsStop();
    refreshSpeakButtons();
  }

  function refreshSpeakButtons() {
    var btns = document.querySelectorAll('.tool-btn[data-speak-index]');
    for (var i = 0; i < btns.length; i++) {
      var idx = parseInt(btns[i].getAttribute('data-speak-index'), 10);
      btns[i].textContent = idx === speakingIndex ? '停止' : '朗读';
      btns[i].classList.toggle('active', idx === speakingIndex);
    }
  }

  // ---------------- 输入区 ----------------

  function autoGrow() {
    var input = el.input;
    // 先归零再量 scrollHeight，否则量到的是上一次设置的高度，输入框永远长不高。
    input.style.height = '0px';
    input.style.overflowY = 'hidden';
    var contentHeight = input.scrollHeight;
    // 上限按「扣除键盘后的可用高度」计算：键盘展开时可用空间小得多，
    // 若仍按整屏比例估算，输入框会超出可视区，最后几行被裁掉。
    var kb = parseFloat(getComputedStyle(document.documentElement)
      .getPropertyValue('--keyboard-height')) || 0;
    var available = window.innerHeight - kb;
    var max = Math.round(Math.max(88, available * 0.42));
    var next = Math.min(contentHeight, max);
    input.style.height = next + 'px';
    input.style.overflowY = contentHeight > max ? 'auto' : 'hidden';
  }

  function setComposerState(isStreaming) {
    el.btnStop.hidden = !isStreaming;
    // 编辑输出或续写时即使清空也要能提交。
    var editable = editOutputIndex >= 0 || continueIndex >= 0;
    el.btnSend.hidden = isStreaming || (!el.input.value.trim() && !editable);
  }

  el.input.addEventListener('input', function () {
    autoGrow();
    setComposerState(!!streaming);
  });

  el.input.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      submit();
    }
  });

  el.btnSend.addEventListener('click', submit);
  el.btnStop.addEventListener('click', function () { native.haptic(); stopStream(); });
  el.btnAttach.addEventListener('click', function () { native.haptic(); pickAttachments(); });
  el.btnMic.addEventListener('click', function () { toggleVoice(); });

  function submit() {
    var text = el.input.value;
    if (streaming) return;
    // 续写态：以输入框内容为前缀重新请求，允许清空（等于让模型重写这一条）。
    if (continueIndex >= 0) {
      el.input.value = '';
      autoGrow();
      setComposerState(false);
      submitContinue(text);
      return;
    }
    // 编辑输出态：纯本地保存，不发请求。
    if (editOutputIndex >= 0) {
      el.input.value = '';
      autoGrow();
      setComposerState(false);
      submitEditOutput(text);
      return;
    }
    // 编辑态：即使清空文字也允许提交（用户可以只保留附件）。
    var inEdit = editingIndex >= 0;
    if (!inEdit && !text.trim()) return;
    if (inEdit) {
      if (!text.trim() && !draftFiles.length) { cancelEdit(); return; }
      el.input.value = '';
      autoGrow();
      setComposerState(false);
      submitEdit(text);
      return;
    }
    if (!text.trim()) return;
    el.input.value = '';
    autoGrow();
    setComposerState(false);
    send(text);
  }

  // ---------------- 抽屉与会话列表 ----------------

  function openDrawer() {
    renderSessions();
    el.drawer.classList.add('open');
    el.scrim.hidden = false;
    window.requestAnimationFrame(function () { el.scrim.classList.add('show'); });
  }

  function closeDrawer() {
    el.drawer.classList.remove('open');
    el.scrim.classList.remove('show');
    window.setTimeout(function () { if (!el.drawer.classList.contains('open')) el.scrim.hidden = true; }, 220);
  }

  function renderSessions() {
    el.sessions.innerHTML = '';
    if (sessions.length === 0) {
      var empty = document.createElement('div');
      empty.className = 'empty-hint';
      empty.textContent = '还没有对话';
      el.sessions.appendChild(empty);
      return;
    }
    sessions.forEach(function (session) {
      var item = document.createElement('div');
      item.className = 'session-item' + (session.id === currentId ? ' active' : '');
      var title = document.createElement('div');
      title.className = 'session-title';
      title.textContent = session.title || '新对话';
      var meta = document.createElement('div');
      meta.className = 'session-meta';
      meta.textContent = session.messages.length + ' 条';
      item.appendChild(title);
      item.appendChild(meta);

      var del = document.createElement('button');
      del.type = 'button';
      del.className = 'session-del';
      del.setAttribute('aria-label', '删除对话');
      del.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
      del.addEventListener('click', function (event) {
        event.stopPropagation();
        confirmDialog('删除这个对话？', function () { removeSession(session.id); });
      });
      item.appendChild(del);

      item.addEventListener('click', function () {
        if (streaming) stopStream();
        if (editingIndex >= 0) cancelEdit();
        currentId = session.id;
        native.storeSet(STORE_CURRENT, currentId);
        closeDrawer();
        renderAll();
      });
      el.sessions.appendChild(item);
    });
  }

  function removeSession(id) {
    sessions = sessions.filter(function (s) { return s.id !== id; });
    if (currentId === id) {
      currentId = sessions.length ? sessions[0].id : '';
      native.storeSet(STORE_CURRENT, currentId);
    }
    saveSessions();
    renderSessions();
    renderAll();
  }

  function newChat() {
    if (streaming) stopStream();
    if (editingIndex >= 0) cancelEdit();
    var session = { id: uid(), title: '', updatedAt: Date.now(), messages: [] };
    sessions.unshift(session);
    currentId = session.id;
    native.storeSet(STORE_CURRENT, currentId);
    saveSessions();
    closeDrawer();
    renderAll();
    renderSessions();
  }

  // ---------------- 设置 ----------------

  function openSettings() {
    $('inp-key').value = settings.apiKey || '';
    keyRevealed = false;
    refreshKeyMask();
    $('inp-base').value = settings.origin || '';
    $('inp-model').value = settings.model || '';
    $('inp-thinking').checked = !!settings.thinking;
    $('inp-effort').value = settings.effort || 'default';
    $('inp-maxtokens').value = String(settings.maxTokens || DEFAULT_SETTINGS.maxTokens);
    $('inp-search').checked = !!settings.webSearch;
    $('inp-readurl').checked = !!settings.readUrl;
    $('inp-title').checked = !!settings.autoTitle;
    $('inp-safety').checked = !!settings.safety;
    $('inp-voice').value = settings.voice || 'stella';
    $('inp-reminder').checked = !!settings.reminder;
    $('inp-location').value = settings.location || '';
    $('inp-system').value = settings.system || '';
    $('inp-theme').value = settings.theme || 'system';
    syncDependentFields();
    el.settings.classList.add('open');
  }

  /** 密钥是否处于明文显示状态。 */
  var keyRevealed = false;

  /** 刷新密钥框的显示：隐藏时用圆点遮罩，但底层仍是可粘贴的普通文本框。 */
  function refreshKeyMask() {
    var input = $('inp-key');
    var real = input.value;
    // 用 ::-webkit-text-security 做遮罩，避免 type=password 触发安全键盘。
    input.style.webkitTextSecurity = keyRevealed ? 'none' : 'disc';
    $('btn-toggle-key').textContent = keyRevealed ? '隐藏' : '显示';
    return real;
  }

  /** 从剪贴板粘贴密钥。 */
  function pasteKey() {
    var text = native.readClipboard();
    if (!text) {
      toast('剪贴板里没有可粘贴的内容');
      return;
    }
    // 只保留可见 ASCII，去掉换行与首尾空白，避免误粘整段文字。
    var cleaned = text.replace(/\s+/g, '').trim();
    var match = cleaned.match(/sk-[A-Za-z0-9_-]+/);
    $('inp-key').value = match ? match[0] : cleaned;
    native.haptic();
    toast('已粘贴');
  }

  /** 思考关闭时隐藏强度；未开启时间地点时隐藏所在地。 */
  function syncDependentFields() {
    var thinkingOn = $('inp-thinking').checked;
    $('field-effort').style.display = thinkingOn ? '' : 'none';
    var reminderOn = $('inp-reminder').checked;
    $('field-location').style.display = reminderOn ? '' : 'none';
  }

  function closeSettings() { el.settings.classList.remove('open'); }

  function readSettingsForm() {
    var next = mergeSettings(settings);
    next.apiKey = $('inp-key').value.trim();
    next.origin = ($('inp-base').value.trim() || DEFAULT_SETTINGS.origin).replace(/\/+$/, '');
    next.model = $('inp-model').value.trim() || DEFAULT_SETTINGS.model;
    next.thinking = $('inp-thinking').checked;
    next.effort = $('inp-effort').value || 'default';
    var maxTokens = parseInt($('inp-maxtokens').value, 10);
    next.maxTokens = isFinite(maxTokens) && maxTokens >= 256
      ? Math.min(maxTokens, 393216) : DEFAULT_SETTINGS.maxTokens;
    next.webSearch = $('inp-search').checked;
    next.readUrl = $('inp-readurl').checked;
    next.autoTitle = $('inp-title').checked;
    next.safety = $('inp-safety').checked;
    next.voice = $('inp-voice').value || 'stella';
    next.reminder = $('inp-reminder').checked;
    next.location = $('inp-location').value.trim();
    next.system = $('inp-system').value;
    next.theme = $('inp-theme').value;
    return next;
  }

  function verifyConnection() {
    var next = readSettingsForm();
    if (!next.apiKey) { toast('请先填写 API Key'); return; }
    var btn = $('btn-verify');
    btn.classList.add('busy');
    btn.disabled = true;
    var id = 'cb' + (++callbackSeq);
    callbacks[id] = function (ok, data) {
      btn.classList.remove('busy');
      btn.disabled = false;
      toast(ok ? '连接正常' : ('连接失败：' + (data.message || '未知错误')));
    };
    native.verify({ id: id, origin: next.origin, apiKey: next.apiKey, body: '{}' }, id);
  }

  function saveSettingsHandler() {
    settings = readSettingsForm();
    saveSettings();
    applyTheme();
    closeSettings();
    toast('已保存');
  }

  function clearAll() {
    confirmDialog('清空全部对话？此操作不可恢复。', function () {
      if (streaming) stopStream();
      sessions = [];
      currentId = '';
      native.storeSet(STORE_CURRENT, '');
      saveSessions();
      renderSessions();
      renderAll();
      toast('已清空');
    });
  }

  // ---------------- 通用交互 ----------------

  var toastTimer = null;

  function toast(text) {
    el.toast.textContent = text;
    el.toast.hidden = false;
    el.toast.classList.add('toast-show');
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () {
      el.toast.classList.remove('toast-show');
      window.setTimeout(function () { el.toast.hidden = true; }, 200);
    }, 1800);
  }

  var confirmHandler = null;

  function confirmDialog(text, onOk) {
    el.confirmText.textContent = text;
    confirmHandler = onOk;
    el.confirm.hidden = false;
  }

  function closeConfirm() {
    el.confirm.hidden = true;
    confirmHandler = null;
  }

  $('confirm-ok').addEventListener('click', function () {
    var fn = confirmHandler;
    closeConfirm();
    if (fn) fn();
  });
  $('confirm-cancel').addEventListener('click', closeConfirm);

  // ---------------- 事件绑定 ----------------

  $('btn-drawer').addEventListener('click', function () { native.haptic(); openDrawer(); });
  $('btn-drawer-close').addEventListener('click', closeDrawer);
  $('btn-new').addEventListener('click', function () { native.haptic(); newChat(); });
  $('btn-new-chat').addEventListener('click', function () { native.haptic(); newChat(); });
  $('btn-settings').addEventListener('click', function () { native.haptic(); closeDrawer(); openSettings(); });
  $('btn-settings-close').addEventListener('click', closeSettings);
  $('btn-save-settings').addEventListener('click', saveSettingsHandler);
  $('btn-cancel-edit').addEventListener('click', cancelEdit);
  $('btn-paste-key').addEventListener('click', pasteKey);
  $('btn-toggle-key').addEventListener('click', function () {
    keyRevealed = !keyRevealed;
    refreshKeyMask();
  });
  $('inp-thinking').addEventListener('change', syncDependentFields);
  $('inp-reminder').addEventListener('change', syncDependentFields);
  $('btn-verify').addEventListener('click', verifyConnection);
  $('btn-clear').addEventListener('click', clearAll);
  el.scrim.addEventListener('click', closeDrawer);

  window.__onAndroidBack = function () {
    if (!el.confirm.hidden) { closeConfirm(); return true; }
    if (el.settings.classList.contains('open')) { closeSettings(); return true; }
    if (el.drawer.classList.contains('open')) { closeDrawer(); return true; }
    return false;
  };

  window.addEventListener('resize', function () {
    autoGrow();
    if (stickToBottom) scrollToBottom(true);
  });

  /**
   * 监听原生注入的 --keyboard-height 变化：键盘弹出/收起时调整标记并滚到底部。
   *
   * <p>窗口是全屏绘制，系统不会缩小可视区，键盘高度由原生按 IME insets 写入，
   * 这里只负责把「键盘是否展开」这个状态同步给样式，保证底部安全区不重复叠加。</p>
   */
  function watchKeyboard() {
    var root = document.documentElement;
    var lastOpen = null;
    function sync() {
      var raw = getComputedStyle(root).getPropertyValue('--keyboard-height').trim();
      var px = parseFloat(raw) || 0;
      var open = px > 0 ? 1 : 0;
      if (open === lastOpen) return;
      lastOpen = open;
      root.style.setProperty('--keyboard-open', String(open));
      // 键盘展开/收起都会改变可用高度，需要重算输入框高度再滚动定位。
      window.setTimeout(function () {
        autoGrow();
        scrollToBottom(true);
      }, 60);
    }
    sync();
    // CSS 变量变化没有事件，用轻量轮询覆盖键盘弹出/收起的时机。
    window.setInterval(sync, 250);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', sync);
    }
  }

  watchKeyboard();

  // ---------------- 启动 ----------------

  function boot() {
    applyTheme();
    // 上次运行若被强制中断，占位消息会残留 pending 标记，这里统一清理。
    for (var s = 0; s < sessions.length; s++) {
      for (var m = 0; m < sessions[s].messages.length; m++) {
        delete sessions[s].messages[m].pending;
      }
    }
    if (!currentId && sessions.length) currentId = sessions[0].id;
    renderAll();
    renderSessions();
    autoGrow();
    setComposerState(false);
    if (!settings.apiKey) {
      window.setTimeout(function () { openSettings(); }, 260);
    }
  }

  // ---------------- 浏览器预览降级实现（仅在没有原生桥时启用） ----------------

  var FENCE = String.fromCharCode(96, 96, 96);
  var TICK = String.fromCharCode(96);

  function previewMarkdown(md) {
    var html = esc(md);
    var fenceRe = new RegExp(FENCE + '([a-zA-Z0-9+-]*)\\n([\\s\\S]*?)' + FENCE, 'g');
    html = html.replace(fenceRe, function (_, lang, code) {
      return '<div class="md-pre">' + (lang ? '<div class="md-code-lang">' + lang + '</div>' : '') +
        '<pre><code class="md-code">' + code.replace(/\n$/, '') + '</code></pre></div>';
    });
    var tickRe = new RegExp(TICK + '([^' + TICK + '\\n]+)' + TICK, 'g');
    html = html.replace(tickRe, '<code class="md-inline-code">$1</code>');
    html = html.replace(/\*\*([^*\n]+)\*\*/g, '<strong class="md-strong">$1</strong>');
    html = html.replace(/^### (.+)$/gm, '<div class="md-h3">$1</div>');
    html = html.replace(/^## (.+)$/gm, '<div class="md-h2">$1</div>');
    html = html.replace(/^# (.+)$/gm, '<div class="md-h1">$1</div>');
    var parts = html.split(/\n{2,}/);
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i].trim();
      if (!part) continue;
      if (/^<div class="md-h[1-6]">/.test(part) || /^<div class="md-pre">/.test(part)) { out.push(part); continue; }
      if (/^(&gt;) /.test(part)) { out.push('<div class="md-quote">' + part.replace(/^(&gt;\s*)+/, '') + '</div>'); continue; }
      if (/^[-*+] /.test(part)) {
        var items = part.split('\n').map(function (line) { return '<li class="md-li">' + line.replace(/^[-*+]\s+/, '') + '</li>'; }).join('');
        out.push('<ul class="md-ul">' + items + '</ul>');
        continue;
      }
      if (/^\d+\. /.test(part)) {
        var oitems = part.split('\n').map(function (line) { return '<li class="md-li">' + line.replace(/^\d+\.\s+/, '') + '</li>'; }).join('');
        out.push('<ol class="md-ol">' + oitems + '</ol>');
        continue;
      }
      out.push('<p class="md-p">' + part.replace(/\n/g, '<br>') + '</p>');
    }
    return out.join('');
  }

  function previewChat(payload, onEvent) {
    var body = parseJson(payload.body, { messages: [] });
    var users = (body.messages || []).filter(function (m) { return m.role === 'user'; });
    var question = users.length ? users[users.length - 1].content : '';
    var answer = '**预览模式**（未运行在 Android 容器中）\n\n' +
      '你刚才问的是：' + (question || '（空）') + '\n\n' +
      '在真实设备上，这里会显示模型的流式回答。下面是排版样式示例：\n\n' +
      '### 主要能力\n\n- 流式输出，深度思考可折叠\n- Markdown、代码块、表格、引用\n- 会话与设置在本地保存\n\n' +
      FENCE + 'java\nSystem.out.println("hello PureChat");\n' + FENCE + '\n\n' +
      '> 不预置任何提示词。\n\n' +
      '| 配置项 | 当前值 |\n| --- | --- |\n| 模型 | ' + (body.model || '') + ' |\n| 思考 | ' +
      ((body.thinking && body.thinking.type) || 'disabled') + ' |\n';
    var parts = answer.match(/[\s\S]{1,3}/g) || [];
    var i = 0;
    onEvent({ t: 'open' });
    onEvent({ t: 'start', id: 'preview', model: body.model || '' });
    onEvent({ t: 'bs', i: 0, bt: 'text', x: '' });
    var timer = window.setInterval(function () {
      if (i >= parts.length) {
        window.clearInterval(timer);
        onEvent({ t: 'md', stop: 'end_turn' });
        onEvent({ t: 'stop' });
        onEvent({ t: 'end' });
        return;
      }
      onEvent({ t: 'd', i: 0, dt: 'text_delta', x: parts[i++] });
    }, 16);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
