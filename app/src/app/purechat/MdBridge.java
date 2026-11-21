package app.purechat;

import java.util.List;

import app.purechat.md.MdBlock;
import app.purechat.md.MdCore;
import app.purechat.md.MdRun;

/**
 * 把 Markdown 文本转换成安全的 HTML 片段交给 WebView 渲染。
 *
 * <p>解析由纯 Java 的 {@link MdCore} 完成，这里只负责输出结构：所有文本都经过 HTML 转义，
 * 链接只保留 http/https，因此模型输出的任何内容都不会变成可执行脚本。
 */
final class MdBridge {

    /** 代码块内保留的换行用 
 表示。 */
    private static final char LF = '\n';

    private MdBridge() {
    }

    /** 公式占位符前后缀：解析前把公式抽出来，避免被 Markdown 的行内标记拆散。 */
    private static final String MATH_OPEN = "\u0000MATH";

    /** 渲染整段 Markdown。 */
    static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        // 先把数学公式替换成占位符，解析完成后再还原为 KaTeX 容器：
        // 否则公式里的下划线、星号会被当成强调标记，公式内容被拆散。
        List<String> formulas = new java.util.ArrayList<String>();
        String prepared = extractMath(markdown, formulas);
        List<MdBlock> blocks;
        try {
            blocks = MdCore.parse(prepared);
        } catch (RuntimeException e) {
            // 解析异常时退化为纯文本，保证界面不空白。
            return "<p class=\"md-p\">" + escape(markdown) + "</p>";
        }
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        List<MdBlock> list = blocks;
        boolean inList = false;
        boolean orderedList = false;
        for (int i = 0; i < list.size(); i++) {
            MdBlock block = list.get(i);
            boolean isListItem = block.type == MdBlock.LIST_ITEM;
            if (isListItem) {
                boolean ordered = block.ordered;
                if (!inList || ordered != orderedList) {
                    closeList(out, inList, orderedList);
                    out.append(ordered ? "<ol class=\"md-ol\">" : "<ul class=\"md-ul\">");
                    inList = true;
                    orderedList = ordered;
                }
            } else if (inList) {
                closeList(out, inList, orderedList);
                inList = false;
            }

            switch (block.type) {
                case MdBlock.PARAGRAPH:
                    out.append("<p class=\"md-p\">");
                    appendRuns(out, block.runs);
                    out.append("</p>");
                    break;
                case MdBlock.HEADING: {
                    int level = Math.max(1, Math.min(6, block.level));
                    out.append("<h").append(level).append(" class=\"md-h").append(level).append("\">");
                    appendRuns(out, block.runs);
                    out.append("</h").append(level).append('>');
                    break;
                }
                case MdBlock.CODE:
                    out.append("<div class=\"md-pre\">");
                    if (block.lang != null && !block.lang.isEmpty()) {
                        out.append("<div class=\"md-code-lang\">").append(escape(block.lang))
                                .append("</div>");
                    }
                    out.append("<pre><code class=\"md-code\">")
                            .append(escape(block.text == null ? "" : block.text))
                            .append("</code></pre></div>");
                    break;
                case MdBlock.QUOTE:
                    out.append("<blockquote class=\"md-quote\">");
                    appendRuns(out, block.runs);
                    out.append("</blockquote>");
                    break;
                case MdBlock.RULE:
                    out.append("<hr class=\"md-hr\">");
                    break;
                case MdBlock.TABLE_ROW:
                    // 连续的表格行合并为一张表：首行作表头，对齐分隔行跳过。
                    if (i == 0 || list.get(i - 1).type != MdBlock.TABLE_ROW) {
                        out.append("<div class=\"md-table-wrap\"><table class=\"md-table\">");
                        appendTableRow(out, block, "th");
                        out.append("<tbody>");
                    } else if (isSeparatorRow(block)) {
                        break;
                    } else {
                        appendTableRow(out, block, "td");
                    }
                    if (i == list.size() - 1 || list.get(i + 1).type != MdBlock.TABLE_ROW) {
                        out.append("</tbody></table></div>");
                    }
                    break;
                case MdBlock.LIST_ITEM:
                    out.append("<li class=\"md-li\">");
                    appendRuns(out, block.runs);
                    out.append("</li>");
                    break;
                default:
                    break;
            }
        }
        if (inList) {
            closeList(out, inList, orderedList);
        }
        return restoreMath(out.toString(), formulas);
    }

    /**
     * 把公式替换为 {@code \u0000MATH{序号}\u0000} 占位符。
     *
     * <p>识别行内公式 {@code \\(...\\)}、块级公式 {@code \\[...\\]} 以及 {@code $$...$$}；
     * 未闭合的公式按普通文本处理，避免吞掉后续正文。
     */
    private static String extractMath(String text, List<String> formulas) {
        if (text.indexOf('\\') < 0 && text.indexOf("$$") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int inlineStart = text.indexOf("\\(", i);
            int blockStart = text.indexOf("\\[", i);
            int dollarStart = text.indexOf("$$", i);
            int start = -1;
            String closer = null;
            boolean display = false;
            int[] candidates = { inlineStart, blockStart, dollarStart };
            for (int c = 0; c < candidates.length; c++) {
                if (candidates[c] >= 0 && (start < 0 || candidates[c] < start)) {
                    start = candidates[c];
                }
            }
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            if (start == inlineStart) {
                closer = "\\)";
            } else if (start == blockStart) {
                closer = "\\]";
                display = true;
            } else {
                closer = "$$";
                display = true;
            }
            int end = text.indexOf(closer, start + (start == dollarStart ? 2 : 2));
            if (end < 0) {
                // 未闭合：原样输出剩余内容并结束，保证不丢字符。
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            String body = text.substring(start + 2, end);
            if (body.indexOf('') >= 0) {
                out.append(text, start, end + 2);
            } else {
                out.append(MATH_OPEN).append(formulas.size()).append('');
                formulas.add((display ? "1" : "0") + body);
            }
            i = end + 2;
        }
        return out.toString();
    }

    /** 把占位符还原成 KaTeX 容器；公式正文按 HTML 转义后放入，不会被当作标签解析。 */
    private static String restoreMath(String html, List<String> formulas) {
        if (formulas.isEmpty() || html.indexOf(MATH_OPEN) < 0) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length() + formulas.size() * 48);
        int i = 0;
        while (i < html.length()) {
            int start = html.indexOf(MATH_OPEN, i);
            if (start < 0) {
                out.append(html, i, html.length());
                break;
            }
            int end = html.indexOf('', start + MATH_OPEN.length());
            if (end < 0) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, start);
            String raw = html.substring(start + MATH_OPEN.length(), end);
            int index;
            try {
                index = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                index = -1;
            }
            if (index >= 0 && index < formulas.size()) {
                String entry = formulas.get(index);
                boolean display = entry.charAt(0) == '1';
                out.append("<span class=\"md-math\" data-display=\"")
                        .append(display ? "1" : "0").append("\">")
                        .append(escape(entry.substring(1)))
                        .append("</span>");
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** 输出一行表格单元格：把整行文本按 \| 切成单元格，首尾空单元格来自行首尾竖线。 */
    private static void appendTableRow(StringBuilder out, MdBlock block, String cellTag) {
        StringBuilder plain = new StringBuilder();
        if (block.runs != null) {
            for (int c = 0; c < block.runs.size(); c++) {
                MdRun run = block.runs.get(c);
                if (run != null && run.text != null) {
                    plain.append(run.text);
                }
            }
        }
        List<String> cells = splitCells(plain.toString());
        out.append("<tr>");
        for (int c = 0; c < cells.size(); c++) {
            out.append('<').append(cellTag).append('>');
            out.append(escape(cells.get(c)));
            out.append("</").append(cellTag).append('>');
        }
        out.append("</tr>");
    }

    /** 按竖线切分单元格，去掉行首尾竖线产生的空单元格，并处理 \| 转义。 */
    private static List<String> splitCells(String row) {
        List<String> cells = new java.util.ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '\\' && i + 1 < row.length() && row.charAt(i + 1) == '|') {
                current.append('|');
                i++;
            } else if (ch == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString().trim());
        // 去掉行首尾竖线造成的空单元。
        if (!cells.isEmpty() && cells.get(0).isEmpty()) {
            cells.remove(0);
        }
        if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    /** 判断是否为 Markdown 表格的对齐分隔行（每个单元格只含 - 与 :）。 */
    private static boolean isSeparatorRow(MdBlock block) {
        StringBuilder plain = new StringBuilder();
        if (block.runs != null) {
            for (int c = 0; c < block.runs.size(); c++) {
                MdRun run = block.runs.get(c);
                if (run != null && run.text != null) {
                    plain.append(run.text);
                }
            }
        }
        String row = plain.toString().trim();
        if (row.isEmpty() || row.indexOf('|') < 0) {
            return false;
        }
        List<String> cells = splitCells(row);
        if (cells.isEmpty()) {
            return false;
        }
        boolean hasDash = false;
        for (int c = 0; c < cells.size(); c++) {
            String text = cells.get(c);
            if (text.isEmpty()) {
                return false;
            }
            for (int k = 0; k < text.length(); k++) {
                char ch = text.charAt(k);
                if (ch == '-') {
                    hasDash = true;
                } else if (ch != ':') {
                    return false;
                }
            }
        }
        return hasDash;
    }

    private static void closeList(StringBuilder out, boolean inList, boolean ordered) {
        if (!inList) {
            return;
        }
        out.append(ordered ? "</ol>" : "</ul>");
    }

    private static void appendRuns(StringBuilder out, List<MdRun> runs) {
        if (runs == null) {
            return;
        }
        for (int i = 0; i < runs.size(); i++) {
            appendRun(out, runs.get(i));
        }
    }

    private static void appendRun(StringBuilder out, MdRun run) {
        if (run == null) {
            return;
        }
        String text = escape(run.text == null ? "" : run.text);
        // 硬换行在数据层用 \n 表示，这里转成 <br>。
        boolean open = false;
        if (run.href != null && isSafeHref(run.href)) {
            out.append("<a class=\"md-link\" href=\"").append(escapeAttribute(run.href))
                    .append("\" target=\"_blank\" rel=\"noopener noreferrer\">");
            open = true;
        }
        if (run.bold) {
            out.append("<strong class=\"md-strong\">");
        }
        if (run.italic) {
            out.append("<em class=\"md-em\">");
        }
        if (run.strike) {
            out.append("<del class=\"md-del\">");
        }
        if (run.code) {
            out.append("<code class=\"md-inline-code\">").append(text).append("</code>");
        } else {
            out.append(text.replace("\n", "<br>"));
        }
        if (run.strike) {
            out.append("</del>");
        }
        if (run.italic) {
            out.append("</em>");
        }
        if (run.bold) {
            out.append("</strong>");
        }
        if (open) {
            out.append("</a>");
        }
    }

    /** 只允许 http/https 与 mailto 链接。 */
    private static boolean isSafeHref(String href) {
        String value = href.trim().toLowerCase();
        return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("mailto:");
    }

    /** HTML 文本转义。 */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** HTML 属性值转义（在文本转义基础上再处理换行）。 */
    private static String escapeAttribute(String text) {
        return escape(text).replace("\n", " ");
    }
}