/*
 * 模块 B：app.purechat.netcore —— 增量式 SSE 解码器（WHATWG Server-Sent Events）。
 *
 * 说明：
 * - 网络分片可以任意切分（包括把 CRLF 拆到两个分片），解码器自行维护行缓冲；
 * - 以空行作为事件分隔符，事件未以空行结尾时 {@link #end} 会丢弃它；
 * - 忽略注释行（以 ':' 开头）与未知字段；{@code data:} 多行以 "\n" 连接；
 * - 事件名在没有 data 字段时也会被派发（data 为空串）。
 */
package app.purechat.netcore;

import java.util.List;

/**
 * 增量式 SSE 解码器。
 *
 * <p>使用方式：每收到一段网络文本就调用 {@link #push(String, List)}，
 * 事件会追加到调用方提供的列表中；流结束时调用 {@link #end(List)} 清理未完成的事件。</p>
 *
 * <p>本类不是线程安全的：同一个实例应由同一个线程（通常是网络读线程）驱动。</p>
 */
public final class SseDecoder {

    /** 当前行缓冲（不含换行符）。 */
    private final StringBuilder lineBuf = new StringBuilder(128);

    /** 当前事件的 data 行缓冲，每行以 "\n" 结尾。 */
    private final StringBuilder dataBuf = new StringBuilder(128);

    /** 当前事件名；null 表示使用默认事件名。 */
    private String eventType;

    /** 当前事件块中是否出现过有效字段（event/data/id/retry）；用于避免空块产生空事件。 */
    private boolean sawField;

    /** 上一个字符是否为被推迟处理的 '\r'（用于识别 CRLF）。 */
    private boolean pendingCr;

    /** 尚未读取过任何字符，需要检查并跳过 UTF-8 BOM。 */
    private boolean needBomCheck = true;

    /**
     * 构造一个空的 SSE 解码器。
     */
    public SseDecoder() {
    }

    /**
     * 推入一段网络文本分片，并把解码出的事件追加到 {@code out}。
     *
     * @param chunk 文本分片，null 或空串时直接返回，不做任何处理
     * @param out   事件输出列表；为 null 时解析出的帧会被丢弃（不抛异常）
     */
    public void push(String chunk, List<SseEvent> out) {
        if (chunk == null || chunk.length() == 0) {
            return;
        }
        int i = 0;
        if (needBomCheck) {
            needBomCheck = false;
            if (chunk.charAt(0) == '\uFEFF') {
                i = 1;
            }
        }
        int len = chunk.length();
        for (; i < len; i++) {
            char c = chunk.charAt(i);
            if (pendingCr) {
                pendingCr = false;
                if (c == '\n') {
                    continue;
                }
            }
            if (c == '\r') {
                endLine(out);
                pendingCr = true;
            } else if (c == '\n') {
                endLine(out);
            } else {
                lineBuf.append(c);
            }
        }
    }

    /**
     * 结束流：丢弃尚未以空行结尾的未完成事件，并重置内部状态。
     *
     * <p>调用后解码器可以继续复用于下一条流。</p>
     *
     * @param out 事件输出列表；本方法不会向其中追加内容，可为 null
     */
    public void end(List<SseEvent> out) {
        lineBuf.setLength(0);
        dataBuf.setLength(0);
        eventType = null;
        sawField = false;
        pendingCr = false;
        needBomCheck = true;
    }

    /**
     * 处理一行（不含换行符）：更新当前事件字段，遇空行则派发事件。
     *
     * @param out 事件输出列表
     */
    private void endLine(List<SseEvent> out) {
        String line = lineBuf.toString();
        lineBuf.setLength(0);
        if (line.length() == 0) {
            dispatch(out);
            return;
        }
        if (line.charAt(0) == ':') {
            // 注释行（例如 SSE 心跳 ": keep-alive"）直接丢弃
            return;
        }
        String field;
        String value;
        int colon = line.indexOf(':');
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (value.length() > 0 && value.charAt(0) == ' ') {
                value = value.substring(1);
            }
        }
        if ("event".equals(field)) {
            eventType = value;
            sawField = true;
        } else if ("data".equals(field)) {
            dataBuf.append(value).append('\n');
            sawField = true;
        } else if ("id".equals(field) || "retry".equals(field)) {
            // 本模块不使用 id 与 retry，但它们是合法字段，不应被当成空块
            sawField = true;
        }
        // 其它未知字段按规范忽略，也不计入“有效字段”
    }

    /**
     * 派发当前事件：data 去除末尾换行，事件名与 data 一起构成 SseEvent。
     *
     * @param out 事件输出列表，可为 null
     */
    private void dispatch(List<SseEvent> out) {
        if (!sawField) {
            eventType = null;
            dataBuf.setLength(0);
            return;
        }
        String data = dataBuf.toString();
        if (data.endsWith("\n")) {
            data = data.substring(0, data.length() - 1);
        }
        if (out != null) {
            out.add(new SseEvent(eventType, data));
        }
        eventType = null;
        dataBuf.setLength(0);
        sawField = false;
    }
}
