package app.purechat;

import app.purechat.netcore.Json;
import app.purechat.netcore.JsonException;

/**
 * 一次网络请求的不可变参数集，由前端 JSON 解析而来。
 *
 * <p>只接受 https 地址：应用声明了 {@code usesCleartextTraffic=false}，同时在这里再拦截一次。
 */
final class RequestSpec {

    /** 前端生成的请求 id，用于事件回传与中止。 */
    final String id;

    /** 接口根地址，例如 https://api.deepseek.com。 */
    final String origin;

    /** API Key。 */
    final String apiKey;

    /** 请求体 JSON（已由前端组装完成）。 */
    final String body;

    /**
     * 请求体是否引用了 Files API 的 file_id。
     *
     * <p>为 true 时，Messages 请求必须附加 {@code anthropic-beta: files-api-2025-04-14}
     * 请求头，否则服务端会以 400 拒绝。该值由前端 payload 的 "filesBeta" 字段给出，
     * 缺省为 false。</p>
     */
    final boolean filesBeta;

    private RequestSpec(String id, String origin, String apiKey, String body, boolean filesBeta) {
        this.id = id;
        this.origin = origin;
        this.apiKey = apiKey;
        this.body = body;
        this.filesBeta = filesBeta;
    }

    /** 解析前端传入的参数；字段缺失或地址非法时抛出 IllegalArgumentException。 */
    static RequestSpec fromJson(String payloadJson) throws JsonException {
        Object node = Json.parse(payloadJson == null ? "" : payloadJson);
        String id = Json.str(node, "id", "");
        String origin = normalizeOrigin(Json.str(node, "origin", ""));
        String apiKey = Json.str(node, "apiKey", "");
        String body = Json.str(node, "body", "");
        if (origin.isEmpty()) {
            throw new IllegalArgumentException("请先在设置中填写接口地址");
        }
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("请先在设置中填写 API Key");
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("请求内容为空");
        }
        return new RequestSpec(id, origin, apiKey, body, readFilesBeta(node));
    }

    /** 读取 "filesBeta" 布尔字段；缺省或类型不符一律按 false 处理。 */
    private static boolean readFilesBeta(Object node) {
        Object value = Json.get(node, "filesBeta");
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof String) {
            return "true".equalsIgnoreCase(((String) value).trim());
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0d;
        }
        return false;
    }

    /** 接口根地址，去掉末尾斜杠并强制 https。 */
    static String normalizeOrigin(String raw) {
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

    /** Anthropic 兼容 Messages 接口地址。 */
    String messagesUrl() {
        return origin + "/anthropic/v1/messages";
    }

    /** 校验密钥用的根地址（与 Messages 接口同源）。 */
    String messagesOrigin() {
        return origin;
    }
}
