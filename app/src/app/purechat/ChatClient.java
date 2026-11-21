package app.purechat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;
import app.purechat.netcore.SseDecoder;
import app.purechat.netcore.SseEvent;
import app.purechat.netcore.StreamEvent;
import app.purechat.netcore.StreamEventParser;

/**
 * 通过 HTTPS 直连模型服务的 Anthropic 兼容接口，并把响应流翻译成前端可消费的紧凑事件。
 *
 * <p>放在原生侧的原因：页面以 file:// 加载，浏览器不允许其直接发起跨域请求；
 * 原生 {@link HttpURLConnection} 没有这个限制，也能精确控制流式读取与中断。
 */
final class ChatClient {

    /** 单次回复的读取超时（毫秒）；思考型模型可能长时间不输出。 */
    private static final int READ_TIMEOUT_MS = 180000;

    /** 建立连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 20000;

    /** 统一 UTF-8。 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 在途连接，键为前端请求 id，用于中止。 */
    private final Map<String, HttpURLConnection> active = new ConcurrentHashMap<String, HttpURLConnection>();

    /** 事件回调。 */
    interface Listener {
        /** 收到一条事件 JSON；实现方负责切线程。 */
        void onEvent(String json);
    }

    /** 校验结果。 */
    static final class VerifyResult {
        final boolean ok;
        final String message;

        VerifyResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /** 中止全部在途请求。 */
    void abortAll() {
        for (Map.Entry<String, HttpURLConnection> entry : active.entrySet()) {
            HttpURLConnection connection = entry.getValue();
            if (connection != null) {
                connection.disconnect();
            }
        }
        active.clear();
    }

    /** 中止指定请求。 */
    void abort(String requestId) {
        if (requestId == null) {
            return;
        }
        HttpURLConnection connection = active.remove(requestId);
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** 用 GET /models 校验地址与密钥，不产生推理费用。 */
    VerifyResult verify(RequestSpec spec) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(spec.messagesOrigin() + "/models");
            connection = (HttpURLConnection) url.openConnection();
            prepare(connection);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + spec.apiKey);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(CONNECT_TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return new VerifyResult(true, "连接正常");
            }
            String body = readAll(connection.getErrorStream());
            String message = errorMessage(body);
            return new VerifyResult(false, message == null ? "HTTP " + code : message);
        } catch (IOException e) {
            return new VerifyResult(false, describe(e));
        } catch (RuntimeException e) {
            return new VerifyResult(false, describe(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 发起流式对话：连接建立后先发 open 事件，随后逐条翻译协议事件，
     * 无论成功失败都以保证一条 end 事件收尾。
     */
    void stream(RequestSpec spec, Listener listener) {
        HttpURLConnection connection = null;
        boolean finished = false;
        try {
            URL url = new URL(spec.requestUrl());
            connection = (HttpURLConnection) url.openConnection();
            prepare(connection);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("x-api-key", spec.apiKey);
            if (spec.filesBeta) {
                // 请求体引用了 Files API 的 file_id：服务端要求带此头，否则返回 400。
                connection.setRequestProperty(FileUploader.HEADER_BETA, FileUploader.FILES_BETA);
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setChunkedStreamingMode(0);
            active.put(spec.id, connection);

            byte[] payload = spec.body.getBytes(UTF8);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                closeQuietly(out);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readAll(connection.getErrorStream());
                String message = errorMessage(body);
                listener.onEvent(error("HTTP " + code + (message == null ? "" : " · " + message)));
                finished = true;
                listener.onEvent("{\"t\":\"end\"}");
                return;
            }

            listener.onEvent("{\"t\":\"open\"}");
            String contentType = connection.getContentType();
            // beta 前缀续写走 Chat Completions 协议，事件结构与 Messages 不同，单独翻译。
            if (spec.continueMode) {
                boolean done = pumpChatCompletions(
                        connection.getInputStream(), listener);
                if (!done) {
                    listener.onEvent("{\"t\":\"cut\"}");
                }
                listener.onEvent("{\"t\":\"end\"}");
                return;
            }
            InputStream in = connection.getInputStream();
            if (contentType != null && !contentType.contains("event-stream")) {
                // 非 SSE 响应说明地址或协议不对，直接把内容作为错误提示。
                String body = readAll(in);
                String message = errorMessage(body);
                listener.onEvent(error(message == null ? "响应不是事件流" : message));
                finished = true;
                listener.onEvent("{\"t\":\"end\"}");
                return;
            }
            finished = pump(in, listener);
            if (!finished) {
                // 没有收到 message_stop 就断流：保持空提示，不猜测原因。
                listener.onEvent("{\"t\":\"cut\"}");
            }
            listener.onEvent("{\"t\":\"end\"}");
        } catch (IOException e) {
            listener.onEvent(error(describe(e)));
            listener.onEvent("{\"t\":\"end\"}");
        } catch (RuntimeException e) {
            listener.onEvent(error(describe(e)));
            listener.onEvent("{\"t\":\"end\"}");
        } finally {
            active.remove(spec.id);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 读取并翻译事件流。
     *
     * @return 是否收到 message_stop（正常结束）
     */
    private boolean pump(InputStream in, Listener listener) throws IOException {
        SseDecoder decoder = new SseDecoder();
        List<SseEvent> events = new ArrayList<SseEvent>();
        Reader reader = new InputStreamReader(in, UTF8);
        char[] buffer = new char[4096];
        boolean stopped = false;
        try {
            for (;;) {
                int read = reader.read(buffer, 0, buffer.length);
                if (read < 0) {
                    break;
                }
                events.clear();
                decoder.push(new String(buffer, 0, read), events);
                if (translate(events, listener)) {
                    stopped = true;
                    break;
                }
            }
            if (!stopped) {
                events.clear();
                decoder.end(events);
                stopped = translate(events, listener);
            }
        } catch (IOException e) {
            if (stopped) {
                return true;
            }
            throw e;
        } finally {
            closeQuietly(reader);
        }
        return stopped;
    }

    /**
     * 解析 Chat Completions 协议的 SSE 流（beta 前缀续写用）。
     *
     * <p>每帧的 data 是一个完整的 chunk：{@code choices[0].delta.content} 是文本增量，
     * {@code choices[0].finish_reason} 非空表示结束，流以 {@code data: [DONE]} 收尾。
     * 这里把它翻译成与 Messages 路径一致的 {@code d} / {@code md} 事件，前端无需区分。</p>
     *
     * @param in       响应流
     * @param listener 事件回调
     * @return 是否正常结束
     */
    private boolean pumpChatCompletions(InputStream in, Listener listener) throws IOException {
        SseDecoder decoder = new SseDecoder();
        List<SseEvent> events = new ArrayList<SseEvent>();
        Reader reader = new InputStreamReader(in, UTF8);
        char[] buffer = new char[4096];
        boolean finished = false;
        try {
            for (;;) {
                int read = reader.read(buffer, 0, buffer.length);
                if (read < 0) {
                    break;
                }
                events.clear();
                decoder.push(new String(buffer, 0, read), events);
                for (int i = 0; i < events.size(); i++) {
                    if (translateChatChunk(events.get(i).data, listener)) {
                        finished = true;
                        break;
                    }
                }
                if (finished) {
                    break;
                }
            }
            if (!finished) {
                events.clear();
                decoder.end(events);
                for (int i = 0; i < events.size(); i++) {
                    if (translateChatChunk(events.get(i).data, listener)) {
                        finished = true;
                        break;
                    }
                }
            }
        } finally {
            closeQuietly(reader);
        }
        return finished;
    }

    /** 翻译一个 Chat Completions chunk；返回是否为结束帧。 */
    private boolean translateChatChunk(String data, Listener listener) {
        if (data == null) {
            return false;
        }
        String payload = data.trim();
        if (payload.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }
        Object node;
        try {
            node = Json.parse(payload);
        } catch (JsonException e) {
            return false;
        }
        Object error = Json.get(node, "error");
        if (error != null) {
            listener.onEvent(error(Json.str(error, "message", "请求失败")));
            return false;
        }
        List<Object> choices = Json.arr(Json.get(node, "choices"));
        if (choices != null && !choices.isEmpty()) {
            Object first = choices.get(0);
            Object delta = Json.get(first, "delta");
            String content = Json.str(delta, "content", "");
            if (content != null && !content.isEmpty()) {
                listener.onEvent("{\"t\":\"d\",\"i\":0,\"dt\":\"text_delta\",\"x\":"
                        + Json.stringify(content) + ",\"s\":\"\"}");
            }
            String finish = Json.str(first, "finish_reason", null);
            if (finish != null && !finish.isEmpty()) {
                listener.onEvent("{\"t\":\"md\",\"stop\":" + Json.stringify(finish)
                        + ",\"in\":0,\"out\":0}");
            }
        }
        return false;
    }

    /** 把一批 SSE 事件翻译成前端事件；返回是否遇到 message_stop。 */
    private boolean translate(List<SseEvent> events, Listener listener) {
        for (int i = 0; i < events.size(); i++) {
            SseEvent raw = events.get(i);
            StreamEvent event = StreamEventParser.parse(raw.event, raw.data);
            if (event == null) {
                continue;
            }
            switch (event.type) {
                case StreamEvent.MESSAGE_START:
                    listener.onEvent("{\"t\":\"start\",\"id\":" + Json.stringify(event.messageId)
                            + ",\"model\":" + Json.stringify(event.model) + "}");
                    break;
                case StreamEvent.CONTENT_BLOCK_START: {
                    StringBuilder sb = new StringBuilder(160);
                    sb.append("{\"t\":\"bs\",\"i\":").append(event.index)
                            .append(",\"bt\":")
                            .append(Json.stringify(event.blockType == null ? "" : event.blockType))
                            .append(",\"x\":").append(Json.stringify(firstNonNull(event.text, "")));
                    // 服务端工具块（搜索/阅读链接）带结构化数据，原样透传给前端渲染。
                    if (event.blockJson != null && !event.blockJson.isEmpty()) {
                        sb.append(",\"b\":").append(Json.stringify(event.blockJson));
                    }
                    sb.append('}');
                    listener.onEvent(sb.toString());
                    break;
                }
                case StreamEvent.CONTENT_BLOCK_DELTA:
                    listener.onEvent("{\"t\":\"d\",\"i\":" + event.index + ",\"dt\":"
                            + Json.stringify(event.deltaType == null ? "" : event.deltaType)
                            + ",\"x\":" + Json.stringify(firstNonNull(event.text, ""))
                            + ",\"s\":" + Json.stringify(firstNonNull(event.signature, "")) + "}");
                    break;
                // 引用增量：text 字段里是 citation 的 JSON（url/title/cited_text）
                // 前端把引用按 url 归并后渲染成来源列表，见 app.js 的 addCitation。
                case StreamEvent.CONTENT_BLOCK_STOP:
                    listener.onEvent("{\"t\":\"be\",\"i\":" + event.index + "}");
                    break;
                case StreamEvent.MESSAGE_DELTA: {
                    StringBuilder sb = new StringBuilder(160);
                    sb.append("{\"t\":\"md\",\"stop\":")
                            .append(Json.stringify(firstNonNull(event.stopReason, "")))
                            .append(",\"in\":").append(event.inputTokens)
                            .append(",\"out\":").append(event.outputTokens);
                    if (event.title != null && !event.title.isEmpty()) {
                        sb.append(",\"title\":").append(Json.stringify(event.title));
                    }
                    sb.append('}');
                    listener.onEvent(sb.toString());
                    break;
                }
                case StreamEvent.MESSAGE_STOP:
                    listener.onEvent("{\"t\":\"stop\"}");
                    return true;
                case StreamEvent.ERROR:
                    listener.onEvent(error(event.errorMessage == null ? "服务端返回错误" : event.errorMessage));
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    // ---------------- 工具方法 ----------------

    private static void prepare(HttpURLConnection connection) {
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(https.getSSLSocketFactory());
        }
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
    }

    /** 从错误响应体里取 {@code error.message}。 */
    private String errorMessage(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            Object node = Json.parse(body);
            Object error = Json.get(node, "error");
            String message = Json.str(error, "message", null);
            if (message != null) {
                return message;
            }
            return Json.str(node, "message", null);
        } catch (JsonException e) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private static String describe(Throwable error) {
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (error instanceof java.net.UnknownHostException) {
            return "无法解析服务器域名，请检查网络与接口地址";
        }
        if (error instanceof java.net.SocketTimeoutException) {
            return "连接超时，请稍后重试";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "安全连接失败：" + (message == null ? name : message);
        }
        if (error instanceof java.net.ConnectException) {
            return "无法连接服务器，请检查网络";
        }
        return message == null || message.isEmpty() ? name : message;
    }

    private static String firstNonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String error(String message) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("t", "err");
        node.put("msg", message);
        try {
            return Json.stringify(node);
        } catch (JsonException e) {
            return "{\"t\":\"err\",\"msg\":\"未知错误\"}";
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 65536) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), UTF8);
        } catch (IOException e) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理。
        }
    }
}
