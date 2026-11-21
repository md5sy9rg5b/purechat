/*
 * 模块：app.purechat —— 文件输入（系统文件选择器）
 *
 * 依赖 android.content / android.provider 的 SAF 接口，不使用任何第三方库，
 * 语法保持 Java 8 兼容。
 */
package app.purechat;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;

/**
 * 系统文件选择器封装：用 {@code ACTION_OPEN_DOCUMENT} 选文件（支持多选），
 * 把内容复制到应用私有缓存目录后返回本地路径。
 *
 * <p>为什么不直接用返回的 {@code content://} 地址：Files API 上传需要按字节流
 * 上传并校验大小，SAF 授予的临时读权限在 Activity 重建或进程重启后会失效，
 * 先落到私有缓存可以保证上传阶段稳定可读，也避免把长权限带进后续流程。</p>
 *
 * <p>线程约束：{@link #start} 与 {@link #onActivityResult} 都必须在 UI 线程调用；
 * 复制大文件会有短暂阻塞，但受大小上限保护（最大 100 MiB）。</p>
 */
final class FilePicker {

    /** 请求码；MainActivity 用它识别自己的回调。 */
    static final int REQUEST_CODE = 0x5043;

    /** 单次最多可选文件数，避免一次复制过多文件把缓存撑满。 */
    static final int MAX_FILES = 10;

    /** 缓存文件保留时长（毫秒），超过则在下次选择时清理。 */
    private static final long CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

    /** 复制缓冲区大小。 */
    private static final int BUFFER_SIZE = 16384;

    /** 上传缓存目录名（位于应用私有 cache 下）。 */
    private static final String CACHE_DIR = "uploads";

    /** 选择器里展示的 MIME 类型：仅保留服务端支持的类型。 */
    private static final String[] ACCEPT_MIME_TYPES = new String[]{
            "image/*",
            "application/pdf",
            "text/*",
            "application/json",
            "application/xml",
            "application/epub+zip",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    };

    /** 选择结果回调。 */
    interface Listener {
        /**
         * @param callbackId 发起选择时传入的回调 id
         * @param result     选择结果
         */
        void onPicked(String callbackId, Result result);
    }

    private final Activity activity;

    /** 正在等待的调用方回调 id；null 表示当前没有进行中的选择。 */
    private String pendingId;

    /** 正在等待的回调对象。 */
    private Listener pendingListener;

    FilePicker(Activity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------
    // 结果对象
    // ------------------------------------------------------------------

    /** 一个已复制到私有缓存、可以直接上传的文件。 */
    static final class PickedFile {

        /** 私有缓存中的绝对路径。 */
        String path;

        /** 原始文件名。 */
        String name;

        /** MIME 类型。 */
        String mime;

        /** 字节数。 */
        long size;

        /** 文件类别，见 FileUploader 的 KIND_* 常量。 */
        String kind;

        /** 对话请求中应使用的内容块类型。 */
        String blockType;

        /** 该类型的大小上限。 */
        long maxBytes;

        /** 上传时应使用的 purpose。 */
        String purpose;
    }

    /** 一次选择的结果。 */
    static final class Result {

        /** 是否有至少一个文件可用。 */
        boolean ok;

        /** 取消选择时为 true。 */
        boolean canceled;

        /** 成功复制到缓存的文件。 */
        final List<PickedFile> files = new ArrayList<PickedFile>();

        /** 被拒绝的文件及原因。 */
        final List<String[]> rejected = new ArrayList<String[]>();

        /** 总体提示信息；成功时可为空串。 */
        String message = "";

        /** 序列化为前端可消费的 JSON。 */
        String toJson() {
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("ok", Boolean.valueOf(ok));
            node.put("canceled", Boolean.valueOf(canceled));
            node.put("message", message);
            List<Object> list = new ArrayList<Object>();
            for (int i = 0; i < files.size(); i++) {
                PickedFile file = files.get(i);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("path", file.path);
                item.put("name", file.name);
                item.put("mime", file.mime);
                item.put("size", Long.valueOf(file.size));
                item.put("kind", file.kind);
                item.put("blockType", file.blockType);
                item.put("maxBytes", Long.valueOf(file.maxBytes));
                item.put("purpose", file.purpose);
                list.add(item);
            }
            node.put("files", list);
            List<Object> rejects = new ArrayList<Object>();
            for (int i = 0; i < rejected.size(); i++) {
                String[] item = rejected.get(i);
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("name", item[0]);
                entry.put("message", item[1]);
                rejects.add(entry);
            }
            node.put("rejected", rejects);
            try {
                return Json.stringify(node);
            } catch (JsonException e) {
                return "{\"ok\":false,\"message\":\"结果序列化失败\"}";
            }
        }
    }

    // ------------------------------------------------------------------
    // 启动与回调
    // ------------------------------------------------------------------

    /**
     * 拉起系统文件选择器。
     *
     * @param callbackId 结果回传用的回调 id
     * @param listener   结果监听器
     * @return 是否成功拉起；已有选择在进行中或没有可用的文件管理器时返回 false
     */
    boolean start(String callbackId, Listener listener) {
        if (pendingId != null) {
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, ACCEPT_MIME_TYPES);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            pendingId = callbackId;
            pendingListener = listener;
            activity.startActivityForResult(intent, REQUEST_CODE);
            return true;
        } catch (RuntimeException e) {
            pendingId = null;
            pendingListener = null;
            return false;
        }
    }

    /** 是否有正在等待的选择。 */
    boolean isPending() {
        return pendingId != null;
    }

    /**
     * 处理 {@code onActivityResult} 回调。
     *
     * @return 是否消费了本次回调（请求码匹配）
     */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        String callbackId = pendingId;
        Listener listener = pendingListener;
        pendingId = null;
        pendingListener = null;
        if (listener == null) {
            return true;
        }
        listener.onPicked(callbackId, handleResult(resultCode, data));
        return true;
    }

    // ------------------------------------------------------------------
    // 结果处理
    // ------------------------------------------------------------------

    /** 解析选择结果：逐个复制到缓存并校验大小。 */
    private Result handleResult(int resultCode, Intent data) {
        Result result = new Result();
        if (resultCode != Activity.RESULT_OK || data == null) {
            result.canceled = true;
            result.message = "已取消选择";
            return result;
        }
        List<Uri> uris = collectUris(data);
        if (uris.isEmpty()) {
            result.canceled = true;
            result.message = "没有选择文件";
            return result;
        }
        if (uris.size() > MAX_FILES) {
            result.message = "一次最多选择 " + MAX_FILES + " 个文件";
            return result;
        }
        File dir = ensureCacheDir();
        if (dir == null) {
            result.message = "无法创建缓存目录，请检查存储空间";
            return result;
        }
        cleanExpiredCache(dir);
        for (int i = 0; i < uris.size(); i++) {
            collectOne(uris.get(i), dir, result);
        }
        if (result.files.isEmpty()) {
            result.ok = false;
            if (result.message.isEmpty()) {
                result.message = result.rejected.isEmpty()
                        ? "没有可用的文件" : result.rejected.get(0)[1];
            }
            return result;
        }
        result.ok = true;
        if (!result.rejected.isEmpty()) {
            result.message = "已加入 " + result.files.size() + " 个文件，"
                    + result.rejected.size() + " 个被拒绝";
        }
        return result;
    }

    /** 收集单选与多选返回的 Uri。 */
    private static List<Uri> collectUris(Intent data) {
        List<Uri> uris = new ArrayList<Uri>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
            return uris;
        }
        Uri single = data.getData();
        if (single != null) {
            uris.add(single);
        }
        return uris;
    }

    /** 处理单个 Uri：取名、判类型、查大小、复制到缓存。 */
    private void collectOne(Uri uri, File dir, Result result) {
        String name = queryName(uri);
        String mime = queryMime(uri, name);
        String kind = FileUploader.kindOf(name, mime);
        if (kind == null) {
            reject(result, name, "不支持的文件类型：" + name + "（支持图片、PDF、Word、PPT、纯文本、表格与电子书）");
            return;
        }
        long limit = FileUploader.maxBytesOf(kind);
        long declared = querySize(uri);
        if (declared > limit) {
            reject(result, name, name + " 大小 " + humanSize(declared) + "，超过上限 "
                    + FileUploader.formatLimit(limit));
            return;
        }
        File target = new File(dir, System.currentTimeMillis() + "-" + safeName(name));
        long copied = copy(uri, target, limit);
        if (copied < 0) {
            deleteQuietly(target);
            reject(result, name, name + " 超过上限 " + FileUploader.formatLimit(limit)
                    + "，已拒绝上传");
            return;
        }
        if (copied == 0) {
            deleteQuietly(target);
            reject(result, name, name + " 内容为空或无法读取");
            return;
        }
        PickedFile picked = new PickedFile();
        picked.path = target.getAbsolutePath();
        picked.name = name;
        picked.mime = mime;
        picked.size = copied;
        picked.kind = kind;
        picked.blockType = FileUploader.blockTypeOf(kind);
        picked.maxBytes = limit;
        picked.purpose = FileUploader.purposeOf(kind);
        result.files.add(picked);
    }

    /** 记录一条拒绝原因。 */
    private static void reject(Result result, String name, String message) {
        result.rejected.add(new String[]{name, message});
    }

    /** 复制 URI 内容到目标文件，超过上限时中止并返回 -1。 */
    private long copy(Uri uri, File target, long limit) {
        InputStream in = null;
        FileOutputStream out = null;
        long total = 0;
        try {
            in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                return 0;
            }
            out = new FileOutputStream(target);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    return -1;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            return total;
        } catch (IOException e) {
            return 0;
        } catch (SecurityException e) {
            return 0;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    // ------------------------------------------------------------------
    // 元数据查询
    // ------------------------------------------------------------------

    /** 查询展示名；查不到时退化为 Uri 末段。 */
    private String queryName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
        } catch (RuntimeException e) {
            // 某些文档提供方不支持该列，落到下面的兜底。
        } finally {
            closeQuietly(cursor);
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty() ? "file" : fallback.trim();
    }

    /** 查询 MIME；查不到时按扩展名推断。 */
    private String queryMime(Uri uri, String name) {
        String mime = null;
        try {
            mime = activity.getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            mime = null;
        }
        if (mime == null || mime.trim().isEmpty() || "application/octet-stream".equals(mime)) {
            return FileUploader.guessMime(name);
        }
        return mime;
    }

    /** 查询文件大小；查不到返回 -1。 */
    private long querySize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (RuntimeException e) {
            // 未知大小按 0 处理，复制阶段还有硬上限保护。
        } finally {
            closeQuietly(cursor);
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 缓存目录
    // ------------------------------------------------------------------

    /** 确保缓存目录存在。 */
    private File ensureCacheDir() {
        File dir = new File(activity.getCacheDir(), CACHE_DIR);
        if (dir.isDirectory()) {
            return dir;
        }
        return dir.mkdirs() ? dir : null;
    }

    /** 清理超过保留时长的缓存文件，避免占用空间。 */
    private void cleanExpiredCache(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (now - file.lastModified() > CACHE_TTL_MS) {
                deleteQuietly(file);
            }
        }
    }

    /** 把文件名里不适合做路径的字符替换掉。 */
    private static String safeName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < 64; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '_');
        }
        String value = sb.toString();
        return value.isEmpty() ? "file" : value;
    }

    /** 人类可读大小。 */
    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        double mib = bytes / (1024.0d * 1024.0d);
        if (mib >= 1.0d) {
            return String.format(Locale.ROOT, "%.1f MiB", Double.valueOf(mib));
        }
        return String.format(Locale.ROOT, "%.1f KiB", Double.valueOf(bytes / 1024.0d));
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (RuntimeException ignored) {
            // 删除失败不影响主流程。
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
