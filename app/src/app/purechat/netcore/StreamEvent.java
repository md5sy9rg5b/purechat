/*
 * 模块 B：app.purechat.netcore —— 归一化后的流式事件（Anthropic Messages 兼容协议）。
 *
 * 说明：StreamEventParser 把 SSE 的 data JSON 归一化成该类的扁平结构，
 * UI 层只依赖这里的字段即可渲染文本/思考/签名增量，无需关心原始协议细节。
 */
package app.purechat.netcore;

/**
 * 一条归一化后的流式事件。
 *
 * <p>本类是可变的纯数据载体：字段公共可读写，默认值见各字段注释。
 * 除 {@link #index} 默认为 -1 外，数值字段默认为 0，字符串字段默认为 null。</p>
 */
public final class StreamEvent {

    /** 事件类型：消息开始（Anthropic {@code message_start}）。 */
    public static final int MESSAGE_START = 1;

    /** 事件类型：内容块开始（{@code content_block_start}）。 */
    public static final int CONTENT_BLOCK_START = 2;

    /** 事件类型：内容块增量（{@code content_block_delta}）。 */
    public static final int CONTENT_BLOCK_DELTA = 3;

    /** 事件类型：内容块结束（{@code content_block_stop}）。 */
    public static final int CONTENT_BLOCK_STOP = 4;

    /** 事件类型：消息级增量（{@code message_delta}，通常携带 stop_reason 与 usage）。 */
    public static final int MESSAGE_DELTA = 5;

    /** 事件类型：消息结束（{@code message_stop}）。 */
    public static final int MESSAGE_STOP = 6;

    /** 事件类型：心跳（{@code ping}）。 */
    public static final int PING = 7;

    /** 事件类型：错误帧（只能识别、不能恢复的协议错误）。 */
    public static final int ERROR = 8;

    /** 事件类型：无法识别的帧。 */
    public static final int UNKNOWN = 9;

    /** 事件类型，取值为本类定义的常量之一。 */
    public int type;

    /** 内容块下标；仅内容块事件有意义，未知时为 -1。 */
    public int index = -1;

    /** 内容块类型：CONTENT_BLOCK_START 时为 "text"、"thinking" 或其它值。 */
    public String blockType;

    /** 增量类型：CONTENT_BLOCK_DELTA 时为 "text_delta"、"thinking_delta"、"signature_delta"、"citations_delta" 或其它值。 */
    public String deltaType;

    /** 文本内容：text_delta / thinking_delta 的增量，或初始 text / thinking 块的完整内容。 */
    public String text;

    /** 思考签名：signature_delta 携带的签名片段。 */
    public String signature;

    /** 停止原因：MESSAGE_DELTA 的 delta.stop_reason，例如 "end_turn"、"max_tokens"。 */
    public String stopReason;

    /** 标题：MESSAGE_DELTA 的 delta.title，没有时为 null。 */
    public String title;

    /** 错误描述：ERROR 事件的可读信息。 */
    public String errorMessage;

    /** 消息 ID：MESSAGE_START 携带。 */
    public String messageId;

    /** 模型名：MESSAGE_START 携带。 */
    public String model;

    /** 输入 token 数（可能为 0）。 */
    public long inputTokens;

    /** 输出 token 数（可能为 0）。 */
    public long outputTokens;

    /**
     * 内容块的原始 JSON（服务端工具块等结构化块使用）。
     *
     * <p>对 {@code server_tool_use}、{@code web_search_tool_result}、
     * {@code web_fetch_tool_result} 三类块，这里保存 content_block_start 携带的完整对象，
     * 由上层按块类型自行取字段；其它块为 null。
     */
    public String blockJson;

    /**
     * 构造一个空事件，字段取默认值。
     */
    public StreamEvent() {
    }

    /**
     * 返回便于调试的文本形式（只打印非 null 字段）。
     *
     * @return 事件的可读描述
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("StreamEvent{type=");
        sb.append(type).append(", index=").append(index);
        if (blockType != null) {
            sb.append(", blockType=").append(blockType);
        }
        if (deltaType != null) {
            sb.append(", deltaType=").append(deltaType);
        }
        if (text != null) {
            sb.append(", text=").append(text);
        }
        if (signature != null) {
            sb.append(", signature=").append(signature);
        }
        if (stopReason != null) {
            sb.append(", stopReason=").append(stopReason);
        }
        if (title != null) {
            sb.append(", title=").append(title);
        }
        if (errorMessage != null) {
            sb.append(", errorMessage=").append(errorMessage);
        }
        if (messageId != null) {
            sb.append(", messageId=").append(messageId);
        }
        if (model != null) {
            sb.append(", model=").append(model);
        }
        sb.append(", inputTokens=").append(inputTokens);
        sb.append(", outputTokens=").append(outputTokens);
        sb.append('}');
        return sb.toString();
    }
}
