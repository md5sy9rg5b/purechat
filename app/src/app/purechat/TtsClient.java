/*
 * 模块：语音合成（TTS）网络客户端。
 *
 * 接口：POST {origin}/v1/tools/tts（DeepSeek 整段合成接口）
 *   请求体 JSON：{"text":"...","voice_id":"stella|tide","format":"wav","filter_markdown":true}
 *   响应体直接是音频流（wav 为 audio/wav）。
 *
 * 设计要点：
 * - 只依赖 java.* 与项目自带的 app.purechat.netcore.Json，不引第三方库，也不引用任何 android.*，
 *   因此可以和 netcore 一起参加 build.ps1 -SelfTestOnly 的纯 JVM 自测。
 * - 单次请求最多 10000 个计费字符：超长文本先按句号/换行切段，逐段合成，再把各段 PCM 拼成一个 WAV。
 * - 服务端返回的 WAV 头部把 RIFF/data 长度写成 0xFFFFFFFF 占位值，这里统一按真实长度重写，
 *   否则部分播放器会按 4 GB 的长度去预分配而直接失败。
 */
package app.purechat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;

/**
 * 语音合成接口的同步客户端：把一段文本合成成一个可播放的 WAV 字节数组。
 *
 * <p>调用方应在后台线程调用 {@link #synthesize(String, String, String, String)}，
 * 它内部会串行完成「切段 → 逐段 POST → 拼接音频」的全部工作。</p>
 *
 * <p>本类只保留一个「在途连接」引用，便于界面销毁时用 {@link #cancel()} 断开请求。
 * 所有公开的 static 方法都是纯函数，可以被纯 JVM 自测直接调用。</p>
 */
public final class TtsClient {

    /** 接口路径。 */
    private static final String PATH = "/v1/tools/tts";

    /** 单次请求允许的最大计费字符数（服务端限制）。 */
    public static final int MAX_BILLABLE_CHARS = 10000;

    /** 单次请求体允许的最大字节数（服务端限制 64 KiB）。 */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** 女声（海星），默认音色。 */
    public static final String VOICE_STELLA = "stella";

    /** 男声（暗潮）。 */
    public static final String VOICE_TIDE = "tide";

    /** 单段音频的最大字节数，超过视为异常响应，避免一次性吃掉过多内存。 */
    private static final int MAX_AUDIO_BYTES = 48 * 1024 * 1024;

    /** 建立连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 20000;

    /** 读取超时（毫秒）：30 分钟音频可能合成较慢，这里给足时间。 */
    private static final int READ_TIMEOUT_MS = 180000;

    /** 统一 UTF-8。 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 解析 RIFF 分块标识用的 ASCII。 */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** RIFF/data 长度字段的占位值（服务端流式写出时使用）。 */
    private static final long U32_PLACEHOLDER = 0xFFFFFFFFL;

    /** 当前在途连接，供界面销毁时取消。 */
    private volatile HttpURLConnection current;

    /**
     * 合成结果的不可变快照。
     *
     * <p>成功时 {@link #bytes} 是一个完整的 WAV 文件；失败时 {@link #error} 带 HTTP 状态与响应体摘要。</p>
     */
    public static final class Result {

        /** 是否成功。 */
        public final boolean ok;

        /** 合成好的 WAV 字节；失败时为 null。 */
        public final byte[] bytes;

        /** 失败原因（含 HTTP 状态与响应体摘要）；成功时为 null。 */
        public final String error;

        /** 最后一次 HTTP 状态码；未发出请求时为 0。 */
        public final int httpStatus;

        /** 本次合成拆成了几段请求。 */
        public final int segments;

        /** 本次文本的计费字符数。 */
        public final int billableChars;

        private Result(boolean ok, byte[] bytes, String error, int httpStatus, int segments,
                int billableChars) {
            this.ok = ok;
            this.bytes = bytes;
            this.error = error;
            this.httpStatus = httpStatus;
            this.segments = segments;
            this.billableChars = billableChars;
        }

        /** 构造一个失败结果。 */
        static Result failure(String error, int httpStatus) {
            return new Result(false, null, error, httpStatus, 0, 0);
        }

        /** 构造一个成功结果。 */
        static Result success(byte[] bytes, int httpStatus, int segments, int billableChars) {
            return new Result(true, bytes, null, httpStatus, segments, billableChars);
        }

        /** 音频字节数；失败时为 0。 */
        public int byteCount() {
            return bytes == null ? 0 : bytes.length;
        }

        /** 便于日志输出的一行摘要。 */
        public String describe() {
            if (!ok) {
                return "ok=false status=" + httpStatus + " error=" + error;
            }
            return "ok=true status=" + httpStatus + " bytes=" + byteCount()
                    + " segments=" + segments + " billable=" + billableChars;
        }
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 取消在途请求（例如 Activity 销毁或用户点停止）。不保证服务端已停止计费。 */
    public void cancel() {
        HttpURLConnection connection = current;
        current = null;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // 断开失败无需处理。
            }
        }
    }

    /**
     * 把文本合成成一个完整 WAV。
     *
     * @param origin  接口根地址，例如 https://api.deepseek.com
     * @param apiKey  API Key
     * @param text    要朗读的文本，不能为空
     * @param voiceId 音色，stella（默认）或 tide
     * @return 结果快照，永不为 null
     */
    public Result synthesize(String origin, String apiKey, String text, String voiceId) {
        String base = normalizeOrigin(origin);
        if (base.isEmpty()) {
            return Result.failure("请先在设置中填写接口地址", 0);
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Result.failure("请先在设置中填写 API Key", 0);
        }
        String voice = normalizeVoice(voiceId);
        List<String> segments = splitForSynth(text, MAX_BILLABLE_CHARS);
        if (segments.isEmpty()) {
            return Result.failure("朗读文本为空，没有可合成的内容", 0);
        }
        List<byte[]> wavs = new ArrayList<byte[]>(segments.size());
        int billable = 0;
        int status = 0;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            billable += billableLength(segment);
            Raw raw = postWav(base, apiKey, segment, voice);
            status = raw.status;
            if (!raw.ok) {
                String prefix = segments.size() > 1
                        ? "第 " + (i + 1) + "/" + segments.size() + " 段合成失败："
                        : "";
                return Result.failure(prefix + raw.error, raw.status);
            }
            wavs.add(raw.bytes);
        }
        byte[] merged;
        try {
            merged = normalizeWav(wavs);
        } catch (IllegalArgumentException e) {
            return Result.failure("音频格式异常：" + e.getMessage(), status);
        }
        if (merged.length == 0) {
            return Result.failure("语音合成接口没有返回音频数据", status);
        }
        return Result.success(merged, status, segments.size(), billable);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** 一次 POST 的原始结果。 */
    private static final class Raw {
        final boolean ok;
        final byte[] bytes;
        final String error;
        final int status;

        private Raw(boolean ok, byte[] bytes, String error, int status) {
            this.ok = ok;
            this.bytes = bytes;
            this.error = error;
            this.status = status;
        }

        static Raw success(byte[] bytes, int status) {
            return new Raw(true, bytes, null, status);
        }

        static Raw failure(String error, int status) {
            return new Raw(false, null, error, status);
        }
    }

    /** 组装请求体：text / voice_id / format / filter_markdown。 */
    private static String buildBody(String text, String voice) throws JsonException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("text", text);
        body.put("voice_id", voice);
        body.put("format", "wav");
        body.put("filter_markdown", Boolean.TRUE);
        return Json.stringify(body);
    }

    /** POST 一段文本，拿回 WAV 字节。 */
    private Raw postWav(String base, String apiKey, String text, String voice) {
        String body;
        try {
            body = buildBody(text, voice);
        } catch (JsonException e) {
            return Raw.failure("请求体序列化失败：" + e.getMessage(), 0);
        }
        byte[] payload = body.getBytes(UTF8);
        if (payload.length > MAX_BODY_BYTES) {
            return Raw.failure("单段请求体 " + payload.length + " 字节，超过 64 KiB 上限", 0);
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(base + PATH);
            connection = (HttpURLConnection) url.openConnection();
            prepare(connection);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "audio/wav");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            current = connection;

            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                closeQuietly(out);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readText(connection.getErrorStream());
                return Raw.failure(describeStatus(code, errorBody), code);
            }
            String contentType = connection.getContentType();
            InputStream in = connection.getInputStream();
            byte[] audio;
            try {
                audio = readAllBytes(in, MAX_AUDIO_BYTES);
            } finally {
                closeQuietly(in);
            }
            if (audio.length == 0) {
                return Raw.failure("HTTP " + code + " · 响应体中没有音频数据", code);
            }
            if (contentType != null && contentType.toLowerCase().contains("json")) {
                String message = errorMessage(new String(audio, UTF8));
                return Raw.failure("HTTP " + code + " · "
                        + (message == null ? "响应不是音频" : message), code);
            }
            return Raw.success(audio, code);
        } catch (IOException e) {
            return Raw.failure(describe(e), 0);
        } catch (RuntimeException e) {
            return Raw.failure(describe(e), 0);
        } finally {
            current = null;
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // 断开失败无需处理。
                }
            }
        }
    }

    private static void prepare(HttpURLConnection connection) {
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(https.getSSLSocketFactory());
        }
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
    }

    /** 把状态码翻译成人话，并附上响应体摘要。 */
    private static String describeStatus(int code, String body) {
        String hint;
        switch (code) {
            case 400:
                hint = "文本为空或未通过内容审核";
                break;
            case 401:
                hint = "API Key 无效或已过期";
                break;
            case 402:
                hint = "账户余额不足";
                break;
            case 403:
                hint = "当前账号无权使用语音合成";
                break;
            case 429:
                hint = "并发请求超过上限，请稍后重试";
                break;
            case 503:
                hint = "服务繁忙，请稍后重试";
                break;
            case 504:
                hint = "语音合成超时";
                break;
            default:
                hint = "语音合成失败";
                break;
        }
        String detail = errorMessage(body);
        StringBuilder sb = new StringBuilder(96);
        sb.append("HTTP ").append(code).append(" · ").append(hint);
        if (detail != null && !detail.isEmpty()) {
            sb.append("（").append(detail).append("）");
        } else if (body != null && !body.trim().isEmpty()) {
            String trimmed = body.trim();
            if (trimmed.length() > 200) {
                trimmed = trimmed.substring(0, 200) + "…";
            }
            sb.append("（").append(trimmed).append("）");
        }
        return sb.toString();
    }

    /** 从错误响应体里取 {@code error.message} 或 {@code message}。 */
    private static String errorMessage(String body) {
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
            String code = Json.str(error, "code", null);
            if (code != null) {
                return code;
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
            return "语音合成超时，请稍后重试";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "安全连接失败：" + (message == null ? name : message);
        }
        if (error instanceof java.net.ConnectException) {
            return "无法连接服务器，请检查网络";
        }
        return message == null || message.isEmpty() ? name : message;
    }

    /** 读取全部字节，超过上限直接抛异常。 */
    private static byte[] readAllBytes(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
        byte[] chunk = new byte[16384];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > limit) {
                throw new IOException("音频数据超过 " + limit + " 字节上限");
            }
        }
        return buffer.toByteArray();
    }

    /** 读取错误响应体（最多 64 KiB）。 */
    private static String readText(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            byte[] bytes = readAllBytes(in, MAX_BODY_BYTES);
            return new String(bytes, UTF8);
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

    // ------------------------------------------------------------------
    // 文本切段（纯函数，可被自测覆盖）
    // ------------------------------------------------------------------

    /**
     * 统计文本的计费字符数：汉字算 2，其余字符（含 emoji 等增补平面字符）算 1。
     *
     * @param text 文本，可为 null
     * @return 计费字符数，null 与空串返回 0
     */
    public static int billableLength(String text) {
        if (text == null) {
            return 0;
        }
        int total = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            total += isHan(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return total;
    }

    /**
     * 按计费字符上限切分文本，优先在句末（。！？；换行）断开，其次在逗号/空格处断开，
     * 都没有时按码点边界硬切；调用方把各段拼回后与原文完全一致，且不会切坏代理对。
     *
     * @param text        原文，可为 null
     * @param maxBillable 每段上限（计费字符），小于 1 时按 1 处理
     * @return 各段文本；文本为 null 或空串时返回空列表
     */
    public static List<String> splitForSynth(String text, int maxBillable) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int limit = maxBillable < 1 ? 1 : maxBillable;
        // 断点至少要走到窗口的四分之一，避免一句话把整段切得过碎。
        int floor = Math.max(1, limit / 4);
        int length = text.length();
        int start = 0;
        while (start < length) {
            int cost = 0;
            int i = start;
            int hardCut = -1;
            int hardCost = 0;
            int softCut = -1;
            int softCost = 0;
            while (i < length) {
                int cp = text.codePointAt(i);
                int unit = isHan(cp) ? 2 : 1;
                if (cost + unit > limit) {
                    break;
                }
                cost += unit;
                int next = i + Character.charCount(cp);
                if (isHardBreak(cp)) {
                    hardCut = next;
                    hardCost = cost;
                } else if (isSoftBreak(cp)) {
                    softCut = next;
                    softCost = cost;
                }
                i = next;
            }
            if (i >= length) {
                out.add(text.substring(start));
                break;
            }
            int cut;
            if (hardCut > start && hardCost >= floor) {
                cut = hardCut;
            } else if (softCut > start && softCost >= floor) {
                cut = softCut;
            } else {
                cut = i;
            }
            if (cut <= start) {
                cut = i;
            }
            if (cut <= start) {
                // 兜底：i 一定是码点边界，正常情况下不会走到这里。
                cut = Math.min(length, start + 1);
            }
            out.add(text.substring(start, cut));
            start = cut;
        }
        return out;
    }

    /** 句末标点与换行：优先在这里断开。 */
    private static boolean isHardBreak(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x3002 || cp == 0xFF01 || cp == 0xFF1F
                || cp == 0xFF1B || cp == 0x2026 || cp == '!' || cp == '?' || cp == ';';
    }

    /** 逗号、顿号、冒号、空格等次级断点。 */
    private static boolean isSoftBreak(int cp) {
        return cp == 0xFF0C || cp == 0x3001 || cp == 0xFF1A || cp == ',' || cp == ':'
                || cp == 0x3000 || cp == ' ' || cp == '\t' || cp == '.' || cp == 0x3002
                || cp == '\u000B' || cp == '\f';
    }

    /** 判断是否为汉字（CJK 统一表意文字及其扩展区、兼容区）。 */
    private static boolean isHan(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2EBEF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F)
                || (cp >= 0x30000 && cp <= 0x3134F);
    }

    /** 校验音色名，非法值退回默认女声。 */
    public static String normalizeVoice(String voiceId) {
        String voice = voiceId == null ? "" : voiceId.trim().toLowerCase();
        if (VOICE_TIDE.equals(voice)) {
            return VOICE_TIDE;
        }
        return VOICE_STELLA;
    }

    /** 接口根地址：去掉末尾斜杠并强制 https。 */
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

    // ------------------------------------------------------------------
    // WAV 整理（纯函数，可被自测覆盖）
    // ------------------------------------------------------------------

    /** WAV 文件的分块信息。 */
    private static final class WavParts {
        /** data 载荷在字节数组中的起点。 */
        final int dataOffset;
        /** 可用的载荷长度。 */
        final int dataLength;

        WavParts(int dataOffset, int dataLength) {
            this.dataOffset = dataOffset;
            this.dataLength = dataLength;
        }
    }

    /**
     * 把若干段 WAV 合成一个可播放的 WAV。
     *
     * <p>服务端把 RIFF 与 data 的长度字段写成 0xFFFFFFFF 占位值（流式写出时未知总长），
     * 这里按真实字节数重写这两个字段；多段时只保留第一段的头部，把各段 PCM 依次拼在 data 里。</p>
     *
     * @param wavs 各段 WAV 字节，不能为 null
     * @return 单个 WAV 字节；输入为空时返回长度为 0 的数组
     * @throws IllegalArgumentException 某一段不是合法的 RIFF/WAVE 时抛出
     */
    public static byte[] normalizeWav(List<byte[]> wavs) {
        if (wavs == null || wavs.isEmpty()) {
            return new byte[0];
        }
        byte[] first = wavs.get(0);
        WavParts head = parseWav(first);
        if (head == null) {
            // 不是 RIFF/WAVE：原样返回，让播放器给出明确错误。
            return first == null ? new byte[0] : first;
        }
        int count = wavs.size();
        WavParts[] parts = new WavParts[count];
        parts[0] = head;
        long totalData = head.dataLength;
        for (int i = 1; i < count; i++) {
            WavParts p = parseWav(wavs.get(i));
            if (p == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 段不是合法的 WAV 数据");
            }
            parts[i] = p;
            totalData += p.dataLength;
        }
        if (totalData > Integer.MAX_VALUE - head.dataOffset) {
            throw new IllegalArgumentException("拼接后的音频过大");
        }
        int total = head.dataOffset + (int) totalData;
        byte[] out = new byte[total];
        System.arraycopy(first, 0, out, 0, head.dataOffset);
        writeU32(out, 4, total - 8L);
        writeU32(out, head.dataOffset - 4, totalData);
        int cursor = head.dataOffset;
        for (int i = 0; i < count; i++) {
            byte[] src = wavs.get(i);
            System.arraycopy(src, parts[i].dataOffset, out, cursor, parts[i].dataLength);
            cursor += parts[i].dataLength;
        }
        return out;
    }

    /** 解析 RIFF 分块，找到 data 载荷；不是合法 WAV 时返回 null。 */
    private static WavParts parseWav(byte[] wav) {
        if (wav == null || wav.length < 44) {
            return null;
        }
        if (!matches(wav, 0, "RIFF") || !matches(wav, 8, "WAVE")) {
            return null;
        }
        int pos = 12;
        while (pos + 8 <= wav.length) {
            long size = readU32(wav, pos + 4);
            int payload = pos + 8;
            if (matches(wav, pos, "data")) {
                long available = wav.length - payload;
                int length;
                if (size == U32_PLACEHOLDER || size > available) {
                    length = (int) available;
                } else {
                    length = (int) size;
                }
                if (length <= 0) {
                    return null;
                }
                return new WavParts(payload, length);
            }
            if (size > wav.length) {
                return null;
            }
            long next = payload + size + (size & 1L);
            if (next <= pos) {
                return null;
            }
            pos = (int) next;
        }
        return null;
    }

    /** 比较字节数组指定偏移处是否等于给定 ASCII 标识。 */
    private static boolean matches(byte[] data, int offset, String tag) {
        if (offset < 0 || offset + tag.length() > data.length) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            if ((data[offset + i] & 0xFF) != (tag.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** 读取小端 32 位无符号整数。 */
    static long readU32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }

    /** 写入小端 32 位无符号整数。 */
    static void writeU32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    /** 保留给调试输出：把 data 块偏移转成可读文本。 */
    static String describeWav(byte[] wav) {
        WavParts parts = parseWav(wav);
        if (parts == null) {
            return "非 WAV 数据（" + (wav == null ? 0 : wav.length) + " 字节）";
        }
        return "WAV dataOffset=" + parts.dataOffset + " dataLength=" + parts.dataLength
                + " total=" + wav.length;
    }
}
