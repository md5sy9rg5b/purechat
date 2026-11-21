# PureChat WebView 样式契约（styles.css）

目标文件：D:\work\purechat\web\src\styles.css（单文件，纯 CSS，无构建、无预处理器、无外部字体/图片）。
HTML 结构见 D:\work\purechat\web\src\index.html，**不要修改 index.html**。
视觉参考项目（可读取，用于取色板与间距/圆角风格，但不要照搬布局类名）：D:\work\chat-demo-src\src\app\theme.css、App.module.css、src\components\messages\*.module.css、src\components\chatInput\ChatInput.module.css。
参考项目设计语言要点：system-ui 字体、中性偏蓝灰的层级色、品牌蓝 accent（浅色 #3964FE / 深色保持同色）、浅色背景纯白、深色背景 rgb(21,21,23) 系、圆角 8/12/16/22、正文字号 16px/行高 28px、过渡 0.2s cubic-bezier(0.4,0,0.2,1)。

## 运行环境
- Android WebView（Chromium 内核），视口宽度 320–480dp，需 /100 适配；页面整体不滚动，只有 #scroller 内部滚动。
- 顶部 #topbar、底部 #composer 固定；中间 #scroller 占据剩余高度。
- 必须支持 safe-area：padding 使用 env(safe-area-inset-top/bottom)，通过 #app 的 padding 或 topbar/composer 的 padding 实现。
- 必须支持浅色/深色：`body[data-theme="light"]` 与 `body[data-theme="dark"]` 两组变量；默认 light。
- 禁止使用 position:fixed 的整页背景 hack；不要禁用文本选择（用户要能复制回答）。
- 全局：-webkit-tap-highlight-color: transparent；overscroll-behavior: contain；touch-action 正常滚动；输入框 font-size 必须 ≥16px（防 iOS/WebView 自动放大）。
- 键盘弹出时布局必须优雅（Android 用 adjustResize，不需 CSS 特殊处理，但 #app 必须是 height:100%）。

## 必须存在的选择器（id 与 class 名严格一致，JS 会动态增删下列 class）
布局：html,body,#app,#topbar,#scroller,#messages,#welcome,#composer,#composer-inner,#input,#btn-send,#btn-stop,#scroll-bottom,#scrim,#drawer,#sessions,#settings,#toast,#confirm
顶栏：.icon-btn（44x44 触摸目标、内联 svg 24px、stroke 风格 path 无 fill）、.title（单行省略）
欢迎页：.welcome[hidden] 隐藏、.welcome-mark（品牌图标容器）、.welcome-title
消息列表：
  .msg（一条消息容器，含 .msg.user 与 .msg.assistant 两种）
  .msg-body（气泡或正文容器）
  .msg-tools（消息下方的操作行，默认 opacity:0，.msg:hover/.msg.show-tools 时显示；移动端改为常显但低对比）
  .msg-time（时间戳，小号灰字）
  .typing-caret（流式输出光标：内联块，1px 宽或小方块，闪烁动画）
  .thinking-box / .thinking-head / .thinking-body / .thinking-title（思考过程折叠区，点击展开；.thinking-box.open 时显示 body）
  .error-box（错误提示卡片，红色系）
  .empty-hint（居中弱提示）
输入区：.send-btn（44x44 圆形主色按钮，disabled 状态）、.send-btn.stop（停止态，用中性色）、#input（自增高 textarea，max-height 约 40vh，无边框，背景透明，resize:none）
抽屉：#drawer（左侧滑出，宽 min(84vw, 320px)，transform 过渡 0.25s）、#drawer.open 打开；#scrim 半透明遮罩（opacity 过渡），#scrim[hidden] 时 display:none
  .drawer-top / .drawer-brand / .new-chat（新建对话主按钮）/ .sessions / .drawer-foot / .foot-btn
  会话项：.session-item（含 .session-title 单行省略、.session-meta 小字）、.session-item.active（当前会话高亮）、.session-del（右侧删除按钮，仅 .session-item:hover 或 .active 时显示）
设置面板：#settings（从底部滑出的全屏 sheet，z-index 高于 drawer）、#settings.open；.sheet-head / .sheet-title / .sheet-body
  .field（标签+输入竖排）/ .field-label / .field-input（圆角输入框，聚焦时主色描边）/ .field-textarea / .field-select
  .row（左右两端对齐行）/ .row-label / .switch（checkbox 做成的开关，选中态主色）
  .wide-btn（整宽按钮，次要样式）/ .wide-btn.primary（主色实底）/ .wide-btn.danger（危险红）/ .sheet-note（小号灰字说明）
  #btn-verify 校验中的 .busy 状态（可用 ::after 转圈）
#toast：顶部居中小黑条，[hidden] 时 display:none；.toast-show 类做淡入淡出
#confirm：全屏遮罩 + .confirm-card（居中卡片）/ .confirm-text / .confirm-actions
#scroll-bottom：悬浮在输入区上方的圆形按钮，[hidden] 时 display:none

## Markdown 渲染产物选择器（由 Java 生成 HTML，JS 直接 innerHTML 插入）
.md-p, .md-h1, .md-h2, .md-h3, .md-h4, .md-h5, .md-h6, .md-ul, .md-ol, .md-li, .md-quote, .md-hr,
.md-pre（代码块外层）, .md-code-lang（代码块语言标签条）, .md-code（<pre> 内的 <code>）,
.md-inline-code（行内代码 <code>）, .md-link（<a>）, .md-table-wrap, .md-table（<table>）,
.md-strong（<strong>）, .md-em（<em>）, .md-del（<del>）
要求：代码块横向滚动不撑破容器；表格横向滚动；链接可换行；长英文/URL 用 overflow-wrap:anywhere 防止溢出；中文正文 16px/28px，行间距舒适；段落间距 12px；列表缩进 22px；blockquote 左侧 3px 主色灰条。

## 消息气泡规范
- 用户消息：右对齐，主色浅底（浅色 #EDF3FE，深色 rgb(44,44,46)），圆角 16px、右上角 4px，最大宽度 86%。
- 助手消息：左对齐、无气泡背景（纯文本流式排版），占满宽度但左右留 16px，最大宽度 100%。
- 消息之间垂直间距 18px。

## 动效
- 只使用 transform/opacity 过渡；淡入 0.18s；抽屉 0.25s；不要长时动画。
- 尊重 prefers-reduced-motion：关闭非必要动效。

## 验收
- 写完 CSS 后在 360x740 与 412x915 两种尺寸下自查（可用注释形式写下自查结论在文件末尾，不要写进 HTML）。
- 不要在 CSS 里引用任何网络资源（图片、字体、@import）。
- 文件顶部写一段中文注释说明主题变量组织结构。
