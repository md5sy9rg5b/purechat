/*
 * 语音输入（ASR）网络客户端：把 PCM 封装成 WAV，按 multipart/form-data 上传到
 * {origin}/v1/tools/asr，并解析识别结果。
 *
 * 设计约束：
 * - 纯 Java：只依赖 java.* 与 app.purechat.netcore.Json，不含任何 android.*，
 *   因此可以被 build.ps1 -SelfTestOnly 直接编译进纯 JVM 自测。
 * - WAV 头封装（buildWavHeader / buildWav）与 multipart 拼装（buildMultipartBody）
 *   都是无副作用的静态纯函数，便于做字节级断言。
 *
 * 接口约定（https://api-docs.deepseek.com/zh-cn/api/asr）：
 * POST /v1/tools/asr，multipart/form-data，字段名 file；
 * 音频为 16 位 PCM WAV 或 Ogg Opus，最大 8 MiB、最长 120 秒；
 * 成功响应 {"request_id":"...","text":"...","duration_ms":3200}；
 * 错误码 400 格式不对/缺 file、408 上传超时、413 过大、429 限流、503 繁忙、504 超时。
 */
package app.purechat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;

/**
 * 语音识别（ASR）客户端：组装 WAV、组装 multipart 请求体、发请求并解析结果。
 *
 * <p>本类不持有状态，所有方法都是线程安全的静态方法；录音由 {@link AsrRecorder} 负责。</p>
 */
public final class AsrClient {

    /** 录音采样率：16 kHz，ASR 接口支持且体积最小。 */
    public static final int SAMPLE_RATE = 16000;

    /** 声道数：单声道。 */
    public static final int CHANNELS = 1;

    /** 位深：16 位。 */
    public static final int BITS_PER_SAMPLE = 16;

    /** 音频体积上限：8 MiB（接口硬限制）。 */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    /** 音频时长上限：120 秒（接口硬限制）。 */
    public static final int MAX_DURATION_MS = 120000;

    /** WAV 头长度：RIFF(12) + fmt(24) + data(8)。 */
    public static final int WAV_HEADER_SIZE = 44;

    /** 响应体最多读取的字符数，避免异常响应撑爆内存。 */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /** 统一 UTF-8。 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** multipart 字段名，接口要求固定为 file。 */
    static final String FIELD_NAME = "file";

    /** multipart 文件名，服务端按内容判断格式，名字只用于展示。 */
    static final String FILE_NAME = "asr.wav";

    /** WAV 的 MIME 类型。 */
    static final String WAV_MIME = "audio/wav";

    /** 建立连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 20000;

    /** 读取响应超时（毫秒）。 */
    private static final int READ_TIMEOUT_MS = 60000;

    /** boundary 随机源。 */
    private static final Random RANDOM = new Random(System.currentTimeMillis() * 31L);

    /**
     * 工具类构造器：私有，禁止实例化。
     */
    private AsrClient() {
    }

    // ------------------------------------------------------------------
    // WAV 封装
    // ------------------------------------------------------------------

    /**
     * 封装 44 字节标准 RIFF/WAVE 头。
     *
     * <p>布局：{@code RIFF|<36+dataSize>|WAVE|fmt |16|1|channels|sampleRate|byteRate|blockAlign|bits|data|<dataSize>}，
     * 所有整数字段均为小端序。</p>
     *
     * @param dataSize      PCM 数据字节数
     * @param sampleRate    采样率，必须为正
     * @param channels      声道数，1 或 2
     * @param bitsPerSample 位深，8/16/24/32
     * @return 长度恒为 44 的头部字节
     * @throws IllegalArgumentException 参数越界时抛出
     */
    public static byte[] buildWavHeader(int dataSize, int sampleRate, int channels, int bitsPerSample) {
        if (dataSize < 0) {
            throw new IllegalArgumentException("PCM 数据长度不能为负：" + dataSize);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("采样率必须为正：" + sampleRate);
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("声道数只能是 1 或 2：" + channels);
        }
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
            throw new IllegalArgumentException("位深只能是 8/16/24/32：" + bitsPerSample);
        }
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        byte[] header = new byte[WAV_HEADER_SIZE];
        int offset = 0;
        offset = writeAscii(header, offset, "RIFF");
        offset = writeIntLE(header, offset, 36 + dataSize);
        offset = writeAscii(header, offset, "WAVE");
        offset = writeAscii(header, offset, "fmt ");
        offset = writeIntLE(header, offset, 16);
        offset = writeShortLE(header, offset, 1);
        offset = writeShortLE(header, offset, channels);
        offset = writeIntLE(header, offset, sampleRate);
        offset = writeIntLE(header, offset, byteRate);
        offset = writeShortLE(header, offset, blockAlign);
        offset = writeShortLE(header, offset, bitsPerSample);
        offset = writeAscii(header, offset, "data");
        writeIntLE(header, offset, dataSize);
        return header;
    }

    /**
     * 把内存里的 PCM 数据封装成完整的 WAV（44 字节头 + 数据）。
     *
     * @param pcm           PCM 数据，不能为 null
     * @param sampleRate    采样率
     * @param channels      声道数
     * @param bitsPerSample 位深
     * @return WAV 字节，长度为 {@code 44 + pcm.length}
     * @throws IllegalArgumentException 参数非法时抛出
     */
    public static byte[] buildWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        if (pcm == null) {
            throw new IllegalArgumentException("PCM 数据为 null");
        }
        byte[] header = buildWavHeader(pcm.length, sampleRate, channels, bitsPerSample);
        byte[] wav = new byte[WAV_HEADER_SIZE + pcm.length];
        System.arraycopy(header, 0, wav, 0, WAV_HEADER_SIZE);
        System.arraycopy(pcm, 0, wav, WAV_HEADER_SIZE, pcm.length);
        return wav;
    }

    /**
     * 生成一个合法的 multipart boundary：只含 ASCII 安全字符，长度不超过 70。
     *
     * @return 形如 {@code ----PureChatFormBoundary1a2b3c4d} 的 boundary
     */
    public static String newBoundary() {
        long a;
        long b;
        synchronized (RANDOM) {
            a = RANDOM.nextLong();
            b = RANDOM.nextLong();
        }
        StringBuilder sb = new StringBuilder(48);
        sb.append("----PureChatFormBoundary");
        sb.append(Long.toHexString(a));
        sb.append(Long.toHexString(b));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // multipart/form-data 拼装
    // ------------------------------------------------------------------

    /**
     * 拼装单文件 multipart/form-data 请求体。
     *
     * <p>字节布局（每行都以 CRLF 结尾）：</p>
     * <pre>
     * --boundary CRLF
     * Content-Disposition: form-data; name="file"; filename="asr.wav" CRLF
     * Content-Type: audio/wav CRLF
     * CRLF
     * &lt;文件字节&gt; CRLF
     * --boundary-- CRLF
     * </pre>
     *
     * @param boundary    boundary，不能为空
     * @param fieldName   字段名，接口要求 file
     * @param fileName    文件名
     * @param contentType 文件 MIME 类型
     * @param fileBytes   文件内容，null 视为空文件
     * @return 完整请求体字节；Content-Length 应等于该数组长度
     * @throws IllegalArgumentException boundary 或字段名为空时抛出
     */
    public static byte[] buildMultipartBody(String boundary, String fieldName, String fileName,
            String contentType, byte[] fileBytes) {
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("multipart boundary 不能为空");
        }
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("multipart 字段名不能为空");
        }
        byte[] data = fileBytes == null ? new byte[0] : fileBytes;
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + (fileName == null ? FILE_NAME : fileName) + "\"\r\n"
                + "Content-Type: " + (contentType == null ? WAV_MIME : contentType) + "\r\n"
                + "\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(UTF8);
        byte[] tailBytes = tail.getBytes(UTF8);
        byte[] body = new byte[headBytes.length + data.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(data, 0, body, headBytes.length, data.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + data.length, tailBytes.length);
        return body;
    }

    /**
     * 拼装默认形式的 WAV multipart 请求体（字段名 file、文件名 asr.wav、类型 audio/wav）。
     *
     * @param boundary boundary
     * @param fileBytes WAV 字节
     * @return 完整请求体字节
     */
    public static byte[] buildWavMultipartBody(String boundary, byte[] fileBytes) {
        return buildMultipartBody(boundary, FIELD_NAME, FILE_NAME, WAV_MIME, fileBytes);
    }

    // ------------------------------------------------------------------
    // 地址
    // ------------------------------------------------------------------

    /**
     * 规整接口根地址：去空白、去掉末尾斜杠、强制 https。
     *
     * @param raw 用户填写的地址，可为 null
     * @return 规整后的地址；输入为空时返回空串
     */
    public static String normalizeOrigin(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("http://")) {
            value = "https://" + value.substring("http://".length());
        }
        if (!value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 语音识别接口地址。
     *
     * @param origin 接口根地址，可为任意写法
     * @return {@code {origin}/v1/tools/asr}；origin 为空时返回空串
     */
    public static String asrUrl(String origin) {
        String base = normalizeOrigin(origin);
        if (base.isEmpty()) {
            return "";
        }
        return base + "/v1/tools/asr";
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /**
     * 把 WAV 音频上传到语音识别接口。
     *
     * <p>本方法会阻塞，调用方必须放在工作线程里执行。</p>
     *
     * @param origin     接口根地址，例如 https://api.deepseek.com
     * @param apiKey     API Key，作为 Authorization: Bearer 发送
     * @param audioBytes WAV（或 Ogg Opus）字节
     * @return 识别结果，永不为 null
     */
    public static AsrResult transcribe(String origin, String apiKey, byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            return new AsrResult(false, "", "录音数据为空", 0, 0, "");
        }
        if (audioBytes.length > MAX_BYTES) {
            return new AsrResult(false, "", "音频超过 8 MiB 上限（" + audioBytes.length + " 字节）", 0, 0, "");
        }
        String url = asrUrl(origin);
        if (url.isEmpty()) {
            return new AsrResult(false, "", "请先在设置中填写接口地址", 0, 0, "");
        }
        String boundary = newBoundary();
        byte[] body = buildWavMultipartBody(boundary, audioBytes);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int code = connection.getResponseCode();
            String text = readAll(code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            return parseResponse(code, text);
        } catch (IOException e) {
            return new AsrResult(false, "", describe(e), 0, 0, "");
        } catch (RuntimeException e) {
            return new AsrResult(false, "", describe(e), 0, 0, "");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 解析接口响应：2xx 取 text 与 duration_ms，其它状态按错误码翻译成中文提示。
     *
     * @param statusCode HTTP 状态码，0 表示没有拿到响应
     * @param body       响应体，可为 null 或空
     * @return 识别结果
     */
    public static AsrResult parseResponse(int statusCode, String body) {
        String raw = body == null ? "" : body;
        String serverMessage = null;
        String text = null;
        long durationMs = 0;
        try {
            Object node = raw.isEmpty() ? null : Json.parse(raw);
            text = Json.str(node, "text", null);
            durationMs = (long) Json.num(node, "duration_ms", 0);
            Object error = Json.get(node, "error");
            serverMessage = Json.str(error, "message", null);
            if (serverMessage == null) {
                serverMessage = Json.str(node, "message", null);
            }
        } catch (JsonException e) {
            // 响应不是 JSON：错误时把原文作为提示，成功时视为格式异常。
            serverMessage = null;
        }
        if (statusCode >= 200 && statusCode < 300) {
            if (text == null) {
                String message = serverMessage != null ? serverMessage : "响应缺少 text 字段";
                return new AsrResult(false, "", "HTTP " + statusCode + " · " + message, statusCode, durationMs, raw);
            }
            return new AsrResult(true, text, "识别成功", statusCode, durationMs, raw);
        }
        String combined = describeStatus(statusCode, serverMessage);
        if (statusCode == 0) {
            combined = serverMessage == null || serverMessage.isEmpty() ? "请求失败" : serverMessage;
        }
        return new AsrResult(false, "", combined, statusCode, durationMs, raw);
    }

    /**
     * 把错误状态码翻译成中文提示，并保留服务端 message 与原始状态码。
     *
     * @param statusCode    HTTP 状态码
     * @param serverMessage 服务端返回的 message，可为 null
     * @return 形如 {@code HTTP 429 · 请求过于频繁（限流）} 的提示
     */
    public static String describeStatus(int statusCode, String serverMessage) {
        String hint;
        switch (statusCode) {
            case 400:
                hint = "音频格式不正确或缺少 file 字段";
                break;
            case 408:
                hint = "上传超时，请重试";
                break;
            case 413:
                hint = "音频过大，最长 120 秒、最大 8 MiB";
                break;
            case 429:
                hint = "请求过于频繁（限流），请稍后重试";
                break;
            case 503:
                hint = "服务繁忙，请稍后重试";
                break;
            case 504:
                hint = "服务端处理超时，请重试";
                break;
            default:
                hint = "语音识别失败";
                break;
        }
        if (serverMessage != null && !serverMessage.isEmpty()) {
            return "HTTP " + statusCode + " · " + hint + "（" + serverMessage + "）";
        }
        return "HTTP " + statusCode + " · " + hint;
    }

    /**
     * 把网络异常翻译成中文提示。
     *
     * @param error 异常，可为 null
     * @return 中文提示
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "语音识别失败";
        }
        String message = error.getMessage();
        if (error instanceof java.net.UnknownHostException) {
            return "无法解析服务器域名，请检查网络与接口地址";
        }
        if (error instanceof java.net.SocketTimeoutException) {
            return "网络超时，请稍后重试";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "安全连接失败：" + (message == null ? error.getClass().getSimpleName() : message);
        }
        if (error instanceof java.net.ConnectException) {
            return "无法连接服务器，请检查网络";
        }
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------
    // 结果
    // ------------------------------------------------------------------

    /**
     * 语音识别结果。
     *
     * <p>{@code ok} 为 true 时 {@code text} 是识别文本；为 false 时 {@code message} 是可直接展示的错误提示。</p>
     */
    public static final class AsrResult {

        /** 是否成功拿到识别文本。 */
        public final boolean ok;

        /** 识别文本，失败时为空串，永不为 null。 */
        public final String text;

        /** 提示信息：成功时为「识别成功」，失败时为中文错误描述。 */
        public final String message;

        /** HTTP 状态码，0 表示没有拿到响应。 */
        public final int statusCode;

        /** 服务端返回的音频时长（毫秒），未知为 0。 */
        public final long durationMs;

        /** 响应原文，便于日志取证。 */
        public final String rawBody;

        /**
         * 构造结果。
         *
         * @param ok         是否成功
         * @param text       识别文本
         * @param message    提示信息
         * @param statusCode HTTP 状态码
         * @param durationMs 音频时长（毫秒）
         * @param rawBody    响应原文
         */
        public AsrResult(boolean ok, String text, String message, int statusCode, long durationMs,
                String rawBody) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.message = message == null ? "" : message;
            this.statusCode = statusCode;
            this.durationMs = durationMs;
            this.rawBody = rawBody == null ? "" : rawBody;
        }

        /**
         * 转成回传给页面的 JSON：{@code {"text":"...","message":"..."}}。
         *
         * @return JSON 文本
         */
        public String toJson() {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("text", text);
            node.put("message", message);
            node.put("status", Double.valueOf(statusCode));
            node.put("duration_ms", Double.valueOf(durationMs));
            return Json.stringify(node);
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static int writeAscii(byte[] target, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            target[offset + i] = (byte) ascii.charAt(i);
        }
        return offset + ascii.length();
    }

    private static int writeIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
        return offset + 4;
    }

    private static int writeShortLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        return offset + 2;
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
                if (buffer.size() > MAX_BODY_BYTES) {
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
