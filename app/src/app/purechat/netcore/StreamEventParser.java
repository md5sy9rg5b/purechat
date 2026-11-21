/*
 * 模块 B：app.purechat.netcore —— Anthropic Messages 流式事件解析器。
 *
 * 说明：
 * - 输入是 SseDecoder 产出的一条事件的 event 名与 data 文本；
 * - 输出是归一化后的 StreamEvent，UI 层无需再关心原始 JSON 结构；
 * - 任何无法识别的输入（非法 JSON、未知 type、空 data）都不会返回 null，而是返回 type=UNKNOWN；
 * - 仅含 {"error":{"message":...}} 的无名帧会被识别为 ERROR。
 */
package app.purechat.netcore;

import java.util.Map;

/**
 * StreamEvent 解析器：把 SSE 帧的 data JSON 归一化为 {@link StreamEvent}。
 *
 * <p>支持的协议为 Anthropic 兼容 Messages 流：
 * {@code message_start} / {@code content_block_start} / {@code content_block_delta} /
 * {@code content_block_stop} / {@code message_delta} / {@code message_stop} / {@code ping}，
 * 另外兼容无名 error 帧与 {"error":{...}} 形式的错误载荷。</p>
 *
 * <p>本类无状态，所有方法线程安全。</p>
 */
public final class StreamEventParser {

    /** 协议：消息开始。 */
    private static final String T_MESSAGE_START = "message_start";

    /** 协议：内容块开始。 */
    private static final String T_CONTENT_BLOCK_START = "content_block_start";

    /** 协议：内容块增量。 */
    private static final String T_CONTENT_BLOCK_DELTA = "content_block_delta";

    /** 协议：内容块结束。 */
    private static final String T_CONTENT_BLOCK_STOP = "content_block_stop";

    /** 协议：消息级增量。 */
    private static final String T_MESSAGE_DELTA = "message_delta";

    /** 协议：消息结束。 */
    private static final String T_MESSAGE_STOP = "message_stop";

    /** 协议：心跳。 */
    private static final String T_PING = "ping";

    /** 协议：错误。 */
    private static final String T_ERROR = "error";

    /** 增量类型：文本。 */
    private static final String D_TEXT = "text_delta";

    /** 增量类型：思考。 */
    private static final String D_THINKING = "thinking_delta";

    /** 增量类型：思考签名。 */
    private static final String D_SIGNATURE = "signature_delta";

    /** 增量类型：引用。 */
    private static final String D_CITATIONS = "citations_delta";

    /** 块类型：文本。 */
    private static final String B_TEXT = "text";

    /** 块类型：思考。 */
    private static final String B_THINKING = "thinking";

    /**
     * 工具类构造器：私有，禁止实例化。
     */
    private StreamEventParser() {
    }

    /**
     * 解析一条 SSE 事件的 data。
     *
     * @param sseEventName SSE 的 {@code event:} 名称，可为 null 或空串
     * @param data         SSE 的 data 文本，可为 null
     * @return 归一化事件；无法识别时返回 {@code type = StreamEvent.UNKNOWN} 的对象，永不返回 null
     */
    public static StreamEvent parse(String sseEventName, String data) {
        StreamEvent ev = new StreamEvent();
        if (data == null || data.trim().length() == 0) {
            ev.type = StreamEvent.UNKNOWN;
            return ev;
        }
        Object node;
        try {
            node = Json.parse(data);
        } catch (JsonException e) {
            node = null;
        }
        if (node instanceof String) {
            // 兼容被二次编码的载荷：data 是一个 JSON 字符串
            try {
                node = Json.parse((String) node);
            } catch (JsonException e) {
                node = null;
            }
        }
        Map<String, Object> root = Json.obj(node);
        if (root == null) {
            // 非法 JSON：只有事件名为 error 时才认为它是错误帧，否则一律未知
            ev.type = StreamEvent.UNKNOWN;
            if (T_ERROR.equals(trim(sseEventName))) {
                ev.type = StreamEvent.ERROR;
                ev.errorMessage = data.trim();
            }
            return ev;
        }

        String type = Json.str(root, "type", null);
        if (!isKnownType(type)) {
            String byName = trim(sseEventName);
            if (isKnownType(byName)) {
                type = byName;
            }
        }
        if (!isKnownType(type)) {
            if (Json.obj(Json.get(root, "error")) != null) {
                type = T_ERROR;
            }
        }
        if (!isKnownType(type)) {
            ev.type = StreamEvent.UNKNOWN;
            return ev;
        }

        if (T_MESSAGE_START.equals(type)) {
            ev.type = StreamEvent.MESSAGE_START;
            Map<String, Object> message = Json.obj(Json.get(root, "message"));
            if (message != null) {
                ev.messageId = Json.str(message, "id", null);
                ev.model = Json.str(message, "model", null);
                applyUsage(ev, Json.obj(Json.get(message, "usage")));
            }
            // 兼容把字段平铺在顶层的代理实现
            if (ev.messageId == null) {
                ev.messageId = Json.str(root, "id", null);
            }
            if (ev.model == null) {
                ev.model = Json.str(root, "model", null);
            }
            applyUsage(ev, Json.obj(Json.get(root, "usage")));
            return ev;
        }

        if (T_CONTENT_BLOCK_START.equals(type)) {
            ev.type = StreamEvent.CONTENT_BLOCK_START;
            ev.index = readIndex(root);
            Object blockNode = Json.get(root, "content_block");
            Map<String, Object> block = Json.obj(blockNode);
            if (block != null) {
                ev.blockType = Json.str(block, "type", null);
                if (B_TEXT.equals(ev.blockType)) {
                    ev.text = Json.str(block, "text", "");
                } else if (B_THINKING.equals(ev.blockType)) {
                    ev.text = Json.str(block, "thinking", "");
                    String sig = Json.str(block, "signature", null);
                    if (sig != null && sig.length() > 0) {
                        ev.signature = sig;
                    }
                } else {
                    // 服务端工具调用与搜索结果块是原子下发的结构化数据，
                    // 这里保留原始 JSON，交给上层按块类型取字段。
                    ev.blockJson = Json.stringify(blockNode);
                }
            }
            return ev;
        }

        if (T_CONTENT_BLOCK_DELTA.equals(type)) {
            ev.type = StreamEvent.CONTENT_BLOCK_DELTA;
            ev.index = readIndex(root);
            Map<String, Object> delta = Json.obj(Json.get(root, "delta"));
            if (delta != null) {
                String dType = Json.str(delta, "type", null);
                if (dType == null) {
                    dType = guessDeltaType(delta);
                }
                ev.deltaType = dType;
                if (D_TEXT.equals(dType)) {
                    ev.text = Json.str(delta, "text", "");
                } else if (D_THINKING.equals(dType)) {
                    ev.text = Json.str(delta, "thinking", Json.str(delta, "text", ""));
                } else if (D_SIGNATURE.equals(dType)) {
                    ev.signature = Json.str(delta, "signature", "");
                } else if (D_CITATIONS.equals(dType)) {
                    // 引用增量：把 citation 对象序列化到 text 字段，上层解析 url/title。
                    Object citation = Json.get(delta, "citation");
                    if (citation != null) {
                        ev.text = Json.stringify(citation);
                    }
                }
            }
            return ev;
        }

        if (T_CONTENT_BLOCK_STOP.equals(type)) {
            ev.type = StreamEvent.CONTENT_BLOCK_STOP;
            ev.index = readIndex(root);
            return ev;
        }

        if (T_MESSAGE_DELTA.equals(type)) {
            ev.type = StreamEvent.MESSAGE_DELTA;
            Map<String, Object> delta = Json.obj(Json.get(root, "delta"));
            ev.stopReason = Json.str(delta, "stop_reason", Json.str(root, "stop_reason", null));
            // 拒答时带 stop_details.explanation，前端据此展示具体原因。
            Map<String, Object> details = Json.obj(Json.get(delta, "stop_details"));
            if (details != null) {
                String explanation = Json.str(details, "explanation", null);
                if (explanation != null && !explanation.isEmpty()) {
                    ev.stopExplanation = explanation;
                }
            }
            ev.title = Json.str(delta, "title", Json.str(root, "title", null));
            applyUsage(ev, Json.obj(Json.get(root, "usage")));
            return ev;
        }

        if (T_MESSAGE_STOP.equals(type)) {
            ev.type = StreamEvent.MESSAGE_STOP;
            return ev;
        }

        if (T_PING.equals(type)) {
            ev.type = StreamEvent.PING;
            return ev;
        }

        // T_ERROR
        ev.type = StreamEvent.ERROR;
        Map<String, Object> error = Json.obj(Json.get(root, "error"));
        String msg = Json.str(error, "message", null);
        if (msg == null) {
            msg = Json.str(root, "message", null);
        }
        if (msg == null) {
            msg = Json.str(root, "error", null);
        }
        if (msg == null || msg.length() == 0) {
            msg = data.trim();
        }
        ev.errorMessage = msg;
        return ev;
    }

    /**
     * 判断事件名是否为已支持的协议类型。
     *
     * @param type 事件名
     * @return 支持时返回 true
     */
    private static boolean isKnownType(String type) {
        if (type == null) {
            return false;
        }
        return T_MESSAGE_START.equals(type)
                || T_CONTENT_BLOCK_START.equals(type)
                || T_CONTENT_BLOCK_DELTA.equals(type)
                || T_CONTENT_BLOCK_STOP.equals(type)
                || T_MESSAGE_DELTA.equals(type)
                || T_MESSAGE_STOP.equals(type)
                || T_PING.equals(type)
                || T_ERROR.equals(type);
    }

    /**
     * 读取内容块下标，缺失或类型不符时返回 -1。
     *
     * @param root 事件 JSON 对象
     * @return 内容块下标
     */
    private static int readIndex(Map<String, Object> root) {
        Object v = Json.get(root, "index");
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return -1;
    }

    /**
     * 把 usage 对象中的 token 数写入事件；缺失字段保持原值（默认 0）。
     *
     * @param ev    目标事件
     * @param usage usage 对象，可为 null
     */
    private static void applyUsage(StreamEvent ev, Map<String, Object> usage) {
        if (usage == null) {
            return;
        }
        Object in = Json.get(usage, "input_tokens");
        if (in instanceof Number) {
            ev.inputTokens = ((Number) in).longValue();
        }
        Object out = Json.get(usage, "output_tokens");
        if (out instanceof Number) {
            ev.outputTokens = ((Number) out).longValue();
        }
    }

    /**
     * 在 delta 缺少 type 时根据字段推断增量类型。
     *
     * @param delta 增量对象
     * @return 推断出的增量类型，无法推断时返回 null
     */
    private static String guessDeltaType(Map<String, Object> delta) {
        if (delta.containsKey("signature")) {
            return D_SIGNATURE;
        }
        if (delta.containsKey("thinking")) {
            return D_THINKING;
        }
        if (delta.containsKey("text")) {
            return D_TEXT;
        }
        return null;
    }

    /**
     * 去除首尾空白并归一化 null。
     *
     * @param s 原始字符串
     * @return 去空白后的字符串，null 输入返回 null
     */
    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
