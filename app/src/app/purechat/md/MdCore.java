/*
 * 模块：app.purechat.md（Markdown 解析，纯数据）
 * 文件：MdCore.java
 *
 * 说明：MdCore 把一段 Markdown 文本解析成 MdBlock 列表，供聊天界面渲染。
 * 解析分两步：
 *   1) 块级解析：按行识别段落、ATX 标题、围栏代码块、列表项、引用、分隔线、表格行；
 *   2) 行内解析：对非代码块的内容识别行内代码、粗体、斜体、删除线、链接、裸链接与硬换行。
 *
 * 设计要点：
 *   - 纯函数：没有全局可变状态，输入 null 或空串返回空列表，不抛异常；
 *   - 容错：流式传输中未闭合的围栏代码块、未配对的行内标记一律按字面文本保留；
 *   - 安全：输出只是纯文本数据结构（MdRun/MdBlock），不产生任何 HTML 字符串，天然无 XSS 风险；
 *   - 性能：行内扫描带预算（见 Ctx），超长单行输入不会退化成平方级扫描；
 *   - 只依赖 java.lang 与 java.util，不使用 Android API 与任何第三方库。
 */

package app.purechat.md;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 解析入口。
 *
 * <p>本类只提供静态方法，不可实例化。解析结果是纯数据对象（{@link MdBlock} / {@link MdRun}），
 * 调用方自行决定如何渲染。所有方法都是线程安全的（无共享可变状态）。</p>
 */
public final class MdCore {

    /** 行内嵌套解析的最大深度，超过后剩余内容按字面文本处理，避免恶意输入导致栈溢出。 */
    private static final int MAX_INLINE_DEPTH = 24;

    /** 列表项缩进层级的上限，避免异常缩进产生超大 level。 */
    private static final int MAX_LIST_LEVEL = 8;

    /** 行内扫描预算的下限（以字符计），保证短文本也能完成全部识别。 */
    private static final int MIN_SCAN_BUDGET = 200000;

    /** 行内扫描预算的上限，防止超长输入消耗过多 CPU。 */
    private static final int MAX_SCAN_BUDGET = 8000000;

    /** 列表标记之后允许的最大序号位数。 */
    private static final int MAX_ORDER_DIGITS = 9;

    /** 工具类，禁止实例化。 */
    private MdCore() {
    }

    /**
     * 把 Markdown 文本解析为块列表。
     *
     * <p>必须容忍流式传输中未闭合的标记：未闭合的围栏代码块会把剩余内容整体作为代码块内容，
     * 未配对的行内标记按字面文本保留。空行用于分隔块，连续空行不会产生空块。</p>
     *
     * @param markdown Markdown 源文本，允许为 {@code null}
     * @return 块列表，永不为 {@code null}；输入为空时返回空列表
     */
    public static List<MdBlock> parse(String markdown) {
        List<MdBlock> blocks = new ArrayList<MdBlock>();
        if (markdown == null || markdown.length() == 0) {
            return blocks;
        }
        List<String> lines = splitLines(markdown);
        Ctx ctx = new Ctx(scanBudget(markdown.length()));
        parseBlocks(lines, blocks, ctx);
        return blocks;
    }

    /** 按总长度计算行内扫描预算。 */
    private static int scanBudget(int length) {
        long budget = (long) length * 8L;
        if (budget < MIN_SCAN_BUDGET) {
            budget = MIN_SCAN_BUDGET;
        }
        if (budget > MAX_SCAN_BUDGET) {
            budget = MAX_SCAN_BUDGET;
        }
        return (int) budget;
    }

    /** 把文本按 CRLF / CR / LF 三种换行风格切分为行，不保留末尾空行。 */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        int length = text.length();
        int start = 0;
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\r') {
                lines.add(text.substring(start, i));
                if (i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                start = i;
            } else if (c == '\n') {
                lines.add(text.substring(start, i));
                i++;
                start = i;
            } else {
                i++;
            }
        }
        if (start < length) {
            lines.add(text.substring(start, length));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // 块级解析
    // ------------------------------------------------------------------

    /** 逐行进行块级解析。 */
    private static void parseBlocks(List<String> lines, List<MdBlock> out, Ctx ctx) {
        int count = lines.size();
        int i = 0;
        while (i < count) {
            String line = lines.get(i);
            if (isBlank(line)) {
                i++;
                continue;
            }

            Fence fence = parseFenceOpen(line);
            if (fence != null) {
                i = consumeCodeBlock(lines, i, fence, out);
                continue;
            }

            int headingLevel = parseHeadingLevel(line);
            if (headingLevel > 0) {
                MdBlock block = new MdBlock(MdBlock.HEADING);
                block.level = headingLevel;
                String content = trimClosingHashes(line.substring(firstContentIndex(line)));
                addGroupRuns(block.runs, content, ctx);
                out.add(block);
                i++;
                continue;
            }

            if (isThematicBreak(line)) {
                out.add(new MdBlock(MdBlock.RULE));
                i++;
                continue;
            }

            if (isTableRow(line)) {
                // 表格分隔行（| --- | :--: |）本身不产生块，只有数据行才输出。
                if (isTableDelimiterRow(line)) {
                    i++;
                    continue;
                }
                MdBlock block = new MdBlock(MdBlock.TABLE_ROW);
                addGroupRuns(block.runs, line, ctx);
                out.add(block);
                i++;
                continue;
            }

            ListInfo list = parseListMarker(line);
            if (list != null) {
                MdBlock block = new MdBlock(MdBlock.LIST_ITEM);
                block.level = list.level;
                block.ordered = list.ordered;
                block.order = list.ordered ? list.order : 0;
                String content = list.contentStart < line.length() ? line.substring(list.contentStart) : "";
                addGroupRuns(block.runs, content, ctx);
                out.add(block);
                i++;
                continue;
            }

            int quoteStart = quotePrefixEnd(line);
            if (quoteStart >= 0) {
                StringBuilder joined = new StringBuilder();
                joined.append(line.substring(quoteStart));
                i++;
                while (i < count) {
                    int nextQuote = quotePrefixEnd(lines.get(i));
                    if (nextQuote < 0) {
                        break;
                    }
                    joined.append('\n').append(lines.get(i).substring(nextQuote));
                    i++;
                }
                MdBlock block = new MdBlock(MdBlock.QUOTE);
                addGroupRuns(block.runs, joined.toString(), ctx);
                out.add(block);
                continue;
            }

            StringBuilder paragraph = new StringBuilder();
            paragraph.append(line);
            i++;
            while (i < count) {
                String next = lines.get(i);
                if (isBlank(next) || startsNewBlock(next)) {
                    break;
                }
                paragraph.append('\n').append(next);
                i++;
            }
            MdBlock block = new MdBlock(MdBlock.PARAGRAPH);
            addGroupRuns(block.runs, paragraph.toString(), ctx);
            out.add(block);
        }
    }

    /** 判断该行是否会开启一个新的块（用于段落续行判断）。 */
    private static boolean startsNewBlock(String line) {
        return parseFenceOpen(line) != null
                || parseHeadingLevel(line) > 0
                || isThematicBreak(line)
                || isTableRow(line)
                || parseListMarker(line) != null
                || quotePrefixEnd(line) >= 0;
    }

    /** 消费一个围栏代码块（含未闭合情况）：返回下一待处理行的下标。 */
    private static int consumeCodeBlock(List<String> lines, int start, Fence fence, List<MdBlock> out) {
        int count = lines.size();
        StringBuilder content = new StringBuilder();
        int i = start + 1;
        while (i < count) {
            String line = lines.get(i);
            if (isFenceClose(line, fence)) {
                i++;
                break;
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(line);
            i++;
        }
        MdBlock block = new MdBlock(MdBlock.CODE);
        block.lang = codeLang(fence.info);
        block.text = content.toString();
        out.add(block);
        return i;
    }

    /** 解析围栏起始行；不是围栏行时返回 {@code null}。 */
    private static Fence parseFenceOpen(String line) {
        if (line == null) {
            return null;
        }
        int length = line.length();
        int indent = 0;
        while (indent < length && indent < 4 && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        if (indent >= length) {
            return null;
        }
        char marker = line.charAt(indent);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int i = indent;
        while (i < length && line.charAt(i) == marker) {
            i++;
        }
        int runLength = i - indent;
        if (runLength < 3) {
            return null;
        }
        String info = line.substring(i).trim();
        if (marker == '`' && info.indexOf('`') >= 0) {
            return null;
        }
        return new Fence(marker, runLength, info);
    }

    /** 判断某行是否为该围栏的结束行：同种字符、数量不少于起始围栏、其后只有空白。 */
    private static boolean isFenceClose(String line, Fence fence) {
        if (line == null) {
            return false;
        }
        int length = line.length();
        int indent = 0;
        while (indent < length && indent < 4 && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        if (indent >= length || line.charAt(indent) != fence.marker) {
            return false;
        }
        int i = indent;
        while (i < length && line.charAt(i) == fence.marker) {
            i++;
        }
        if (i - indent < fence.length) {
            return false;
        }
        while (i < length) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
            i++;
        }
        return true;
    }

    /** 从围栏信息串中取语言标记；无语言标记时返回 {@code null}。 */
    private static String codeLang(String info) {
        if (info == null) {
            return null;
        }
        String trimmed = info.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        int i = 0;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t' || c == '{' || c == ',') {
                break;
            }
            i++;
        }
        String lang = trimmed.substring(0, i).trim();
        return lang.length() == 0 ? null : lang;
    }

    /** 判断是否为 ATX 标题行，返回标题级别 1 到 6；不是标题返回 0。 */
    private static int parseHeadingLevel(String line) {
        if (line == null) {
            return 0;
        }
        int length = line.length();
        int indent = 0;
        while (indent < length && indent < 4 && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        int i = indent;
        int hashes = 0;
        while (i < length && line.charAt(i) == '#' && hashes <= 6) {
            hashes++;
            i++;
        }
        if (hashes < 1 || hashes > 6) {
            return 0;
        }
        if (i < length && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
            return 0;
        }
        return hashes;
    }

    /** 返回标题行中正文的起始下标（跳过缩进与井号）。 */
    private static int firstContentIndex(String line) {
        int length = line.length();
        int i = 0;
        while (i < length && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        while (i < length && line.charAt(i) == '#') {
            i++;
        }
        while (i < length && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** 去掉 ATX 标题末尾可选的收尾井号（例如标题后跟 ##）。 */
    private static String trimClosingHashes(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        int hashStart = end;
        while (hashStart > 0 && text.charAt(hashStart - 1) == '#') {
            hashStart--;
        }
        if (hashStart < end && (hashStart == 0 || text.charAt(hashStart - 1) == ' ' || text.charAt(hashStart - 1) == '\t')) {
            return text.substring(0, hashStart).trim();
        }
        return text.substring(0, end);
    }

    /** 判断是否为分隔线：三个及以上相同字符（减号、星号、下划线）组成，允许中间有空白。 */
    private static boolean isThematicBreak(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        char marker = trimmed.charAt(0);
        if (marker != '-' && marker != '*' && marker != '_') {
            return false;
        }
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == marker) {
                count++;
            } else if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return count >= 3;
    }

    /** 判断是否为表格行（以竖线开头）。 */
    private static boolean isTableRow(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.length() >= 2 && trimmed.charAt(0) == '|';
    }

    /** 判断是否为表格分隔行，例如竖线包围的减号与冒号。 */
    private static boolean isTableDelimiterRow(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.length() < 2) {
            return false;
        }
        if (trimmed.charAt(0) != '|' && trimmed.charAt(0) != ':') {
            return false;
        }
        boolean hasDash = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-') {
                hasDash = true;
            } else if (c != '|' && c != ':' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return hasDash;
    }

    /** 解析列表标记；不是列表项时返回 {@code null}。 */
    private static ListInfo parseListMarker(String line) {
        if (line == null) {
            return null;
        }
        int length = line.length();
        int columns = 0;
        int i = 0;
        while (i < length) {
            char c = line.charAt(i);
            if (c == ' ') {
                columns++;
                i++;
            } else if (c == '\t') {
                columns += 4 - (columns % 4);
                i++;
            } else {
                break;
            }
        }
        if (i >= length) {
            return null;
        }
        int level = columns / 2;
        if (level > MAX_LIST_LEVEL) {
            level = MAX_LIST_LEVEL;
        }
        char marker = line.charAt(i);
        if (marker == '-' || marker == '*' || marker == '+') {
            if (i + 1 == length) {
                return new ListInfo(false, 0, level, i + 1);
            }
            char next = line.charAt(i + 1);
            if (next == ' ' || next == '\t') {
                return new ListInfo(false, 0, level, i + 2);
            }
            return null;
        }
        if (marker >= '0' && marker <= '9') {
            int j = i;
            while (j < length && line.charAt(j) >= '0' && line.charAt(j) <= '9' && j - i < MAX_ORDER_DIGITS) {
                j++;
            }
            if (j >= length) {
                return null;
            }
            char separator = line.charAt(j);
            if (separator != '.' && separator != ')') {
                return null;
            }
            if (j + 1 < length) {
                char next = line.charAt(j + 1);
                if (next != ' ' && next != '\t') {
                    return null;
                }
            }
            int order = parseIntSafe(line.substring(i, j), 1);
            return new ListInfo(true, order, level, j + 1);
        }
        return null;
    }

    /** 返回引用行内容首字符的下标；不是引用行返回 -1。 */
    private static int quotePrefixEnd(String line) {
        if (line == null) {
            return -1;
        }
        int length = line.length();
        int i = 0;
        int spaces = 0;
        while (i < length && spaces < 4 && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
            spaces++;
        }
        if (i >= length || line.charAt(i) != '>') {
            return -1;
        }
        i++;
        if (i < length && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** 判断一行是否为空行（只含空格或制表符）。 */
    private static boolean isBlank(String line) {
        if (line == null) {
            return true;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** 安全解析十进制整数，失败时返回默认值。 */
    private static int parseIntSafe(String text, int def) {
        if (text == null || text.length() == 0) {
            return def;
        }
        long value = 0L;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return def;
            }
            value = value * 10L + (c - '0');
            if (value > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) value;
    }

    // ------------------------------------------------------------------
    // 块内分组与行内解析
    // ------------------------------------------------------------------

    /** 把块文本按换行切成组，组之间插入硬换行片段。 */
    private static void addGroupRuns(List<MdRun> out, String text, Ctx ctx) {
        if (text == null || text.length() == 0) {
            return;
        }
        int length = text.length();
        int start = 0;
        boolean pendingBreak = false;
        while (start <= length) {
            int end = start;
            while (end < length && text.charAt(end) != '\n') {
                end++;
            }
            String item = trimOuterSpaces(text.substring(start, end));
            boolean blank = item.length() == 0;
            if (pendingBreak && !blank) {
                out.add(new MdRun("\n"));
            }
            if (!blank) {
                inline(item, out, 0, ctx);
            }
            pendingBreak = true;
            if (end >= length) {
                break;
            }
            start = end + 1;
        }
    }

    /**
     * 行内解析：把一段文本转成带样式的片段列表。
     *
     * @param text  待解析文本
     * @param out   输出列表
     * @param depth 当前嵌套深度
     * @param ctx   扫描预算上下文
     */
    private static void inline(String text, List<MdRun> out, int depth, Ctx ctx) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (depth > MAX_INLINE_DEPTH) {
            appendPlain(out, text, depth, ctx);
            return;
        }
        int length = text.length();
        StringBuilder buffer = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            if (!ctx.canScan()) {
                buffer.append(text, i, length);
                i = length;
                break;
            }
            char c = text.charAt(i);

            if (c == '`') {
                int runEnd = i;
                while (runEnd < length && text.charAt(runEnd) == '`') {
                    runEnd++;
                }
                int ticks = runEnd - i;
                int close = findCodeClose(text, runEnd, ticks, ctx);
                if (close > runEnd) {
                    flush(buffer, out, depth, ctx);
                    MdRun run = new MdRun(text.substring(runEnd, close));
                    run.code = true;
                    out.add(run);
                    i = close + ticks;
                    continue;
                }
                buffer.append(text, i, runEnd);
                i = runEnd;
                continue;
            }

            if (c == '[') {
                int labelEnd = findLinkLabelEnd(text, i + 1, ctx);
                int parenEnd = labelEnd >= 0 && labelEnd + 1 < length && text.charAt(labelEnd + 1) == '('
                        ? findParenEnd(text, labelEnd + 2, ctx)
                        : -1;
                if (parenEnd > labelEnd) {
                    List<MdRun> inner = new ArrayList<MdRun>();
                    inline(text.substring(i + 1, labelEnd), inner, depth + 1, ctx);
                    if (inner.isEmpty()) {
                        buffer.append(text, i, parenEnd + 1);
                        i = parenEnd + 1;
                        continue;
                    }
                    String href = cleanHref(text.substring(labelEnd + 2, parenEnd));
                    flush(buffer, out, depth, ctx);
                    for (int k = 0; k < inner.size(); k++) {
                        inner.get(k).href = href;
                    }
                    out.addAll(inner);
                    i = parenEnd + 1;
                    continue;
                }
                buffer.append(c);
                i++;
                continue;
            }

            if (c == '*' || c == '_' || c == '~') {
                int runEnd = i;
                while (runEnd < length && text.charAt(runEnd) == c) {
                    runEnd++;
                }
                int runLength = runEnd - i;
                if (c == '~' && runLength < 2) {
                    buffer.append(text, i, runEnd);
                    i = runEnd;
                    continue;
                }
                if (depth + 1 > MAX_INLINE_DEPTH) {
                    buffer.append(text, i, runEnd);
                    i = runEnd;
                    continue;
                }
                if (c == '_' && i > 0 && isWordChar(text.charAt(i - 1))) {
                    // 词内下划线（如 snake_case）不作为强调起始标记。
                    buffer.append(text, i, runEnd);
                    i = runEnd;
                    continue;
                }
                int used = c == '~' ? 2 : (runLength >= 3 ? 3 : runLength);
                int close = findClosingDelimiter(text, runEnd, c, used, ctx);
                if (close > runEnd) {
                    List<MdRun> inner = new ArrayList<MdRun>();
                    inline(text.substring(runEnd, close), inner, depth + 1, ctx);
                    if (!inner.isEmpty()) {
                        // 作用范围首尾的空白不属于强调内容（a **b** c 里的空格不加粗）。
                        MdRun firstRun = inner.get(0);
                        MdRun lastRun = inner.get(inner.size() - 1);
                        MdRun pre = firstRun.copy();
                        MdRun post = lastRun.copy();
                        pre.text = takeLeadingSpaces(firstRun);
                        post.text = takeTrailingSpaces(lastRun);
                        pre.href = null;
                        post.href = null;
                        removeEmptyRuns(inner);
                        if (!inner.isEmpty()) {
                            flush(buffer, out, depth, ctx);
                            boolean isStrike = c == '~';
                            boolean isBold = !isStrike && used >= 2;
                            boolean isItalic = !isStrike && (used == 1 || used == 3);
                            if (pre.text.length() > 0) {
                                appendPlain(out, pre.text, depth, ctx);
                            }
                            for (int k = 0; k < inner.size(); k++) {
                                MdRun run = inner.get(k);
                                if (isStrike) {
                                    run.strike = true;
                                }
                                if (isBold) {
                                    run.bold = true;
                                }
                                if (isItalic) {
                                    run.italic = true;
                                }
                                out.add(run);
                            }
                            if (post.text.length() > 0) {
                                appendPlain(out, post.text, depth, ctx);
                            }
                            i = close + used;
                            continue;
                        }
                    }
                }
                buffer.append(text, i, runEnd);
                i = runEnd;
                continue;
            }

            buffer.append(c);
            i++;
        }
        flush(buffer, out, depth, ctx);
    }

    /** 把缓冲区中的纯文本落盘为片段（做链接识别）。 */
    private static void flush(StringBuilder buffer, List<MdRun> out, int depth, Ctx ctx) {
        if (buffer.length() == 0) {
            return;
        }
        String text = buffer.toString();
        buffer.setLength(0);
        appendPlain(out, text, depth, ctx);
    }

    /**
     * 处理一段纯文本：按换行与 br 标签拆分为多段并识别裸链接后追加到输出。
     * 段内空白原样保留（否则 {bold} 内部的行内代码两侧空格会被吃掉），
     * 只有紧邻换行的空白会被裁掉。
     */
    private static void appendPlain(List<MdRun> out, String text, int depth, Ctx ctx) {
        if (text.length() == 0) {
            return;
        }
        List<Tok> tokens = new ArrayList<Tok>();
        int length = text.length();
        int start = 0;
        int i = 0;
        boolean trimLeft = false;
        while (i < length) {
            char c = text.charAt(i);
            int brLength = c == '<' ? brTagLength(text, i) : 0;
            if (c == '\n' || brLength > 0) {
                addRawText(tokens, text, start, i, trimLeft, true);
                tokens.add(new Tok(new MdRun("\n"), null, false, true));
                i += brLength > 0 ? brLength : 1;
                start = i;
                trimLeft = true;
                continue;
            }
            i++;
        }
        addRawText(tokens, text, start, length, trimLeft, false);
        buildChains(tokens, out, depth, ctx);
    }

    /** 把一段待识别的纯文本加入 token 列表，可按需裁掉左侧（紧邻换行）与右侧空白。 */
    private static void addRawText(List<Tok> out, String text, int from, int to, boolean trimLeft, boolean trimRight) {
        if (to <= from) {
            return;
        }
        int startIndex = from;
        int endIndex = to;
        if (trimLeft) {
            while (startIndex < endIndex && (text.charAt(startIndex) == ' ' || text.charAt(startIndex) == '\t')) {
                startIndex++;
            }
        }
        if (trimRight) {
            while (endIndex > startIndex && (text.charAt(endIndex - 1) == ' ' || text.charAt(endIndex - 1) == '\t')) {
                endIndex--;
            }
        }
        if (endIndex <= startIndex) {
            return;
        }
        out.add(new Tok(new MdRun(text.substring(startIndex, endIndex)), null, true, false));
    }

    /** 判断位置 {@code i} 是否是 br 换行标签（br、br/、br /，忽略大小写），返回标签长度；否则返回 0。 */
    private static int brTagLength(String text, int i) {
        int length = text.length();
        if (i >= length || text.charAt(i) != '<') {
            return 0;
        }
        int j = i + 1;
        if (!regionMatchesIgnoreCase(text, j, "br")) {
            return 0;
        }
        j += 2;
        while (j < length && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
            j++;
        }
        if (j < length && text.charAt(j) == '/') {
            j++;
        }
        if (j < length && text.charAt(j) == '>') {
            return j + 1 - i;
        }
        return 0;
    }

    /** 忽略大小写判断 {@code text} 从 {@code offset} 起是否匹配 {@code prefix}。 */
    private static boolean regionMatchesIgnoreCase(String text, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char a = text.charAt(offset + i);
            char b = prefix.charAt(i);
            if (a == b) {
                continue;
            }
            char lowerA = (a >= 'A' && a <= 'Z') ? (char) (a + 32) : a;
            char lowerB = (b >= 'A' && b <= 'Z') ? (char) (b + 32) : b;
            if (lowerA != lowerB) {
                return false;
            }
        }
        return true;
    }

    /** 判断字符是否为可用于“词内下划线”判断的单词字符。 */
    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** 取出片段开头的连续空白并从片段中移除，返回取出的空白（无则空串）。 */
    private static String takeLeadingSpaces(MdRun run) {
        String text = run.text;
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        if (i == 0) {
            return "";
        }
        run.text = text.substring(i);
        return text.substring(0, i);
    }

    /** 取出片段结尾的连续空白并从片段中移除，返回取出的空白（无则空串）。 */
    private static String takeTrailingSpaces(MdRun run) {
        String text = run.text;
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        if (end == text.length()) {
            return "";
        }
        run.text = text.substring(0, end);
        return text.substring(end);
    }

    /** 移除文本已空的片段。 */
    private static void removeEmptyRuns(List<MdRun> runs) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).text.length() == 0) {
                runs.remove(i);
            }
        }
    }

    /**
     * 合并 token 成最终片段列表：先对纯文本做链接识别，
     * 再把样式相同、链接相同且不是硬换行的相邻片段合并成一个片段。
     */
    private static void buildChains(List<Tok> tokens, List<MdRun> out, int depth, Ctx ctx) {
        List<Tok> resolved = new ArrayList<Tok>(tokens.size() + 4);
        for (int i = 0; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            if (tok.raw) {
                parseLinks(tok.run.text, resolved, depth, ctx);
            } else {
                MdRun run = tok.run.copy();
                run.href = null;
                resolved.add(new Tok(run, tok.href, false, tok.isBreak));
            }
        }
        int size = resolved.size();
        int i = 0;
        while (i < size) {
            Tok first = resolved.get(i);
            int j = i + 1;
            StringBuilder joined = null;
            if (!first.isBreak) {
                joined = new StringBuilder(first.run.text);
                while (j < size && mergeable(first, resolved.get(j))) {
                    joined.append(resolved.get(j).run.text);
                    j++;
                }
            }
            String text = joined == null ? first.run.text : joined.toString();
            if (text.length() > 0) {
                MdRun run = new MdRun(text);
                run.bold = first.run.bold;
                run.italic = first.run.italic;
                run.code = first.run.code;
                run.strike = first.run.strike;
                run.href = first.href;
                out.add(run);
            }
            i = j;
        }
    }

    /** 判断两个 token 是否可以合并成同一个片段。 */
    private static boolean mergeable(Tok first, Tok other) {
        if (other.isBreak || first.href != null || other.href != null) {
            return false;
        }
        MdRun a = first.run;
        MdRun b = other.run;
        return a.bold == b.bold && a.italic == b.italic && a.code == b.code && a.strike == b.strike;
    }

    /** 在纯文本中识别链接语法与裸链接，其余文本原样成为片段。 */
    private static void parseLinks(String text, List<Tok> out, int depth, Ctx ctx) {
        int length = text.length();
        int i = 0;
        int plainStart = 0;
        while (i < length) {
            if (!ctx.canScan()) {
                break;
            }
            if (text.charAt(i) == '[') {
                int labelEnd = findLinkLabelEnd(text, i + 1, ctx);
                int parenEnd = labelEnd >= 0 && labelEnd + 1 < length && text.charAt(labelEnd + 1) == '('
                        ? findParenEnd(text, labelEnd + 2, ctx)
                        : -1;
                if (parenEnd > labelEnd) {
                    addLiteral(out, text, plainStart, i);
                    String href = cleanHref(text.substring(labelEnd + 2, parenEnd));
                    List<MdRun> inner = new ArrayList<MdRun>();
                    inline(text.substring(i + 1, labelEnd), inner, depth + 1, ctx);
                    for (int k = 0; k < inner.size(); k++) {
                        MdRun run = inner.get(k);
                        run.href = href;
                        out.add(new Tok(run, href, false, false));
                    }
                    i = parenEnd + 1;
                    plainStart = i;
                    continue;
                }
                i++;
                continue;
            }
            int urlEnd = bareLinkEnd(text, i, ctx);
            if (urlEnd > i) {
                addLiteral(out, text, plainStart, i);
                String url = text.substring(i, urlEnd);
                MdRun run = new MdRun(url);
                run.href = url;
                out.add(new Tok(run, url, false, false));
                i = urlEnd;
                plainStart = i;
                continue;
            }
            i++;
        }
        addLiteral(out, text, plainStart, length);
    }

    /**
     * 规整链接目标：裁掉首尾空白，并丢弃紧随其后的可选标题部分（例如 [t](url "title") 中的引号标题）。
     *
     * @param raw 圆括号内的原始文本
     * @return 规整后的链接地址，可能为空串
     */
    private static String cleanHref(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t') {
                return trimmed.substring(0, i);
            }
        }
        return trimmed;
    }

    /** 把一段字面文本加入 token 列表（空串忽略）。 */
    private static void addLiteral(List<Tok> out, String text, int from, int to) {
        if (to <= from) {
            return;
        }
        String literal = text.substring(from, to);
        if (literal.length() == 0) {
            return;
        }
        out.add(new Tok(new MdRun(literal), null, false, false));
    }

    // ------------------------------------------------------------------
    // 行内扫描辅助
    // ------------------------------------------------------------------

    /** 找到与起始反引号等长的闭合反引号串，返回其起始下标；找不到返回 -1。 */
    private static int findCodeClose(String text, int from, int ticks, Ctx ctx) {
        int length = text.length();
        int i = from;
        while (i < length) {
            if (!ctx.canScan()) {
                return -1;
            }
            if (text.charAt(i) == '`') {
                int j = i;
                while (j < length && text.charAt(j) == '`') {
                    j++;
                }
                if (j - i == ticks) {
                    return i;
                }
                i = j;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** 找到与左方括号配对的右方括号下标；找不到返回 -1。 */
    private static int findLinkLabelEnd(String text, int from, Ctx ctx) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            if (!ctx.canScan()) {
                return -1;
            }
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** 找到与左圆括号配对的右圆括号下标；找不到返回 -1。 */
    private static int findParenEnd(String text, int from, Ctx ctx) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            if (!ctx.canScan()) {
                return -1;
            }
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /**
     * 找到闭合标记串的起始下标；找不到返回 -1。
     *
     * <p>闭合串长度只需不短于 {@code needed}：超出的部分留给后续解析（例如 **ab****ab** 中的
     * 中间四个星号会闭合前一段并开启下一段），因此标记字符不会被吞进正文，也不会丢失。</p>
     *
     * @param text   待扫描文本
     * @param from   起始下标
     * @param marker 标记字符
     * @param needed 需要的标记字符个数
     * @param ctx    扫描预算上下文
     * @return 闭合标记串的起始下标，找不到返回 -1
     */
    private static int findClosingDelimiter(String text, int from, char marker, int needed, Ctx ctx) {
        int length = text.length();
        int i = from;
        while (i < length) {
            if (!ctx.canScan()) {
                return -1;
            }
            if (text.charAt(i) != marker) {
                i++;
                continue;
            }
            int j = i;
            while (j < length && text.charAt(j) == marker) {
                j++;
            }
            if (j - i >= needed) {
                return i;
            }
            i = j;
        }
        return -1;
    }

    /** 判断位置 {@code i} 是否为一个裸链接（http/https）的开头。 */
    private static boolean isBareLinkStart(String text, int i) {
        if (i > 0) {
            char prev = text.charAt(i - 1);
            boolean wordChar = (prev >= 'a' && prev <= 'z') || (prev >= 'A' && prev <= 'Z')
                    || (prev >= '0' && prev <= '9') || prev == '_' || prev == '/';
            if (wordChar) {
                return false;
            }
        }
        return startsWithIgnoreCase(text, i, "https://") || startsWithIgnoreCase(text, i, "http://");
    }

    /** 从位置 {@code i} 开始匹配裸链接，返回结束下标（不含）；不是裸链接返回 -1。 */
    private static int bareLinkEnd(String text, int i, Ctx ctx) {
        if (!isBareLinkStart(text, i)) {
            return -1;
        }
        int length = text.length();
        int j = i;
        while (j < length && isUrlChar(text.charAt(j))) {
            if (!ctx.canScan()) {
                break;
            }
            j++;
        }
        while (j > i && isUrlTrailChar(text.charAt(j - 1))) {
            j--;
        }
        if (j - i < 8) {
            return -1;
        }
        return j;
    }

    /** 判断字符能否出现在裸链接内部（限制在可打印 ASCII 内，避免吞掉中文标点）。 */
    private static boolean isUrlChar(char c) {
        if (c <= 0x20 || c >= 0x7F) {
            return false;
        }
        if (c == '"' || c == '<' || c == '>') {
            return false;
        }
        return true;
    }

    /** 判断字符是否为裸链接需要剥离的尾部标点。 */
    private static boolean isUrlTrailChar(char c) {
        return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
                || c == ')' || c == ']' || c == '}' || c == '\'' || c == '*';
    }

    /** 忽略大小写的前缀判断。 */
    private static boolean startsWithIgnoreCase(String text, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char a = text.charAt(offset + i);
            char b = prefix.charAt(i);
            if (a == b) {
                continue;
            }
            char lowerA = (a >= 'A' && a <= 'Z') ? (char) (a + 32) : a;
            char lowerB = (b >= 'A' && b <= 'Z') ? (char) (b + 32) : b;
            if (lowerA != lowerB) {
                return false;
            }
        }
        return true;
    }

    /** 去掉首尾空白（空格、制表符、回车、换行）。 */
    private static String trimOuterSpaces(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && isTrimSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** 判断是否为需要裁掉的空白字符。 */
    private static boolean isTrimSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

    /** 行内扫描预算：限制超长输入下的总扫描字符数，超限后剩余内容按字面处理。 */
    private static final class Ctx {

        /** 剩余可扫描字符数。 */
        private int remaining;

        Ctx(int budget) {
            this.remaining = budget;
        }

        /** 消费一次扫描额度，额度耗尽时返回 {@code false}。 */
        boolean canScan() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    /** 围栏代码块的起始信息。 */
    private static final class Fence {

        /** 围栏字符：反引号或波浪号。 */
        final char marker;

        /** 起始围栏的字符个数。 */
        final int length;

        /** 围栏之后的信息串（可能为空串）。 */
        final String info;

        Fence(char marker, int length, String info) {
            this.marker = marker;
            this.length = length;
            this.info = info;
        }
    }

    /** 列表标记解析结果。 */
    private static final class ListInfo {

        /** 是否有序列表。 */
        final boolean ordered;

        /** 有序列表的序号，无序列表为 0。 */
        final int order;

        /** 缩进层级，从 0 开始。 */
        final int level;

        /** 列表项正文的起始下标。 */
        final int contentStart;

        ListInfo(boolean ordered, int order, int level, int contentStart) {
            this.ordered = ordered;
            this.order = order;
            this.level = level;
            this.contentStart = contentStart;
        }
    }

    /** 行内中间结果 token：已解析的片段，或等待做链接识别的纯文本。 */
    private static final class Tok {

        /** 片段数据。 */
        final MdRun run;

        /** 链接地址，{@code null} 表示普通文本。 */
        final String href;

        /** 是否还需要做链接识别。 */
        final boolean raw;

        /** 是否为硬换行占位，不参与相邻合并。 */
        final boolean isBreak;

        Tok(MdRun run, String href, boolean raw, boolean isBreak) {
            this.run = run;
            this.href = href;
            this.raw = raw;
            this.isBreak = isBreak;
        }
    }
}
