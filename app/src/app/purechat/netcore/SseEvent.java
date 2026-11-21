/*
 * 模块 B：app.purechat.netcore —— SSE 事件数据载体。
 *
 * 说明：SseEvent 是 {@link SseDecoder} 输出的单条 Server-Sent Events 事件，
 * 只保存 event 名与拼接后的 data 文本，不做任何协议解析（协议解析见 StreamEventParser）。
 */
package app.purechat.netcore;

/**
 * 一条 Server-Sent Events 事件。
 *
 * <p>字段不可变，可安全地在线程之间传递。</p>
 */
public final class SseEvent {

    /** 事件名（对应 SSE 的 {@code event:} 字段）；未提供时为 null。 */
    public final String event;

    /** 事件数据（多条 {@code data:} 行以 "\n" 拼接，末尾换行已去除）；不为 null，可能为空串。 */
    public final String data;

    /**
     * 构造一条事件。
     *
     * @param event 事件名，可为 null
     * @param data  事件数据，null 会被转换为空串
     */
    public SseEvent(String event, String data) {
        this.event = event;
        this.data = data == null ? "" : data;
    }

    /**
     * 构造一条只有数据、没有事件名的默认事件（对应 SSE 的 message 事件）。
     *
     * @param data 事件数据，null 会被转换为空串
     */
    public SseEvent(String data) {
        this(null, data);
    }

    /**
     * 返回便于调试的文本形式，例如 {@code SseEvent(event=null, data={"type":"ping"})}。
     *
     * @return 事件的可读描述
     */
    @Override
    public String toString() {
        return "SseEvent(event=" + event + ", data=" + data + ")";
    }
}
