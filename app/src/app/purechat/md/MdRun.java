/*
 * 模块：app.purechat.md（Markdown 解析，纯数据）
 * 文件：MdRun.java
 *
 * 说明：MdRun 是一段带有行内样式的文本片段，是 Markdown 块内富文本的最小单位。
 * 一段连续的普通文本、一段加粗文本、一个行内代码、一个链接，都各自对应一个 MdRun。
 * 本文件只依赖 java.lang 与 java.util.Objects，不依赖 Android 与任何第三方库。
 */

package app.purechat.md;

import java.util.Objects;

/**
 * Markdown 行内文本片段（run）。
 *
 * <p>一个 run 表示块内的最小样式单位：文本内容加上可叠加的样式标记（粗体、斜体、行内代码、删除线），
 * 以及可选链接地址。多个 run 按顺序拼接即构成一个块的显示内容。</p>
 *
 * <p>线程安全性：本类是可变的简单数据载体，不做同步，约定由单个线程构造并交给渲染线程只读使用。</p>
 */
public final class MdRun {

    /** 片段文本内容，永不为 {@code null}，可能为空串。 */
    public String text;

    /** 是否为粗体（{@code **x**} 或 {@code __x__}）。 */
    public boolean bold;

    /** 是否为斜体（{@code *x*} 或 {@code _x_}）。 */
    public boolean italic;

    /** 是否为行内代码（{@code `x`}），为 {@code true} 时其余样式标记没有显示意义。 */
    public boolean code;

    /** 是否为删除线（{@code ~~x~~}）。 */
    public boolean strike;

    /** 链接地址；{@code null} 表示该片段不是链接。 */
    public String href;

    /**
     * 构造一个仅含文本的片段，其余样式字段保持默认值（{@code false} / {@code null}）。
     *
     * @param text 片段文本，传入 {@code null} 时按空串处理
     */
    public MdRun(String text) {
        this.text = text == null ? "" : text;
    }

    /**
     * 复制当前片段。
     *
     * @return 字段完全相同的新片段对象
     */
    public MdRun copy() {
        MdRun run = new MdRun(this.text);
        run.bold = this.bold;
        run.italic = this.italic;
        run.code = this.code;
        run.strike = this.strike;
        run.href = this.href;
        return run;
    }

    /**
     * 判断该片段是否为纯文本：没有任何样式且不是链接。
     *
     * @return 无样式、无链接时返回 {@code true}
     */
    public boolean isPlain() {
        return !bold && !italic && !code && !strike && href == null;
    }

    /**
     * 返回便于调试的文本表示，形如 {@code MdRun{text='x', bold, href='u'}}。
     *
     * @return 调试字符串
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(48);
        sb.append("MdRun{text='").append(text).append('\'');
        if (bold) {
            sb.append(", bold");
        }
        if (italic) {
            sb.append(", italic");
        }
        if (code) {
            sb.append(", code");
        }
        if (strike) {
            sb.append(", strike");
        }
        if (href != null) {
            sb.append(", href='").append(href).append('\'');
        }
        return sb.append('}').toString();
    }

    /**
     * 按全部字段比较两个片段是否相等（使用 {@link Objects#equals(Object, Object)} 安全处理 null）。
     *
     * @param other 待比较对象
     * @return 字段完全一致时返回 {@code true}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MdRun)) {
            return false;
        }
        MdRun that = (MdRun) other;
        return bold == that.bold
                && italic == that.italic
                && code == that.code
                && strike == that.strike
                && Objects.equals(text, that.text)
                && Objects.equals(href, that.href);
    }

    /**
     * 计算与 {@link #equals(Object)} 一致的哈希值。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        int result = Objects.hashCode(text);
        result = 31 * result + (bold ? 1 : 0);
        result = 31 * result + (italic ? 1 : 0);
        result = 31 * result + (code ? 1 : 0);
        result = 31 * result + (strike ? 1 : 0);
        result = 31 * result + Objects.hashCode(href);
        return result;
    }
}
