/*
 * 模块：app.purechat.md（Markdown 解析，纯数据）
 * 文件：MdBlock.java
 *
 * 说明：MdBlock 表示 Markdown 文档中的一个块级元素（段落、标题、代码块、列表项、引用、分隔线、表格行）。
 * 块内富文本以 MdRun 列表保存，代码块则以原文保存在 text 字段。
 * 本文件只依赖 java.lang 与 java.util，不依赖 Android 与任何第三方库。
 */

package app.purechat.md;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 块级元素。
 *
 * <p>通过 {@link #type} 区分块种类；不同种类只使用各自相关的字段：
 * 标题使用 {@link #level}，列表项使用 {@link #level}/{@link #ordered}/{@link #order}，
 * 代码块使用 {@link #lang} 与 {@link #text}，其余块使用 {@link #runs}。</p>
 *
 * <p>线程安全性：本类是可变的简单数据载体，不做同步，约定构造完成后只读使用。</p>
 */
public final class MdBlock {

    /** 块类型：普通段落。 */
    public static final int PARAGRAPH = 0;
    /** 块类型：ATX 标题（{@code #} 到 {@code ######}）。 */
    public static final int HEADING = 1;
    /** 块类型：围栏代码块（{@code ```} 或 {@code ~~~}）。 */
    public static final int CODE = 2;
    /** 块类型：列表项（无序或有序）。 */
    public static final int LIST_ITEM = 3;
    /** 块类型：引用块（{@code >}）。 */
    public static final int QUOTE = 4;
    /** 块类型：分隔线（{@code ---}、{@code ***}、{@code ___}）。 */
    public static final int RULE = 5;
    /** 块类型：表格行（{@code | a | b |}）。 */
    public static final int TABLE_ROW = 6;

    /** 块类型，取值为本类的 {@code PARAGRAPH} 到 {@code TABLE_ROW} 之一。 */
    public int type = PARAGRAPH;

    /**
     * 层级。
     * <ul>
     *   <li>{@link #HEADING}：标题级别，1 到 6；</li>
     *   <li>{@link #LIST_ITEM}：缩进层级，从 0 开始；</li>
     *   <li>其它类型：固定为 0。</li>
     * </ul>
     */
    public int level;

    /** 仅对 {@link #LIST_ITEM} 有意义：{@code true} 表示有序列表项，{@code false} 表示无序列表项。 */
    public boolean ordered;

    /** 仅对有序的 {@link #LIST_ITEM} 有意义：列表序号，从 1 开始；无序列表项或其它类型为 0。 */
    public int order;

    /** 仅对 {@link #CODE} 有意义：代码围栏后的语言标记；没有语言标记时为 {@code null}。 */
    public String lang;

    /** 仅对 {@link #CODE} 有意义：完整代码内容（不包含围栏行），可能为空串；其它类型为 {@code null}。 */
    public String text;

    /** 非代码块的富文本内容，永不为 {@code null}（可能为空列表）。 */
    public List<MdRun> runs = new ArrayList<MdRun>();

    /**
     * 构造一个默认段落块，级层为 0、无富文本。
     */
    public MdBlock() {
        // 字段默认值即为所需初值，无需额外初始化。
    }

    /**
     * 以指定类型构造一个空块。
     *
     * @param type 块类型，取值见本类常量
     */
    public MdBlock(int type) {
        this.type = type;
    }

    /**
     * 判断该块是否使用 {@link #runs} 承载内容（即非代码块）。
     *
     * @return 非 {@link #CODE} 块返回 {@code true}
     */
    public boolean hasRuns() {
        return type != CODE;
    }

    /**
     * 生成便于调试的文本表示，包含类型、层级、列表信息、语言与富文本。
     *
     * @return 调试字符串
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("MdBlock{type=").append(type);
        if (type == HEADING || type == LIST_ITEM) {
            sb.append(", level=").append(level);
        }
        if (type == LIST_ITEM) {
            sb.append(", ordered=").append(ordered).append(", order=").append(order);
        }
        if (type == CODE) {
            sb.append(", lang=").append(lang);
            sb.append(", textLength=").append(text == null ? 0 : text.length());
        } else {
            sb.append(", runs=").append(runs);
        }
        return sb.append('}').toString();
    }
}
