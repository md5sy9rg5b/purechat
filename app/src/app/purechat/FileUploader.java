/*
 * 模块：app.purechat —— 文件输入（Files API 上传）
 *
 * 只依赖 java.* 与项目内的 netcore.Json，语法保持 Java 8 兼容。
 * 端点：POST {origin}/anthropic/v1/files（Anthropic 兼容 Files API）。
 * 协议要点（见官方《Files API》《文件解析》文档）：
 * - 请求必须带 anthropic-beta: files-api-2025-04-14；
 * - multipart/form-data，purpose 字段必须排在 file 字段之前；
 * - purpose 取值：图片/纯文本/表格/电子书 user_data；PDF/Word/PPT 只能
 *   text_recognition 或 rich_layout_recognition（用 user_data 会 400）；
 * - 上传大小上限：图片 64 MiB、PDF/Word/PPT 100 MiB、纯文本/表格/电子书 32 MiB。
 */
package app.purechat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;

/**
 * Files API 上传客户端：把本地文件 POST 到 Anthropic 兼容端点 {@code /anthropic/v1/files}，
 * 返回可被对话请求引用的 {@code file_id}。
 *
 * <p>本类负责三件事：</p>
 * <ol>
 *   <li>按文件名/扩展名/文件头判定文件类别（图片、扫描件、文档、纯文本）；</li>
 *   <li>据此挑选合法的 {@code purpose}，并在调用方给错 purpose 时自动纠正，
 *       避免服务端直接返回 400；</li>
 *   <li>手写 multipart/form-data（保证 purpose 在 file 之前）并解析响应。</li>
 * </ol>
 *
 * <p>线程安全性：本类无可变静态状态，所有方法都可以在任意后台线程调用；
 * {@link #upload} 会阻塞到请求结束，禁止在 UI 线程调用。</p>
 */
final class FileUploader {

    /** Anthropic 兼容 Files API 的端点路径（相对接口根地址）。 */
    static final String FILES_PATH = "/anthropic/v1/files";

    /** 引用 Files API 时必须携带的 anthropic-beta 值。 */
    static final String FILES_BETA = "files-api-2025-04-14";

    /** 请求头名称：服务端用它开启 Files API 能力。 */
    static final String HEADER_BETA = "anthropic-beta";

    /** purpose：图片、纯文本、表格、电子书。 */
    static final String PURPOSE_USER_DATA = "user_data";

    /** purpose：PDF/Word/PPT 只解析文字。 */
    static final String PURPOSE_TEXT_RECOGNITION = "text_recognition";

    /** purpose：PDF/Word/PPT 图文混排解析。 */
    static final String PURPOSE_RICH_LAYOUT = "rich_layout_recognition";

    /** 类别：普通图片（user_data，对话中用 image 块引用）。 */
    static final String KIND_IMAGE = "image";

    /** 类别：只能走 OCR 的图片（BMP/TIFF，对话中用 document 块引用）。 */
    static final String KIND_IMAGE_OCR = "image_ocr";

    /** 类别：PDF/Word/PPT 文档。 */
    static final String KIND_DOCUMENT = "document";

    /** 类别：纯文本/表格/电子书。 */
    static final String KIND_TEXT = "text";

    /** 1 MiB 的字节数。 */
    private static final long MIB = 1024L * 1024L;

    /** 图片大小上限：64 MiB。 */
    static final long MAX_IMAGE_BYTES = 64L * MIB;

    /** PDF/Word/PPT 大小上限：100 MiB。 */
    static final long MAX_DOCUMENT_BYTES = 100L * MIB;

    /** 纯文本/表格/电子书大小上限：32 MiB。 */
    static final long MAX_TEXT_BYTES = 32L * MIB;

    /** 文件名长度上限（字节），官方限制 512 字节。 */
    private static final int MAX_FILENAME_BYTES = 512;

    /** 建立连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 20000;

    /** 读取响应超时（毫秒）；文档上传官方要求 120 秒内完成，这里留足余量。 */
    private static final int READ_TIMEOUT_MS = 300000;

    /** 统一 UTF-8。 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 错误响应体最大读取长度，避免异常响应打爆内存。 */
    private static final int MAX_ERROR_BODY = 65536;

    /** multipart 边界里使用的随机串前缀。 */
    private static final String BOUNDARY_PREFIX = "----PureChatBoundary";

    /** 工具类构造器：私有，禁止实例化。 */
    private FileUploader() {
    }

    // ------------------------------------------------------------------
    // 结果对象
    // ------------------------------------------------------------------

    /** 一次上传的结果；成功时带上服务端返回的文件对象字段。 */
    static final class Result {

        /** 是否上传成功。 */
        boolean ok;

        /** 服务端文件 id，形如 {@code file-api-xxx}；失败时为 null。 */
        String fileId;

        /** 服务端记录的文件名。 */
        String filename;

        /** 服务端记录的 MIME 类型。 */
        String mimeType;

        /** 服务端记录的字节数；未返回时为本地文件大小。 */
        long sizeBytes;

        /** 服务端记录的创建时间（RFC 3339 字符串）。 */
        String createdAt;

        /** 实际使用的 purpose。 */
        String purpose;

        /** 调用方请求的 purpose（可能是 auto 或空串）。 */
        String requestedPurpose;

        /** 文件类别，见本类的 KIND_* 常量。 */
        String kind;

        /** 对话请求中应使用的内容块类型：{@code image} 或 {@code document}。 */
        String blockType;

        /** 是否因为 purpose 非法而被自动纠正。 */
        boolean purposeAdjusted;

        /** HTTP 状态码；未建立连接时为 0。 */
        int httpStatus;

        /** 失败时的可读错误信息（中文）。 */
        String message;

        /** 把结果序列化为前端可直接消费的 JSON 字符串。 */
        String toJson() {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("ok", Boolean.valueOf(ok));
            if (!ok) {
                node.put("message", message == null ? "上传失败" : message);
                node.put("httpStatus", Integer.valueOf(httpStatus));
                if (purpose != null) {
                    node.put("purpose", purpose);
                }
                return Json.stringify(node);
            }
            node.put("fileId", fileId);
            node.put("filename", filename);
            node.put("mimeType", mimeType);
            node.put("sizeBytes", Long.valueOf(sizeBytes));
            node.put("createdAt", createdAt);
            node.put("purpose", purpose);
            node.put("requestedPurpose", requestedPurpose);
            node.put("kind", kind);
            node.put("blockType", blockType);
            node.put("purposeAdjusted", Boolean.valueOf(purposeAdjusted));
            node.put("httpStatus", Integer.valueOf(httpStatus));
            node.put("contentBlock", contentBlock(fileId, blockType));
            return Json.stringify(node);
        }
    }

    /**
     * 生成对话请求里引用该文件的内容块。
     *
     * @param fileId    服务端文件 id
     * @param blockType {@code image} 或 {@code document}
     * @return 内容块对应的 Map（可直接交给 {@link Json#stringify(Object)}）
     */
    static Map<String, Object> contentBlock(String fileId, String blockType) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("type", "file");
        source.put("file_id", fileId);
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", KIND_IMAGE.equals(blockType) ? KIND_IMAGE : KIND_DOCUMENT);
        block.put("source", source);
        return block;
    }

    // ------------------------------------------------------------------
    // 文件分类与限额
    // ------------------------------------------------------------------

    /**
     * 按文件真实内容（文件头）、扩展名、MIME 依次判定文件类别。
     *
     * @param file     本地文件，可为 null（此时只用扩展名与 MIME）
     * @param filename 原始文件名，可为 null
     * @param mime     MIME 类型，可为 null
     * @return {@link #KIND_IMAGE}、{@link #KIND_IMAGE_OCR}、{@link #KIND_DOCUMENT}、{@link #KIND_TEXT}
     *         之一；无法判定时返回 null
     */
    static String kindOf(File file, String filename, String mime) {
        byte[] head = file == null ? null : readHead(file);
        String byMagic = kindOfMagic(head);
        if (byMagic != null) {
            return byMagic;
        }
        String byExt = kindOfExtension(extensionOf(filename));
        if (byExt != null) {
            return byExt;
        }
        return kindOfMime(mime);
    }

    /** 只按文件名与 MIME 判定类别（选择文件时用于提前校验大小）。 */
    static String kindOf(String filename, String mime) {
        String byExt = kindOfExtension(extensionOf(filename));
        if (byExt != null) {
            return byExt;
        }
        return kindOfMime(mime);
    }

    /**
     * 返回该类别文件的上传大小上限。
     *
     * @param kind 文件类别
     * @return 字节数上限；类别未知时返回 {@link #MAX_TEXT_BYTES}
     */
    static long maxBytesOf(String kind) {
        if (KIND_IMAGE.equals(kind) || KIND_IMAGE_OCR.equals(kind)) {
            return MAX_IMAGE_BYTES;
        }
        if (KIND_DOCUMENT.equals(kind)) {
            return MAX_DOCUMENT_BYTES;
        }
        return MAX_TEXT_BYTES;
    }

    /**
     * 返回该类别推荐的 purpose。
     *
     * @param kind 文件类别
     * @return user_data 或 text_recognition
     */
    static String purposeOf(String kind) {
        if (KIND_DOCUMENT.equals(kind) || KIND_IMAGE_OCR.equals(kind)) {
            return PURPOSE_TEXT_RECOGNITION;
        }
        return PURPOSE_USER_DATA;
    }

    /**
     * 返回该类别在对话请求中应使用的内容块类型。
     *
     * <p>只有以 user_data 上传的普通图片才能当图片引用；扫描件与文档在服务端属于
     * OCR 产物，只能作为 document 块引用（否则服务端会拒绝该 file_id）。</p>
     *
     * @param kind 文件类别
     * @return {@code image} 或 {@code document}
     */
    static String blockTypeOf(String kind) {
        return KIND_IMAGE.equals(kind) ? KIND_IMAGE : KIND_DOCUMENT;
    }

    /**
     * 把字节数格式化为便于阅读的文本。
     *
     * @param bytes 字节数
     * @return 例如 {@code 64 MiB}
     */
    static String formatLimit(long bytes) {
        if (bytes >= MIB) {
            return (bytes / MIB) + " MiB";
        }
        return bytes + " 字节";
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    /**
     * 上传一个本地文件到 Files API。
     *
     * <p>阻塞方法，必须在后台线程调用。上传前会按文件真实内容重新判定类别，
     * 若调用方给出的 purpose 与服务端规则冲突（例如给 PDF 传 user_data），
     * 这里会自动纠正为合法值并在结果里标记 {@code purposeAdjusted}，
     * 而不是把注定 400 的请求发出去。</p>
     *
     * @param origin          接口根地址，例如 {@code https://api.deepseek.com}
     * @param apiKey          API Key
     * @param path            本地文件绝对路径
     * @param filename        上传时使用的文件名（可为 null，默认取路径末段）
     * @param mime            文件 MIME 类型（可为 null，按扩展名推断）
     * @param requestedPurpose 调用方希望的 purpose；{@code auto}、空或 null 表示由本类决定
     * @return 上传结果，永不为 null
     */
    static Result upload(String origin, String apiKey, String path, String filename,
                         String mime, String requestedPurpose) {
        Result result = new Result();
        result.requestedPurpose = requestedPurpose == null ? "" : requestedPurpose;

        File file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            result.message = "文件不存在或已被清理，请重新选择";
            return result;
        }
        String name = sanitizeFilename(filename == null || filename.isEmpty()
                ? file.getName() : filename);
        String contentType = mime == null || mime.trim().isEmpty()
                ? guessMime(name) : mime.trim();

        String kind = kindOf(file, name, contentType);
        if (kind == null) {
            result.message = "不支持的文件类型，请选择图片、PDF、Word、PPT、纯文本或表格文件";
            return result;
        }
        result.kind = kind;
        result.blockType = blockTypeOf(kind);

        long length = file.length();
        long limit = maxBytesOf(kind);
        if (length <= 0) {
            result.message = "文件内容为空，无法上传";
            return result;
        }
        if (length > limit) {
            result.message = "文件 " + name + " 大小 " + formatSize(length)
                    + "，超过该类型上限 " + formatLimit(limit);
            return result;
        }

        String purpose = resolvePurpose(requestedPurpose, kind);
        result.purposeAdjusted = !purpose.equals(normalizedPurpose(requestedPurpose, kind));
        result.purpose = purpose;

        HttpURLConnection connection = null;
        try {
            String base = RequestSpec.normalizeOrigin(origin);
            if (base.isEmpty()) {
                result.message = "请先在设置中填写接口地址";
                return result;
            }
            if (apiKey == null || apiKey.trim().isEmpty()) {
                result.message = "请先在设置中填写 API Key";
                return result;
            }
            String boundary = BOUNDARY_PREFIX + Long.toHexString(System.nanoTime());
            byte[] preamble = buildPreamble(boundary, purpose, name, contentType);
            byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(UTF8);

            URL url = new URL(base + FILES_PATH);
            connection = (HttpURLConnection) url.openConnection();
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("x-api-key", apiKey.trim());
            connection.setRequestProperty(HEADER_BETA, FILES_BETA);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setFixedLengthStreamingMode(preamble.length + length + epilogue.length);

            OutputStream out = connection.getOutputStream();
            try {
                out.write(preamble);
                copyFile(file, out);
                out.write(epilogue);
                out.flush();
            } finally {
                closeQuietly(out);
            }

            int code = connection.getResponseCode();
            result.httpStatus = code;
            String body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code < 200 || code >= 300) {
                result.message = describeError(code, body);
                return result;
            }
            fillFromResponse(result, body, file, name, contentType);
            return result;
        } catch (IOException e) {
            result.message = describeThrowable(e);
            return result;
        } catch (RuntimeException e) {
            result.message = describeThrowable(e);
            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // purpose 规则
    // ------------------------------------------------------------------

    /** 把调用方给的 purpose 归一化：auto/空 → 类别推荐值，其它原样小写返回。 */
    private static String normalizedPurpose(String requested, String kind) {
        String value = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "auto".equals(value)) {
            return purposeOf(kind);
        }
        return value;
    }

    /**
     * 计算真正要发送的 purpose，并拦掉服务端必然拒绝的组合。
     *
     * <p>实测规则：以 user_data 上传 PDF/Word/PPT 返回 400；以 text_recognition
     * 上传纯文本返回 400（“file format is not supported”）。</p>
     */
    private static String resolvePurpose(String requested, String kind) {
        String value = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        boolean explicit = !(value.isEmpty() || "auto".equals(value));
        String recommended = purposeOf(kind);
        if (!explicit) {
            return recommended;
        }
        boolean ocrPurpose = PURPOSE_TEXT_RECOGNITION.equals(value)
                || PURPOSE_RICH_LAYOUT.equals(value);
        if (KIND_DOCUMENT.equals(kind)) {
            // 文档只能用 OCR 类 purpose；rich_layout_recognition 是合法的图文混排选项。
            return ocrPurpose ? value : PURPOSE_TEXT_RECOGNITION;
        }
        if (KIND_IMAGE_OCR.equals(kind)) {
            return PURPOSE_RICH_LAYOUT.equals(value) ? value : PURPOSE_TEXT_RECOGNITION;
        }
        // 图片、纯文本、表格、电子书只能 user_data。
        return PURPOSE_USER_DATA;
    }

    // ------------------------------------------------------------------
    // multipart 组装
    // ------------------------------------------------------------------

    /** 组装 purpose 段与 file 段的头部；purpose 必须排在 file 之前。 */
    private static byte[] buildPreamble(String boundary, String purpose, String filename,
                                        String mime) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"purpose\"\r\n");
        sb.append("\r\n");
        sb.append(purpose).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(asciiFilename(filename)).append('"');
        String encoded = rfc5987(filename);
        if (!encoded.isEmpty()) {
            sb.append("; filename*=UTF-8''").append(encoded);
        }
        sb.append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("\r\n");
        return sb.toString().getBytes(UTF8);
    }

    /** 去掉路径分隔符与控制字符，并按 UTF-8 字节数截断到 512 字节以内。 */
    private static String sanitizeFilename(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty()) {
            clean = "file";
        }
        while (clean.getBytes(UTF8).length > MAX_FILENAME_BYTES && clean.length() > 1) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    /** ASCII 回退文件名：非 ASCII 字符替换为下划线，避免 header 编码问题。 */
    private static String asciiFilename(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x80 && c != '"' && c != '\\') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /** RFC 5987 的 UTF-8 百分号编码；纯 ASCII 时返回空串（无需第二参数）。 */
    private static String rfc5987(String name) {
        StringBuilder sb = new StringBuilder(name.length() * 3);
        boolean ascii = true;
        byte[] bytes = name.getBytes(UTF8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            char c = (char) b;
            boolean plain = b < 0x80 && (Character.isLetterOrDigit(c)
                    || c == '-' || c == '.' || c == '_' || c == '~');
            if (!plain) {
                ascii = false;
                sb.append('%');
                String hex = Integer.toHexString(b).toUpperCase(Locale.ROOT);
                if (hex.length() < 2) {
                    sb.append('0');
                }
                sb.append(hex);
            } else {
                sb.append(c);
            }
        }
        return ascii ? "" : sb.toString();
    }

    // ------------------------------------------------------------------
    // 文件与类型工具
    // ------------------------------------------------------------------

    /** 读取文件头最多 12 字节，用于内容判定；失败返回 null。 */
    private static byte[] readHead(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] head = new byte[12];
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read <= 0) {
                return null;
            }
            byte[] trimmed = new byte[read];
            System.arraycopy(head, 0, trimmed, 0, read);
            return trimmed;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 按文件头判定类别；无法判定返回 null。 */
    private static String kindOfMagic(byte[] head) {
        if (head == null || head.length < 2) {
            return null;
        }
        if (startsWith(head, new int[]{0x25, 0x50, 0x44, 0x46})) {
            return KIND_DOCUMENT; // %PDF
        }
        if (startsWith(head, new int[]{0x89, 0x50, 0x4E, 0x47})) {
            return KIND_IMAGE; // PNG
        }
        if (startsWith(head, new int[]{0xFF, 0xD8, 0xFF})) {
            return KIND_IMAGE; // JPEG
        }
        if (startsWith(head, new int[]{0x47, 0x49, 0x46, 0x38})) {
            return KIND_IMAGE; // GIF8
        }
        if (startsWith(head, new int[]{0x52, 0x49, 0x46, 0x46}) && head.length >= 12
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return KIND_IMAGE; // RIFF....WEBP
        }
        if (startsWith(head, new int[]{0x42, 0x4D})) {
            return KIND_IMAGE_OCR; // BMP，只能走 OCR
        }
        return null;
    }

    /** 判断字节序列是否以给定的 ASCII 码开头。 */
    private static boolean startsWith(byte[] data, int[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 按扩展名判定类别。 */
    private static String kindOfExtension(String ext) {
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "gif".equals(ext) || "webp".equals(ext)) {
            return KIND_IMAGE;
        }
        if ("bmp".equals(ext) || "tif".equals(ext) || "tiff".equals(ext)) {
            return KIND_IMAGE_OCR;
        }
        if ("pdf".equals(ext) || "doc".equals(ext) || "docx".equals(ext)
                || "ppt".equals(ext) || "pptx".equals(ext)) {
            return KIND_DOCUMENT;
        }
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)
                || "csv".equals(ext) || "tsv".equals(ext) || "json".equals(ext)
                || "jsonl".equals(ext) || "xml".equals(ext) || "yaml".equals(ext)
                || "yml".equals(ext) || "html".equals(ext) || "htm".equals(ext)
                || "log".equals(ext) || "ini".equals(ext) || "toml".equals(ext)
                || "cfg".equals(ext) || "conf".equals(ext) || "sql".equals(ext)
                || "xlsx".equals(ext) || "xls".equals(ext)
                || "epub".equals(ext) || "mobi".equals(ext) || "azw3".equals(ext)
                || "js".equals(ext) || "mjs".equals(ext) || "cjs".equals(ext)
                || "ts".equals(ext) || "tsx".equals(ext) || "jsx".equals(ext)
                || "py".equals(ext) || "java".equals(ext) || "kt".equals(ext)
                || "c".equals(ext) || "h".equals(ext) || "cpp".equals(ext)
                || "cc".equals(ext) || "hpp".equals(ext) || "cs".equals(ext)
                || "go".equals(ext) || "rs".equals(ext) || "rb".equals(ext)
                || "php".equals(ext) || "sh".equals(ext) || "ps1".equals(ext)
                || "bat".equals(ext) || "lua".equals(ext) || "swift".equals(ext)
                || "css".equals(ext) || "scss".equals(ext) || "vue".equals(ext)
                || "srt".equals(ext) || "vtt".equals(ext)) {
            return KIND_TEXT;
        }
        return null;
    }

    /** 按 MIME 判定类别。 */
    private static String kindOfMime(String mime) {
        if (mime == null) {
            return null;
        }
        String value = mime.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if ("image/jpeg".equals(value) || "image/jpg".equals(value) || "image/png".equals(value)
                || "image/gif".equals(value) || "image/webp".equals(value)) {
            return KIND_IMAGE;
        }
        if ("image/bmp".equals(value) || "image/tiff".equals(value)) {
            return KIND_IMAGE_OCR;
        }
        if ("application/pdf".equals(value)
                || "application/msword".equals(value)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(value)
                || "application/vnd.ms-powerpoint".equals(value)
                || "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(value)) {
            return KIND_DOCUMENT;
        }
        if (value.startsWith("text/")
                || "application/json".equals(value)
                || "application/xml".equals(value)
                || "application/epub+zip".equals(value)
                || "application/vnd.ms-excel".equals(value)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(value)) {
            return KIND_TEXT;
        }
        return null;
    }

    /** 取小写扩展名（不含点）；没有扩展名返回空串。 */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按扩展名猜 MIME；未知时退化为 application/octet-stream。 */
    static String guessMime(String filename) {
        String ext = extensionOf(filename);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return "image/jpeg";
        }
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("gif".equals(ext)) {
            return "image/gif";
        }
        if ("webp".equals(ext)) {
            return "image/webp";
        }
        if ("bmp".equals(ext)) {
            return "image/bmp";
        }
        if ("tif".equals(ext) || "tiff".equals(ext)) {
            return "image/tiff";
        }
        if ("pdf".equals(ext)) {
            return "application/pdf";
        }
        if ("doc".equals(ext)) {
            return "application/msword";
        }
        if ("docx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if ("ppt".equals(ext)) {
            return "application/vnd.ms-powerpoint";
        }
        if ("pptx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if ("xls".equals(ext)) {
            return "application/vnd.ms-excel";
        }
        if ("xlsx".equals(ext)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if ("json".equals(ext)) {
            return "application/json";
        }
        if ("xml".equals(ext)) {
            return "application/xml";
        }
        if ("epub".equals(ext)) {
            return "application/epub+zip";
        }
        if (kindOfExtension(ext) != null) {
            String kind = kindOfExtension(ext);
            if (KIND_TEXT.equals(kind)) {
                return "text/plain";
            }
        }
        return "application/octet-stream";
    }

    /** 把字节数格式化为人类可读的大小（用于错误提示）。 */
    private static String formatSize(long bytes) {
        if (bytes >= MIB) {
            return String.format(Locale.ROOT, "%.1f MiB", Double.valueOf(bytes / (double) MIB));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", Double.valueOf(bytes / 1024.0d));
        }
        return bytes + " 字节";
    }

    // ------------------------------------------------------------------
    // 响应解析与错误处理
    // ------------------------------------------------------------------

    /** 解析成功响应，填充 file_id 等字段。 */
    private static void fillFromResponse(Result result, String body, File file, String name,
                                         String mime) {
        Object node = null;
        try {
            node = Json.parse(body);
        } catch (JsonException e) {
            result.message = "上传响应无法解析，请检查接口地址是否正确";
            return;
        }
        String id = Json.str(node, "id", "");
        if (id.isEmpty()) {
            result.message = "上传响应缺少文件 id，请稍后重试";
            return;
        }
        result.ok = true;
        result.fileId = id;
        result.filename = Json.str(node, "filename", name);
        result.mimeType = Json.str(node, "mime_type", mime);
        double size = Json.num(node, "size_bytes", (double) file.length());
        result.sizeBytes = (long) size;
        result.createdAt = Json.str(node, "created_at", "");
    }

    /** 把 HTTP 错误响应翻译成可读的中文提示。 */
    private static String describeError(int code, String body) {
        String raw = extractErrorMessage(body);
        if (code == 401) {
            return "API Key 无效或已失效（HTTP 401）";
        }
        if (code == 403) {
            return "没有权限上传文件（HTTP 403）";
        }
        if (code == 413) {
            return "文件超过服务端大小上限（HTTP 413）";
        }
        if (code == 429) {
            return "请求过于频繁，请稍后再试（HTTP 429）";
        }
        if (code >= 500) {
            return "服务端暂时不可用（HTTP " + code + "）"
                    + (raw == null ? "" : "：" + raw);
        }
        if (raw == null) {
            return "上传失败（HTTP " + code + "）";
        }
        if (raw.indexOf("anthropic-beta") >= 0) {
            return "服务端要求 anthropic-beta 请求头，请升级应用后重试";
        }
        if (raw.indexOf("must be images or plain-text") >= 0) {
            return "该文件是文档，必须按文档解析方式上传（user_data 会被拒绝）";
        }
        if (raw.indexOf("Failed to recognize the file") >= 0) {
            return "服务端无法识别该文件格式，请换一种文件类型";
        }
        return raw;
    }

    /** 从错误响应体中取 error.message；取不到时截断原文。 */
    private static String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            Object node = Json.parse(body);
            Object error = Json.get(node, "error");
            String message = Json.str(error, "message", null);
            if (message == null || message.isEmpty()) {
                message = Json.str(node, "message", null);
            }
            if (message != null && !message.isEmpty()) {
                return stripRequestId(message);
            }
        } catch (JsonException e) {
            // 非 JSON 响应，落到下面的截断逻辑。
        }
        String text = body.trim();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /** 去掉错误信息末尾的 request_id 括号，前端提示更干净。 */
    private static String stripRequestId(String message) {
        int index = message.indexOf(" (request_id:");
        if (index < 0) {
            return message;
        }
        return message.substring(0, index).trim();
    }

    /** 把网络异常翻译成可读中文。 */
    private static String describeThrowable(Throwable error) {
        if (error instanceof java.net.UnknownHostException) {
            return "无法解析服务器域名，请检查网络与接口地址";
        }
        if (error instanceof java.net.SocketTimeoutException) {
            return "上传超时，请检查网络后重试";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "安全连接失败，请检查网络环境";
        }
        if (error instanceof java.net.ConnectException) {
            return "无法连接服务器，请检查网络";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------
    // IO 工具
    // ------------------------------------------------------------------

    /** 把文件内容写入输出流。 */
    private static void copyFile(File file, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            closeQuietly(in);
        }
    }

    /** 读取响应体，最多 {@link #MAX_ERROR_BODY} 字节。 */
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
                if (buffer.size() > MAX_ERROR_BODY) {
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

    /** 静默关闭。 */
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
