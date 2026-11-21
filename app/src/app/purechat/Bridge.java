package app.purechat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;

/**
 * 前端与原生之间的桥接对象，挂载为页面里的 {@code window.PureChatNative}。
 *
 * <p>存储类调用同步返回；网络类调用异步执行，通过页面全局函数 {@code __pcEvent}（流式事件）、
 * {@code __pcCallback}（一次性结果）回传。所有回调都切回 UI 线程执行。
 */
public final class Bridge {

    /** 执行网络请求的线程池：同一时刻只允许一个流式回复占用连接。 */
    private final ExecutorService network = Executors.newCachedThreadPool();

    /** 执行回调的 UI 线程即 WebView 所属 Activity。 */
    private final Activity activity;

    private final WebView webView;

    private final Store store;

    private final ChatClient chatClient;

    /** 语音输入：录音器（同一时刻只有一次录音）。 */
    private final AsrRecorder asrRecorder = new AsrRecorder();

    /** 语音输入：最近一次 asrStart 传入的接口地址。 */
    private volatile String asrOrigin = "";

    /** 语音输入：最近一次 asrStart 传入的 API Key。 */
    private volatile String asrApiKey = "";

    /** 语音输入：等待录音权限授予后自动重试的 asrStart。 */
    private Runnable pendingAsr;

    /** 语音输入：等待权限的 asrStart 的请求 id，用于回传失败提示。 */
    private volatile String pendingAsrId = "";

    /** 朗读回答：TTS 客户端（同一时刻只允许一次合成）。 */
    private final TtsClient ttsClient = new TtsClient();

    /** 朗读回答：播放器（延迟创建，避免构造器里碰 MediaPlayer）。 */
    private TtsPlayer ttsPlayer;

    /** 朗读回答：本次朗读的回调 id，用于回传播放状态。 */
    private volatile String ttsCallbackId = "";

    Bridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.store = new Store(activity);
        this.chatClient = new ChatClient();
    }

    /** 释放网络资源，Activity 销毁时调用。 */
    void shutdown() {
        chatClient.abortAll();
        asrRecorder.release();
        ttsClient.cancel();
        if (ttsPlayer != null) {
            ttsPlayer.release();
            ttsPlayer = null;
        }
        network.shutdownNow();
    }


    /** 应用退出前取消所有在途请求。 */
    @JavascriptInterface
    public void abortAll() {
        chatClient.abortAll();
    }

    // ---------------- 存储 ----------------

    /** 读取本地键值。 */
    @JavascriptInterface
    public String storeGet(String key) {
        return store.get(key);
    }

    /** 写入本地键值。 */
    @JavascriptInterface
    public void storeSet(String key, String value) {
        store.set(key, value);
    }

    /** 清空本地数据。 */
    @JavascriptInterface
    public void storeClear() {
        store.clear();
    }

    // ---------------- 渲染 ----------------

    /** 把 Markdown 渲染成 HTML 片段，供页面直接插入。 */
    @JavascriptInterface
    public String renderMarkdown(String markdown) {
        return MdBridge.render(markdown);
    }

    // ---------------- 设备能力 ----------------

    /** 设备信息与初始主题（JSON）。 */
    @JavascriptInterface
    public String deviceInfo() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"sdk\":").append(Build.VERSION.SDK_INT);
        sb.append(",\"model\":").append(quote(Build.MODEL));
        sb.append(",\"dark\":").append(isSystemDark());
        sb.append('}');
        return sb.toString();
    }

    /** 跟随界面主题切换系统栏图标明暗。 */
    @JavascriptInterface
    public void setDarkChrome(final boolean dark) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).setDarkChrome(dark);
                }
            }
        });
    }

    /** 轻触反馈。 */
    @JavascriptInterface
    public void haptic() {
        Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(8, 60));
        } else {
            vibrator.vibrate(8);
        }
    }

    /** 写入系统剪贴板。 */
    @JavascriptInterface
    public void copyText(final String text) {
        if (text == null) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ClipboardManager manager =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (manager != null) {
                    manager.setPrimaryClip(ClipData.newPlainText("PureChat", text));
                }
            }
        });
    }

    /**
     * 读取系统剪贴板里的文本。
     *
     * <p>设置页的密钥输入框不使用 {@code type=password}（安全键盘会禁用粘贴），
     * 改由页面内按钮调用这里直接取剪贴板内容填入，绕过输入法限制。</p>
     *
     * @return 剪贴板文本；没有内容或类型不支持时返回空串
     */
    @JavascriptInterface
    public String readClipboard() {
        final String[] holder = new String[] {""};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager manager =
                            (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager != null && manager.hasPrimaryClip()) {
                        ClipData clip = manager.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence text = clip.getItemAt(0).coerceToText(activity);
                            if (text != null) {
                                holder[0] = text.toString();
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    // 某些系统在无焦点时会拒绝读取剪贴板，按空处理。
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    // ---------------- 网络 ----------------

    /**
     * 发起一次流式对话。payload 为 JSON，字段见 web/src/app.js 的 buildStreamPayload。
     * 事件通过 {@code __pcEvent(requestId, json)} 逐条回传，结束时必有一条 type=end 或 type=error。
     */
    @JavascriptInterface
    public void chatStart(String payloadJson) {
        final RequestSpec spec;
        try {
            spec = RequestSpec.fromJson(payloadJson);
        } catch (JsonException e) {
            return;
        } catch (IllegalArgumentException e) {
            emit(specIdOf(payloadJson), "{\"type\":\"error\",\"message\":"
                    + quote(e.getMessage() == null ? "请求参数无效" : e.getMessage()) + "}");
            return;
        }
        network.execute(new Runnable() {
            @Override
            public void run() {
                chatClient.stream(spec, new ChatClient.Listener() {
                    @Override
                    public void onEvent(String json) {
                        emit(spec.id, json);
                    }
                });
            }
        });
    }

    /** 中止指定请求；不存在时忽略。 */
    @JavascriptInterface
    public void chatAbort(String requestId) {
        chatClient.abort(requestId);
    }

    /** 用 GET /models 校验连接，结果通过 {@code __pcCallback(callbackId, ok, json)} 回传。 */
    @JavascriptInterface
    public void verify(String payloadJson, final String callbackId) {
        final RequestSpec spec;
        try {
            spec = RequestSpec.fromJson(payloadJson);
        } catch (JsonException e) {
            callback(callbackId, false, "{\"message\":\"请求参数无效\"}");
            return;
        }
        network.execute(new Runnable() {
            @Override
            public void run() {
                final ChatClient.VerifyResult result = chatClient.verify(spec);
                callback(callbackId, result.ok, "{\"message\":" + quote(result.message) + "}");
            }
        });
    }

    // ---------------- 内部 ----------------

    private static String specIdOf(String payloadJson) {
        Object node;
        try {
            node = Json.parse(payloadJson);
        } catch (JsonException e) {
            return "";
        }
        return Json.str(node, "id", "");
    }

    private boolean isSystemDark() {
        int mode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 把一条事件投递到页面全局函数。 */
    private void emit(final String requestId, final String json) {
        runJs("window.__pcEvent(" + quote(requestId) + "," + quote(json) + ")");
    }

    /** 把一次性结果投递到页面全局函数。 */
    private void callback(final String callbackId, final boolean ok, final String json) {
        runJs("window.__pcCallback(" + quote(callbackId) + "," + (ok ? "true" : "false") + ","
                + quote(json) + ")");
    }

    private void runJs(final String script) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    /** 生成可安全嵌入 JS 单引号/双引号字符串的 JSON 字面量。 */
    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\u2028':
                    sb.append("\\u2028");
                    break;
                case '\u2029':
                    sb.append("\\u2029");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ---------------- 语音（ASR） ----------------

    /**
     * 开始录音。
     *
     * <p>payload 为 JSON：{@code {id, origin, apiKey}}。若没有录音权限，会先由 MainActivity 申请，
     * 申请完成后自动重新开始录音。pending 录音只保留一次，新的 asrStart 会覆盖旧的。</p>
     *
     * @param payloadJson 前端传入的参数 JSON
     */
    @JavascriptInterface
    public void asrStart(String payloadJson) {
        final String id;
        final String origin;
        final String apiKey;
        try {
            Object node = Json.parse(payloadJson == null ? "" : payloadJson);
            id = Json.str(node, "id", "");
            origin = Json.str(node, "origin", "");
            apiKey = Json.str(node, "apiKey", "");
        } catch (JsonException e) {
            return;
        }
        if (!AsrRecorder.hasPermission(activity)) {
            final String payload = payloadJson;
            pendingAsrId = id;
            synchronized (this) {
                pendingAsr = new Runnable() {
                    @Override
                    public void run() {
                        asrStart(payload);
                    }
                };
            }
            if (!AsrRecorder.requestPermission(activity)) {
                synchronized (this) {
                    pendingAsr = null;
                }
                emitAsrError(id, "未授予录音权限，请在系统设置里允许麦克风权限");
            }
            return;
        }
        synchronized (this) {
            pendingAsr = null;
        }
        boolean started = asrRecorder.start(new AsrRecorder.Listener() {
            @Override
            public void onFinished(byte[] wav, long durationMs, String failure) {
                if (failure != null) {
                    emitAsrError(id, failure);
                }
            }
        });
        if (!started) {
            emitAsrError(id, "录音启动失败，请稍后重试");
            return;
        }
        asrOrigin = origin;
        asrApiKey = apiKey;
    }

    /**
     * 结束录音并异步上传转写。
     *
     * <p>结果通过 {@code window.__pcCallback(requestId, ok, json)} 回传，
     * json 形如 {@code {"text":"...","message":"..."}}。</p>
     *
     * @param requestId 请求 id，同时作为回调 id
     */
    @JavascriptInterface
    public void asrStop(String requestId) {
        final String id = requestId == null ? "" : requestId;
        if (!asrRecorder.isRecording()) {
            callback(id, false, asrJson("", "当前没有正在进行的录音"));
            return;
        }
        final String origin = asrOrigin;
        final String apiKey = asrApiKey;
        final AsrRecorder recorder = asrRecorder;
        network.execute(new Runnable() {
            @Override
            public void run() {
                byte[] wav = recorder.stopAndGetWav();
                if (wav == null || wav.length <= AsrClient.WAV_HEADER_SIZE) {
                    callback(id, false, asrJson("", "没有录到音频数据"));
                    return;
                }
                AsrClient.AsrResult result = AsrClient.transcribe(origin, apiKey, wav);
                callback(id, result.ok, result.toJson());
            }
        });
    }

    /**
     * 录音权限申请完成后由 MainActivity 调用：重新发起等待中的 asrStart。
     *
     * <p>无论用户是否授权都会走到这里：授权则重新开始录音，拒绝则回传失败提示。</p>
     */
    void onAsrPermissionResult(boolean granted) {
        final Runnable pending;
        synchronized (this) {
            pending = pendingAsr;
            pendingAsr = null;
        }
        if (pending == null) {
            return;
        }
        if (!granted) {
            emitAsrError(pendingAsrId, "未授予录音权限，请在系统设置里允许麦克风权限");
            return;
        }
        activity.runOnUiThread(pending);
    }

    /** 把录音/转写错误通过一次性回调送出（失败），页面据此提示。 */
    private void emitAsrError(final String requestId, final String message) {
        callback(requestId, false, asrJson("", message));
    }

    /** 组装 ASR 结果 JSON：{"text":"...","message":"..."}。 */
    private static String asrJson(String text, String message) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("text", text == null ? "" : text);
        node.put("message", message == null ? "" : message);
        return Json.stringify(node);
    }

    // ===== 文件输入 =====

    /** 文件选择器：同一时刻只允许一次选择；延迟创建，避免动到构造器。 */
    private FilePicker filePicker;

    /** 取文件选择器实例（首次调用时创建）。 */
    private synchronized FilePicker filePicker() {
        if (filePicker == null) {
            filePicker = new FilePicker(activity);
        }
        return filePicker;
    }

    /**
     * 拉起系统文件选择器（支持多选）。
     *
     * <p>结果通过 {@code window.__pcCallback(callbackId, ok, json)} 回传，json 形如
     * {"ok":true,"files":[{"path":"/data/...","name":"a.png","mime":"image/png","size":8215,
     * "kind":"image","blockType":"image","purpose":"user_data"}],"rejected":[]}。
     * 选中的文件已复制到应用私有缓存目录，可直接交给 {@link #uploadFile(String)}。</p>
     *
     * @param callbackId 结果回传用的回调 id
     */
    @JavascriptInterface
    public void pickFiles(final String callbackId) {
        final String id = callbackId == null ? "" : callbackId;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean started = filePicker().start(id, new FilePicker.Listener() {
                    @Override
                    public void onPicked(String callbackId, FilePicker.Result result) {
                        callback(callbackId, result.ok, result.toJson());
                    }
                });
                if (!started) {
                    callback(id, false, "{\"ok\":false,\"canceled\":false,"
                            + "\"message\":\"无法打开文件选择器：可能已有一次选择正在进行，"
                            + "或系统没有可用的文件管理器\"}");
                }
            }
        });
    }

    /**
     * 把本地文件上传到 Files API，拿到可在对话里引用的 file_id。
     *
     * <p>payload 为 JSON：{@code {callbackId, origin, apiKey, path, name, mime, purpose}}，
     * 其中 {@code name}、{@code mime}、{@code purpose} 可省略；{@code purpose} 为 {@code auto}
     * 时由原生按文件类型决定，与类型冲突的取值会被自动纠正。结果通过
     * {@code __pcCallback} 回传，成功时 json 含
     * {@code fileId/mimeType/filename/sizeBytes/purpose/blockType/contentBlock}。</p>
     *
     * @param payloadJson 前端传入的参数 JSON
     */
    @JavascriptInterface
    public void uploadFile(String payloadJson) {
        final String callbackId;
        final String origin;
        final String apiKey;
        final String path;
        final String name;
        final String mime;
        final String purpose;
        try {
            Object node = Json.parse(payloadJson == null ? "" : payloadJson);
            callbackId = Json.str(node, "callbackId", Json.str(node, "id", ""));
            origin = Json.str(node, "origin", "");
            apiKey = Json.str(node, "apiKey", "");
            path = Json.str(node, "path", "");
            name = Json.str(node, "name", "");
            mime = Json.str(node, "mime", "");
            purpose = Json.str(node, "purpose", "auto");
        } catch (JsonException e) {
            callback("", false, "{\"ok\":false,\"message\":\"上传参数不是合法 JSON\"}");
            return;
        }
        network.execute(new Runnable() {
            @Override
            public void run() {
                FileUploader.Result result =
                        FileUploader.upload(origin, apiKey, path, name, mime, purpose);
                callback(callbackId, result.ok, result.toJson());
            }
        });
    }

    /**
     * 转发 {@code onActivityResult}，由 MainActivity 调用。
     *
     * @param requestCode 请求码
     * @param resultCode  结果码
     * @param data        返回的 Intent
     * @return 是否消费了本次回调
     */
    boolean onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        return filePicker().onActivityResult(requestCode, resultCode, data);
    }

    // ===== 附件预览 =====

    /**
     * 读取附件缩略图，返回可直接放进 img src 的 data URL。
     *
     * <p>图片是用户自己选的文件，尺寸可能很大，这里先按边长压到 512px 再编码，
     * 避免一次性把几十 MB 的数据塞进 JS 字符串。只允许读应用私有目录下的文件。</p>
     *
     * @param path 附件在私有缓存中的绝对路径
     * @return data URL；读取失败或文件不合法时返回空串
     */
    @JavascriptInterface
    public String imageThumb(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        try {
            java.io.File file = new java.io.File(path);
            java.io.File canonical = file.getCanonicalFile();
            String base = activity.getCacheDir().getCanonicalPath();
            // 只允许读缓存目录内的文件，防止越权读取其它路径。
            if (!canonical.getPath().startsWith(base)) {
                return "";
            }
            if (!canonical.exists() || canonical.length() <= 0) {
                return "";
            }
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(canonical.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return "";
            }
            int target = 512;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= target
                    || bounds.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap bitmap =
                    android.graphics.BitmapFactory.decodeFile(canonical.getPath(), options);
            if (bitmap == null) {
                return "";
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, buffer);
            bitmap.recycle();
            String encoded = android.util.Base64.encodeToString(
                    buffer.toByteArray(), android.util.Base64.NO_WRAP);
            return "data:image/jpeg;base64," + encoded;
        } catch (java.io.IOException e) {
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ===== 朗读回答 =====

    /** 取播放器实例（首次调用时创建）。 */
    private synchronized TtsPlayer ttsPlayer() {
        if (ttsPlayer == null) {
            ttsPlayer = new TtsPlayer();
        }
        return ttsPlayer;
    }

    /**
     * 合成并朗读一段文本。
     *
     * <p>payload 为 JSON：{@code {id, origin, apiKey, text, voiceId}}，额外字段
     * {@code callbackId} 用于回传播放状态；不传时用 {@code id}。回传的 json 形如
     * {@code {"event":"start"}}、{@code {"event":"end"}} 或
     * {@code {"event":"error","message":"..."}}。</p>
     *
     * @param payloadJson 前端传入的参数 JSON
     */
    @JavascriptInterface
    public void ttsSpeak(String payloadJson) {
        final String callbackId;
        final String origin;
        final String apiKey;
        final String text;
        final String voiceId;
        try {
            Object node = Json.parse(payloadJson == null ? "" : payloadJson);
            callbackId = Json.str(node, "callbackId", Json.str(node, "id", ""));
            origin = Json.str(node, "origin", "");
            apiKey = Json.str(node, "apiKey", "");
            text = Json.str(node, "text", "");
            voiceId = Json.str(node, "voiceId", TtsClient.VOICE_STELLA);
        } catch (JsonException e) {
            return;
        }
        if (text.trim().isEmpty()) {
            callback(callbackId, false, ttsJson("error", "没有可朗读的内容"));
            return;
        }
        if (apiKey.isEmpty()) {
            callback(callbackId, false, ttsJson("error", "请先在设置中填写 API Key"));
            return;
        }
        ttsCallbackId = callbackId;
        ttsPlayer().stop();
        network.execute(new Runnable() {
            @Override
            public void run() {
                final TtsClient.Result result = ttsClient.synthesize(origin, apiKey, text, voiceId);
                if (callbackId.equals(ttsCallbackId) == false) {
                    // 期间用户已开始新的朗读，丢弃本次结果。
                    return;
                }
                if (!result.ok) {
                    callback(callbackId, false, ttsJson("error", result.error));
                    return;
                }
                callback(callbackId, true, ttsJson("start", null));
                final String id = callbackId;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ttsPlayer().play(result.bytes, new TtsPlayer.Listener() {
                            @Override
                            public void onEnded() {
                                callback(id, true, ttsJson("end", null));
                            }

                            @Override
                            public void onError(String message) {
                                callback(id, false, ttsJson("error", message));
                            }
                        });
                    }
                });
            }
        });
    }

    /** 停止朗读。 */
    @JavascriptInterface
    public void ttsStop() {
        ttsCallbackId = "";
        ttsClient.cancel();
        if (ttsPlayer != null) {
            ttsPlayer.stop();
        }
    }

    /** 组装朗读状态 JSON：{"event":"...","message":"..."}。 */
    private static String ttsJson(String event, String message) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("event", event);
        if (message != null) {
            node.put("message", message);
        }
        return Json.stringify(node);
    }
}
