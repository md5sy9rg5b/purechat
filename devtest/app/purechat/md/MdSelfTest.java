/*
 * 模块：app.purechat.md（Markdown 解析，纯数据）—— 自测
 * 文件：MdSelfTest.java
 *
 * 说明：位于 devtest 下、与模块同包名（app.purechat.md），不打包进 APK。
 * 运行方式：
 *   javac --release 8 -encoding UTF-8 -d out\selftest app\src\app\purechat\md\*.java devtest\app\purechat\md\MdSelfTest.java
 *   java -cp out\selftest app.purechat.md.MdSelfTest
 * 覆盖：空串、未闭合标记、超长输入、嵌套标记、CRLF/CR/LF 换行，以及各块级与行内语法。
 */

package app.purechat.md;

import java.util.List;

/**
 * 模块 A（Markdown 解析）的自测入口。
 *
 * <p>使用简单断言逐项校验 {@link MdCore#parse(String)} 的行为，打印 PASS / FAIL，
 * 最后输出一行 SUMMARY pass=N fail=M；存在失败时以退出码 1 结束。</p>
 */
public final class MdSelfTest {

    /** 通过计数。 */
    private static int pass;

    /** 失败计数。 */
    private static int fail;

    /** 工具类，禁止实例化。 */
    private MdSelfTest() {
    }

    /**
     * 自测主入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        testEmptyInput();
        testBasicBlocks();
        testHeadings();
        testCodeBlocks();
        testInlineStyles();
        testNesting();
        testLinks();
        testLists();
        testQuoteRuleAndTable();
        testLineEndings();
        testHardBreaks();
        testUnclosedMarkers();
        testMalformedInput();
        testOverlongMarkerPreservation();
        testHugeInput();
        testDataClassContract();

        System.out.println("SUMMARY pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 空串、null、纯空白。 */
    private static void testEmptyInput() {
        check("empty.null", MdCore.parse(null).isEmpty(), "null 应返回空列表");
        check("empty.blank", MdCore.parse("").isEmpty(), "空串应返回空列表");
        check("empty.spaces", MdCore.parse("   \t  ").isEmpty(), "纯空白应返回空列表");
        check("empty.newlines", MdCore.parse("\n\n\r\n").isEmpty(), "纯换行应返回空列表");
        check("empty.resultNotNull", MdCore.parse((String) null) != null, "返回值不能为 null");
    }

    /** 段落与空行分块。 */
    private static void testBasicBlocks() {
        List<MdBlock> one = MdCore.parse("hello world");
        check("para.count", one.size() == 1, "单段应只有 1 块");
        check("para.type", one.get(0).type == MdBlock.PARAGRAPH, "应为段落");
        check("para.text", "hello world".equals(runsText(one.get(0))), "段落文本应为 hello world");
        check("para.plainFlag", one.get(0).runs.get(0).isPlain(), "普通文本不应带样式");

        List<MdBlock> two = MdCore.parse("a\n\nb");
        check("para.split", two.size() == 2, "空行应分成 2 块");
        check("para.split.first", "a".equals(runsText(two.get(0))), "第一块为 a");
        check("para.split.second", "b".equals(runsText(two.get(1))), "第二块为 b");

        List<MdBlock> multi = MdCore.parse("l1\nl2");
        check("para.multiline.count", multi.size() == 1, "无空行应合为 1 块");
        check("para.multiline.nl", "l1\nl2".equals(runsText(multi.get(0))), "行间应保留换行片段");
    }

    /** ATX 标题。 */
    private static void testHeadings() {
        for (int level = 1; level <= 6; level++) {
            String md = repeat('#', level) + " title";
            List<MdBlock> blocks = MdCore.parse(md);
            check("heading.l" + level + ".count", blocks.size() == 1, "标题应为 1 块");
            check("heading.l" + level + ".type", blocks.get(0).type == MdBlock.HEADING, "应为标题块");
            check("heading.l" + level + ".level", blocks.get(0).level == level, "级别应为 " + level);
            check("heading.l" + level + ".text", "title".equals(runsText(blocks.get(0))), "标题文本应为 title");
        }
        List<MdBlock> closed = MdCore.parse("## t ##");
        check("heading.closing", "t".equals(runsText(closed.get(0))), "收尾井号应被去掉");
        List<MdBlock> noSpace = MdCore.parse("#nospace");
        check("heading.nospace", noSpace.get(0).type == MdBlock.PARAGRAPH, "# 后无空格应视为段落");
        List<MdBlock> seven = MdCore.parse("####### seven");
        check("heading.seven", seven.get(0).type == MdBlock.PARAGRAPH, "7 个 # 应视为段落");
    }

    /** 围栏代码块，含 ~~~ 与未闭合。 */
    private static void testCodeBlocks() {
        List<MdBlock> blocks = MdCore.parse("```java\nint a = 1;\nint b = 2;\n```");
        check("code.count", blocks.size() == 1, "代码块应为 1 块");
        check("code.type", blocks.get(0).type == MdBlock.CODE, "应为代码块");
        check("code.lang", "java".equals(blocks.get(0).lang), "语言应为 java");
        check("code.text", "int a = 1;\nint b = 2;".equals(blocks.get(0).text), "代码内容应完整");
        check("code.runsEmpty", blocks.get(0).runs.isEmpty(), "代码块不应有富文本");

        List<MdBlock> noLang = MdCore.parse("```\nx\n```");
        check("code.nolang", noLang.get(0).lang == null, "无语言标记应为 null");
        check("code.nolang.text", "x".equals(noLang.get(0).text), "内容应为 x");

        List<MdBlock> tilde = MdCore.parse("~~~py\nprint(1)\n~~~");
        check("code.tilde", tilde.get(0).type == MdBlock.CODE, "~~~ 应是代码块");
        check("code.tilde.lang", "py".equals(tilde.get(0).lang), "语言应为 py");

        List<MdBlock> noInline = MdCore.parse("```\n**not bold** [x](y)\n```");
        check("code.noinline", "**not bold** [x](y)".equals(noInline.get(0).text), "代码块内不做行内解析");

        List<MdBlock> longFence = MdCore.parse("````\na `` b\n````");
        check("code.longFence", "a `` b".equals(longFence.get(0).text), "四反引号围栏内可含三反引号");

        List<MdBlock> after = MdCore.parse("```\nc\n```\nafter");
        check("code.after", after.size() == 2 && after.get(1).type == MdBlock.PARAGRAPH, "围栏后应继续解析段落");
    }

    /** 行内样式。 */
    private static void testInlineStyles() {
        assertRun("bold.star", "a **b** c", 1, "b", true, false, false, false);
        assertRun("bold.underscore", "a __b__ c", 1, "b", true, false, false, false);
        assertRun("bold.spaceKept", "a **b** c", 0, "a ", false, false, false, false);
        assertRun("italic.star", "a *b* c", 1, "b", false, true, false, false);
        assertRun("italic.underscore", "a _b_ c", 1, "b", false, true, false, false);
        assertRun("strike", "a ~~b~~ c", 1, "b", false, false, false, true);

        List<MdBlock> code = MdCore.parse("a `b` c");
        check("inline.code.count", code.get(0).runs.size() == 3, "行内代码应把文本切成 3 段");
        check("inline.code.style", code.get(0).runs.get(1).code, "第二段应为行内代码");
        check("inline.code.text", "b".equals(code.get(0).runs.get(1).text), "代码文本应为 b");

        List<MdBlock> dbl = MdCore.parse("``a `b` c``");
        check("inline.code.nested", dbl.get(0).runs.size() == 1, "双反引号应整体视作一段代码");
        check("inline.code.nested.text", "a `b` c".equals(dbl.get(0).runs.get(0).text), "双反引号内允许单反引号");
        check("inline.code.nested.flag", dbl.get(0).runs.get(0).code, "应为代码样式");

        List<MdBlock> plain = MdCore.parse("a **b");
        check("inline.unclosedBold", "a **b".equals(runsText(plain.get(0))), "未闭合粗体应保留字面");
        check("inline.unclosedBold.style", !plain.get(0).runs.get(0).bold, "未闭合粗体不应加粗");

        List<MdBlock> snake = MdCore.parse("a_b_c");
        check("inline.intrawordUnderscore", "a_b_c".equals(runsText(snake.get(0))), "词内下划线不应触发斜体");
        check("inline.intrawordUnderscore.style", !snake.get(0).runs.get(0).italic, "词内下划线不应斜体");

        List<MdBlock> html = MdCore.parse("<b>x</b> & y");
        check("inline.htmlLiteral", "<b>x</b> & y".equals(runsText(html.get(0))), "HTML 标签按字面保留");
        check("inline.htmlLiteral.runs", html.get(0).runs.size() == 1, "HTML 标签不应拆分片段");
    }

    /** 嵌套标记。 */
    private static void testNesting() {
        List<MdBlock> b1 = MdCore.parse("**a `b` c**");
        check("nest.boldCode.count", b1.get(0).runs.size() == 3, "粗体内代码应切成 3 段");
        check("nest.boldCode.code", b1.get(0).runs.get(1).code && b1.get(0).runs.get(1).bold, "代码段应同时是粗体");
        check("nest.boldCode.text", "b".equals(b1.get(0).runs.get(1).text), "代码段文本应为 b");

        List<MdBlock> b2 = MdCore.parse("**a [t](u) c**");
        MdRun linkRun = null;
        for (int i = 0; i < b2.get(0).runs.size(); i++) {
            if (b2.get(0).runs.get(i).href != null) {
                linkRun = b2.get(0).runs.get(i);
            }
        }
        check("nest.boldLink.exists", linkRun != null, "粗体内链接应被识别");
        check("nest.boldLink.bold", linkRun != null && linkRun.bold, "链接应继承粗体");
        check("nest.boldLink.href", linkRun != null && "u".equals(linkRun.href), "链接地址应为 u");

        List<MdBlock> b3 = MdCore.parse("**_x_**");
        check("nest.italicInBold", b3.get(0).runs.get(0).bold && b3.get(0).runs.get(0).italic, "应同时为粗体与斜体");

        List<MdBlock> b4 = MdCore.parse("***x***");
        check("nest.triple", b4.get(0).runs.get(0).bold && b4.get(0).runs.get(0).italic, "三星号应粗斜体");

        List<MdBlock> b5 = MdCore.parse("**a *b* c**");
        boolean allBold = true;
        boolean hasItalic = false;
        for (int i = 0; i < b5.get(0).runs.size(); i++) {
            MdRun run = b5.get(0).runs.get(i);
            allBold = allBold && run.bold;
            hasItalic = hasItalic || run.italic;
        }
        check("nest.boldItalic.allBold", allBold, "粗体内所有片段都应带粗体");
        check("nest.boldItalic.hasItalic", hasItalic, "内层斜体应保留");

        List<MdBlock> b6 = MdCore.parse("~~a **b** c~~");
        boolean allStrike = true;
        boolean innerBold = false;
        for (int i = 0; i < b6.get(0).runs.size(); i++) {
            MdRun run = b6.get(0).runs.get(i);
            allStrike = allStrike && run.strike;
            innerBold = innerBold || run.bold;
        }
        check("nest.strike.allStrike", allStrike, "删除线内所有片段都应带删除线");
        check("nest.strike.innerBold", innerBold, "删除线内粗体应保留");
    }

    /** 链接与裸链接。 */
    private static void testLinks() {
        List<MdBlock> md = MdCore.parse("see [text](https://a.com) end");
        MdRun link = null;
        for (int i = 0; i < md.get(0).runs.size(); i++) {
            if (md.get(0).runs.get(i).href != null) {
                link = md.get(0).runs.get(i);
            }
        }
        check("link.exists", link != null, "应识别 Markdown 链接");
        check("link.text", link != null && "text".equals(link.text), "链接文本应为 text");
        check("link.href", link != null && "https://a.com".equals(link.href), "链接地址应为 https://a.com");

        List<MdBlock> bare = MdCore.parse("visit https://a.com/x now");
        MdRun bareLink = null;
        for (int i = 0; i < bare.get(0).runs.size(); i++) {
            if (bare.get(0).runs.get(i).href != null) {
                bareLink = bare.get(0).runs.get(i);
            }
        }
        check("bare.exists", bareLink != null, "应识别裸链接");
        check("bare.href", bareLink != null && "https://a.com/x".equals(bareLink.href), "裸链接地址应正确");

        List<MdBlock> trailing = MdCore.parse("(https://a.com/x).");
        MdRun trimmed = null;
        for (int i = 0; i < trailing.get(0).runs.size(); i++) {
            if (trailing.get(0).runs.get(i).href != null) {
                trimmed = trailing.get(0).runs.get(i);
            }
        }
        check("bare.trailing", trimmed != null && "https://a.com/x".equals(trimmed.href), "尾部标点应剥离");

        List<MdBlock> unfinished = MdCore.parse("[t](");
        check("link.unclosed", "[t](".equals(runsText(unfinished.get(0))), "未闭合链接应保留字面");
        check("link.unclosed.href", unfinished.get(0).runs.get(0).href == null, "未闭合链接不应产生 href");

        List<MdBlock> emptyLabel = MdCore.parse("[](url)");
        check("link.emptyLabel", !emptyLabel.isEmpty(), "空标签链接不应崩溃");

        List<MdBlock> parens = MdCore.parse("[a](https://x.com/a_(b))");
        MdRun paren = null;
        for (int i = 0; i < parens.get(0).runs.size(); i++) {
            if (parens.get(0).runs.get(i).href != null) {
                paren = parens.get(0).runs.get(i);
            }
        }
        check("link.nestedParen", paren != null && "https://x.com/a_(b)".equals(paren.href), "链接内括号应配对");
    }

    /** 列表。 */
    private static void testLists() {
        List<MdBlock> unordered = MdCore.parse("- a\n* b\n+ c");
        check("list.unordered.count", unordered.size() == 3, "三个无序列表项应为 3 块");
        check("list.unordered.type", unordered.get(0).type == MdBlock.LIST_ITEM, "应为列表项");
        check("list.unordered.ordered", !unordered.get(0).ordered, "应为无序");
        check("list.unordered.order", unordered.get(0).order == 0, "无序列表 order 应为 0");
        check("list.unordered.text", "a".equals(runsText(unordered.get(0))), "列表项文本应为 a");

        List<MdBlock> ordered = MdCore.parse("1. first\n2) second");
        check("list.ordered.count", ordered.size() == 2, "有序列表应为 2 块");
        check("list.ordered.flag", ordered.get(0).ordered, "应为有序");
        check("list.ordered.order1", ordered.get(0).order == 1, "首项序号应为 1");
        check("list.ordered.order2", ordered.get(1).order == 2, "第二项序号应为 2");
        check("list.ordered.text", "first".equals(runsText(ordered.get(0))), "文本应为 first");

        List<MdBlock> nested = MdCore.parse("- a\n  - b\n    - c");
        check("list.nested.l0", nested.get(0).level == 0, "首项层级应为 0");
        check("list.nested.l1", nested.get(1).level == 1, "第二项层级应为 1");
        check("list.nested.l2", nested.get(2).level == 2, "第三项层级应为 2");

        List<MdBlock> bigOrder = MdCore.parse("999999999. x");
        check("list.bigOrder", bigOrder.get(0).ordered && bigOrder.get(0).order == 999999999, "9 位序号应原样保留");
        List<MdBlock> tooManyDigits = MdCore.parse("1234567890. x");
        check("list.tooManyDigits", tooManyDigits.get(0).type == MdBlock.PARAGRAPH, "10 位序号不应识别为列表");

        List<MdBlock> notList = MdCore.parse("-notalist");
        check("list.noSpace", notList.get(0).type == MdBlock.PARAGRAPH, "减号后无空格应为段落");
    }

    /** 引用、分隔线、表格。 */
    private static void testQuoteRuleAndTable() {
        List<MdBlock> quote = MdCore.parse("> q1\n> q2");
        check("quote.count", quote.size() == 1, "连续引用应合为 1 块");
        check("quote.type", quote.get(0).type == MdBlock.QUOTE, "应为引用块");
        check("quote.text", "q1\nq2".equals(runsText(quote.get(0))), "引用文本应保留换行");

        List<MdBlock> quoteInline = MdCore.parse("> **b**");
        check("quote.inline", quoteInline.get(0).runs.get(0).bold, "引用内应解析行内样式");

        String[] rules = { "---", "***", "___", " - - - ", "*****" };
        for (int i = 0; i < rules.length; i++) {
            List<MdBlock> rule = MdCore.parse(rules[i]);
            check("rule." + i, rule.size() == 1 && rule.get(0).type == MdBlock.RULE, "应为分隔线: " + rules[i]);
        }
        List<MdBlock> notRule = MdCore.parse("--");
        check("rule.tooShort", notRule.get(0).type == MdBlock.PARAGRAPH, "两个减号不是分隔线");

        List<MdBlock> table = MdCore.parse("| a | b |\n| --- | --- |\n| 1 | 2 |");
        check("table.count", table.size() == 2, "表格分隔行应被跳过");
        check("table.type", table.get(0).type == MdBlock.TABLE_ROW, "应为表格行");
        check("table.text", "| a | b |".equals(runsText(table.get(0))), "表格行文本应保留");
        check("table.second", "| 1 | 2 |".equals(runsText(table.get(1))), "第二行数据应保留");

        List<MdBlock> leadingSpace = MdCore.parse("  ---  ");
        check("rule.indented", leadingSpace.get(0).type == MdBlock.RULE, "缩进的分隔线应被识别");
    }

    /** CRLF / CR / LF 三种换行。 */
    private static void testLineEndings() {
        check("eol.lf", MdCore.parse("a\nb").size() == 1, "LF 单换行应合为 1 段");
        check("eol.crlf", MdCore.parse("a\r\nb").size() == 1, "CRLF 单换行应合为 1 段");
        check("eol.cr", MdCore.parse("a\rb").size() == 1, "CR 单换行应合为 1 段");

        check("eol.crlf.blocks", MdCore.parse("a\r\n\r\nb").size() == 2, "CRLF 空行应分块");
        check("eol.cr.blocks", MdCore.parse("a\r\rb").size() == 2, "CR 空行应分块");
        check("eol.lf.blocks", MdCore.parse("a\n\nb").size() == 2, "LF 空行应分块");

        List<MdBlock> crlfCode = MdCore.parse("```\r\nline1\r\nline2\r\n```");
        check("eol.code.type", crlfCode.get(0).type == MdBlock.CODE, "CRLF 下应识别代码块");
        check("eol.code.text", "line1\nline2".equals(crlfCode.get(0).text), "代码内部换行应统一");

        List<MdBlock> mixed = MdCore.parse("a\r\n\r\nb\rc\nd");
        check("eol.mixed.count", mixed.size() == 2, "单换行合段、空行分块：应为 2 块");
        check("eol.mixed.text2", "b\nc\nd".equals(runsText(mixed.get(1))), "第二块应合并 b/c/d 三行");

        List<MdBlock> crlfList = MdCore.parse("- a\r\n- b\r\n");
        check("eol.list.count", crlfList.size() == 2, "CRLF 列表应为 2 块");
        check("eol.trailingNl", MdCore.parse("a\r\n").size() == 1, "结尾换行不应产生空块");
    }

    /** 硬换行：行尾两空格与 br。 */
    private static void testHardBreaks() {
        List<MdBlock> trailing = MdCore.parse("a  ");
        check("break.trailingSpaces", "a".equals(runsText(trailing.get(0))), "行尾空格应被裁掉");

        List<MdBlock> br = MdCore.parse("a<br>b");
        check("break.br.count", br.get(0).runs.size() == 3, "br 应拆成 3 段");
        check("break.br.nl", "\n".equals(br.get(0).runs.get(1).text), "中间段应为换行");
        check("break.br.tail", "b".equals(br.get(0).runs.get(2).text), "末段应为 b");

        List<MdBlock> brUpper = MdCore.parse("a<BR/>b");
        check("break.br.upper", brUpper.get(0).runs.size() == 3, "大写 BR/ 也应识别为换行");

        List<MdBlock> brSpace = MdCore.parse("a<br />b");
        check("break.br.space", brSpace.get(0).runs.size() == 3, "br 带空格与斜杠也应识别");

        List<MdBlock> other = MdCore.parse("a<img>b");
        check("break.otherTag", "a<img>b".equals(runsText(other.get(0))), "非 br 标签应保留字面");
    }

    /** 未闭合标记（流式传输中常见的半截输入）。 */
    private static void testUnclosedMarkers() {
        List<MdBlock> fence = MdCore.parse("```java\nint a;");
        check("unclosed.fence.type", fence.size() == 1 && fence.get(0).type == MdBlock.CODE, "未闭合围栏应为代码块");
        check("unclosed.fence.text", "int a;".equals(fence.get(0).text), "未闭合围栏应吞掉剩余内容");
        check("unclosed.fence.lang", "java".equals(fence.get(0).lang), "语言标记应保留");

        List<MdBlock> tilde = MdCore.parse("~~~\nabc");
        check("unclosed.tilde", tilde.get(0).type == MdBlock.CODE && "abc".equals(tilde.get(0).text), "未闭合 ~~~ 同理");

        List<MdBlock> bold = MdCore.parse("**abc");
        check("unclosed.bold", "**abc".equals(runsText(bold.get(0))), "未闭合粗体应字面保留");
        List<MdBlock> italic = MdCore.parse("*abc");
        check("unclosed.italic", "*abc".equals(runsText(italic.get(0))), "未闭合斜体应字面保留");
        List<MdBlock> strike = MdCore.parse("~~abc");
        check("unclosed.strike", "~~abc".equals(runsText(strike.get(0))), "未闭合删除线应字面保留");
        List<MdBlock> code = MdCore.parse("`abc");
        check("unclosed.code", "`abc".equals(runsText(code.get(0))), "未闭合行内代码应字面保留");
        List<MdBlock> link = MdCore.parse("[abc](http://x");
        check("unclosed.link", !link.get(0).runs.isEmpty(), "未闭合链接不应崩溃");
        check("unclosed.link.href", link.get(0).runs.get(0).href == null, "未闭合链接不应产生 href");

        List<MdBlock> partial = MdCore.parse("正常段落\n\n**半截粗体");
        check("unclosed.mixed.count", partial.size() == 2, "正常段落与半截粗体应分块");
        check("unclosed.mixed.second", "**半截粗体".equals(runsText(partial.get(1))), "半截粗体应字面保留");
    }

    /** 异常/畸形输入不应抛异常。 */
    private static void testMalformedInput() {
        String[] inputs = {
            "[", "](", "**", "~~", "`", "```", ">>>", "||||", "   -", "1.",
            "[[]](())", "**~~`a`~~**", "\u0000\u0001", "<br", "|||", "###", "--- ---"
        };
        boolean ok = true;
        for (int i = 0; i < inputs.length; i++) {
            try {
                List<MdBlock> blocks = MdCore.parse(inputs[i]);
                ok = ok && blocks != null;
                for (int k = 0; k < blocks.size(); k++) {
                    MdBlock block = blocks.get(k);
                    ok = ok && block != null;
                    if (block.type != MdBlock.CODE) {
                        ok = ok && block.runs != null;
                        for (int m = 0; m < block.runs.size(); m++) {
                            ok = ok && block.runs.get(m) != null && block.runs.get(m).text != null;
                        }
                    }
                }
            } catch (RuntimeException ex) {
                ok = false;
                System.out.println("FAIL: malformed[" + i + "] threw " + ex);
            }
        }
        check("malformed.noThrow", ok, "畸形输入不应抛异常且结果不为 null");

        List<MdBlock> deep = MdCore.parse(repeat("**", 400) + "x" + repeat("**", 400));
        check("malformed.deepNest", deep != null && !deep.isEmpty(), "深层嵌套不应栈溢出");
        check("malformed.deepNest.text", runsText(deep.get(0)).indexOf('x') >= 0, "深层嵌套应保留文本 x");

        List<MdBlock> deepLink = MdCore.parse(repeat("[", 500) + "x" + repeat("]", 500) + "(u)");
        check("malformed.deepLink", deepLink != null && !deepLink.isEmpty(), "深层链接不应栈溢出");

        List<MdBlock> deepQuote = MdCore.parse(repeat(">", 100) + " q");
        check("malformed.deepQuote", deepQuote != null && !deepQuote.isEmpty(), "超多级引用不应崩溃");
    }

    /** 超长且未配对的重复标记串必须逐字符原样保留（不丢字、不吞字符）。 */
    private static void testOverlongMarkerPreservation() {
        char[] markers = { '*', '_', '~', '`' };
        String[] names = { "star", "underscore", "tilde", "backtick" };
        for (int m = 0; m < markers.length; m++) {
            // 行首的超长 ~~~/``` 属于合法围栏起始，故前置普通字符以确保是行内场景。
            String run = "a" + repeat(markers[m], 5000);
            String md = run + "x";
            List<MdBlock> blocks = MdCore.parse(md);
            check("preserve." + names[m] + ".count", blocks.size() == 1, names[m] + " 应为 1 块");
            check("preserve." + names[m] + ".type", blocks.get(0).type == MdBlock.PARAGRAPH,
                    names[m] + " 未配对标记应为段落");
            check("preserve." + names[m] + ".text", md.equals(runsText(blocks.get(0))),
                    names[m] + " 未配对标记应逐字符保留");

            String odd = "a" + repeat(markers[m], 5001) + "y";
            check("preserve." + names[m] + ".odd", odd.equals(runsText(MdCore.parse(odd).get(0))),
                    names[m] + " 奇数长度未配对标记应逐字符保留");

            // 超长成对标记本身是合法定界符，会被消费；这里守的是不伪造、也不吞掉正文。
            String body = repeat(markers[m], 4000);
            String both = "a" + body + "b" + body + "c";
            List<MdBlock> bothBlocks = MdCore.parse(both);
            String bothText = runsText(bothBlocks.get(0));
            check("preserve." + names[m] + ".pair.count", bothBlocks.size() == 1, names[m] + " 应为 1 块");
            check("preserve." + names[m] + ".pair.body", bothText.indexOf('a') >= 0 && bothText.indexOf('b') >= 0
                    && bothText.indexOf('c') >= 0, names[m] + " 正文 a/b/c 不应丢失");
            check("preserve." + names[m] + ".pair.noInvent", bothText.length() <= both.length(),
                    names[m] + " 不应凭空造出字符");
        }

        // 行首超长围栏字符属于合法代码围栏，不应被当作行内标记而吞掉内容。
        List<MdBlock> tildeFence = MdCore.parse(repeat('~', 5000) + "\nbody");
        check("preserve.tilde.fence", tildeFence.size() == 1 && tildeFence.get(0).type == MdBlock.CODE,
                "行首超长 ~~~ 应是未闭合代码块");
        check("preserve.tilde.fence.text", "body".equals(tildeFence.get(0).text), "围栏内容应为 body");

        String unpairedBold = "**" + repeat("a", 3000);
        check("preserve.unpairedBold", unpairedBold.equals(runsText(MdCore.parse(unpairedBold).get(0))),
                "未闭合粗体应逐字符保留");

        String pairedBold = repeat("**ab**", 3000);
        List<MdBlock> pairedBlocks = MdCore.parse(pairedBold);
        boolean allBold = true;
        for (int i = 0; i < pairedBlocks.get(0).runs.size(); i++) {
            allBold = allBold && pairedBlocks.get(0).runs.get(i).bold;
        }
        check("preserve.pairedBold.chars", repeat("ab", 3000).equals(runsText(pairedBlocks.get(0))),
                "成对粗体应只消费标记、保留全部正文字符");
        check("preserve.pairedBold.style", allBold && !pairedBlocks.get(0).runs.isEmpty(), "成对粗体应整体加粗");

        String alternating = repeat("**a", 3000);
        check("preserve.alternating.chars", repeat("a", 3000).equals(runsText(MdCore.parse(alternating).get(0))),
                "交替粗体标记应只消费标记、正文不丢字符");

        String nested = "**" + repeat("*", 5000) + "x";
        List<MdBlock> nestedBlocks = MdCore.parse(nested);
        check("preserve.nested", nested.equals(runsText(nestedBlocks.get(0))), "未配对嵌套星号应逐字符保留");

        String longLink = "[" + repeat("a", 5000);
        check("preserve.link", longLink.equals(runsText(MdCore.parse(longLink).get(0))), "未闭合链接标签应保留");

        String longParen = "[t](" + repeat("a", 5000);
        check("preserve.paren", longParen.equals(runsText(MdCore.parse(longParen).get(0))),
                "未闭合链接括号应逐字符保留");

        // 极端嵌套深度：不栈溢出、不超时。
        String deepStack = repeat("**", 5000) + "z" + repeat("**", 5000);
        long startDeep = System.currentTimeMillis();
        List<MdBlock> deepBlocks = MdCore.parse(deepStack);
        long costDeep = System.currentTimeMillis() - startDeep;
        check("preserve.deep.noStackOverflow", deepBlocks.size() == 1, "极端嵌套不应栈溢出");
        check("preserve.deep.text", runsText(deepBlocks.get(0)).indexOf('z') >= 0, "极端嵌套应保留文本 z");
        check("preserve.deep.fast", costDeep < 20000, "极端嵌套不应超时，实际 " + costDeep + "ms");

        String manyQuotes = repeat(">", 5000) + " q";
        check("preserve.deepQuote", !MdCore.parse(manyQuotes).isEmpty(), "超多级引用不应崩溃");
    }

    /** 超长输入（只保证不崩溃且有界耗时）。 */
    private static void testHugeInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("line ").append(i).append(" **bold** `code`\n");
        }
        long start = System.currentTimeMillis();
        List<MdBlock> blocks = MdCore.parse(sb.toString());
        long cost = System.currentTimeMillis() - start;
        check("huge.multiBlock", blocks.size() == 1, "无空行的超长文本应合为 1 段");
        check("huge.fast", cost < 20000, "超长输入应在合理时间内完成，实际 " + cost + "ms");

        String oneLine = repeat("*", 200000);
        long start2 = System.currentTimeMillis();
        List<MdBlock> stars = MdCore.parse(oneLine);
        long cost2 = System.currentTimeMillis() - start2;
        check("huge.stars", stars.size() == 1 && stars.get(0).type == MdBlock.RULE, "超长星号串是分隔线");
        check("huge.stars.fast", cost2 < 20000, "超长星号串不应超时，实际 " + cost2 + "ms");

        String starMess = repeat("*a", 100000);
        long start3b = System.currentTimeMillis();
        List<MdBlock> messy = MdCore.parse(starMess);
        long cost3b = System.currentTimeMillis() - start3b;
        check("huge.starMess", messy.size() == 1, "超长星号交替串应为 1 段");
        check("huge.starMess.text", runsText(messy.get(0)).length() > 0, "超长星号交替串不应丢内容");
        check("huge.starMess.fast", cost3b < 20000, "超长星号交替串不应超时，实际 " + cost3b + "ms");

        String unclosedStars = repeat("**a ", 50000);
        long start3c = System.currentTimeMillis();
        List<MdBlock> unclosed = MdCore.parse(unclosedStars);
        long cost3c = System.currentTimeMillis() - start3c;
        check("huge.unclosedStars.text", runsText(unclosed.get(0)).length() > 0, "大量未闭合粗体不应丢内容");
        check("huge.unclosedStars.fast", cost3c < 20000, "大量未闭合粗体不应超时，实际 " + cost3c + "ms");

        String manyPipes = repeat("| a ", 20000);
        check("huge.pipes", MdCore.parse(manyPipes).size() == 1, "超长表格行应为 1 块");

        String manyLinks = repeat("[t](https://x.com) ", 5000);
        long start3 = System.currentTimeMillis();
        List<MdBlock> links = MdCore.parse(manyLinks);
        long cost3 = System.currentTimeMillis() - start3;
        check("huge.links", links.size() == 1 && !links.get(0).runs.isEmpty(), "大量链接应解析出片段");
        check("huge.links.fast", cost3 < 20000, "大量链接不应超时，实际 " + cost3 + "ms");

        StringBuilder hugeText = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            hugeText.append('a');
        }
        check("huge.plain", MdCore.parse(hugeText.toString()).size() == 1, "超长纯文本应为 1 段");
    }

    /** 数据类契约：默认值、字段可写、复制与相等性。 */
    private static void testDataClassContract() {
        MdRun run = new MdRun("x");
        check("run.defaults.text", "x".equals(run.text), "构造函数应设置 text");
        check("run.defaults.flags", !run.bold && !run.italic && !run.code && !run.strike, "样式默认应为 false");
        check("run.defaults.href", run.href == null, "href 默认应为 null");
        check("run.nullText", "".equals(new MdRun(null).text), "null 文本应为空串");
        MdRun copy = run.copy();
        check("run.copy.equals", copy.equals(run), "复制对象应相等");
        check("run.copy.notSame", copy != run, "复制应为新对象");
        run.bold = true;
        check("run.mutable", run.bold, "public 字段应可直接写");
        check("run.equals.flagDiff", !copy.equals(run), "字段不同不应相等");

        MdBlock block = new MdBlock();
        check("block.defaults.type", block.type == MdBlock.PARAGRAPH, "默认类型应为段落");
        check("block.defaults.runs", block.runs != null && block.runs.isEmpty(), "runs 应初始化为空列表且非 null");
        check("block.defaults.lang", block.lang == null && block.text == null, "lang/text 默认应为 null");
        check("block.defaults.list", !block.ordered && block.order == 0 && block.level == 0, "列表字段默认应为 0/false");
        block.runs.add(new MdRun("y"));
        check("block.mutable", block.runs.size() == 1, "runs 应可直接添加");

        check("const.paragraph", MdBlock.PARAGRAPH == 0, "PARAGRAPH 应为 0");
        check("const.heading", MdBlock.HEADING == 1, "HEADING 应为 1");
        check("const.code", MdBlock.CODE == 2, "CODE 应为 2");
        check("const.listItem", MdBlock.LIST_ITEM == 3, "LIST_ITEM 应为 3");
        check("const.quote", MdBlock.QUOTE == 4, "QUOTE 应为 4");
        check("const.rule", MdBlock.RULE == 5, "RULE 应为 5");
        check("const.tableRow", MdBlock.TABLE_ROW == 6, "TABLE_ROW 应为 6");
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    /**
     * 断言一个行内片段：位置、文本与四个样式标记。
     *
     * @param name   用例名
     * @param md     Markdown 输入
     * @param index  片段下标
     * @param text   期望文本
     * @param bold   期望粗体
     * @param italic 期望斜体
     * @param code   期望行内代码
     * @param strike 期望删除线
     */
    private static void assertRun(String name, String md, int index, String text, boolean bold, boolean italic,
            boolean code, boolean strike) {
        List<MdBlock> blocks = MdCore.parse(md);
        if (blocks.isEmpty()) {
            check(name, false, "解析结果为空: " + md);
            return;
        }
        List<MdRun> runs = blocks.get(0).runs;
        if (index >= runs.size()) {
            check(name, false, "片段数量不足: 期望下标 " + index + " 实际 " + runs.size());
            return;
        }
        MdRun run = runs.get(index);
        boolean ok = text.equals(run.text) && run.bold == bold && run.italic == italic
                && run.code == code && run.strike == strike;
        check(name, ok, "期望 text=" + text + " bold=" + bold + " italic=" + italic + " code=" + code
                + " strike=" + strike + " 实际 " + run);
    }

    /**
     * 记一次断言结果。
     *
     * @param name      用例名
     * @param condition 断言条件
     * @param message   失败时输出的说明
     */
    private static void check(String name, boolean condition, String message) {
        if (condition) {
            pass++;
            System.out.println("PASS: " + name);
        } else {
            fail++;
            System.out.println("FAIL: " + name + " -> " + message);
        }
    }

    /** 把块的富文本片段拼成一个字符串。 */
    private static String runsText(MdBlock block) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < block.runs.size(); i++) {
            sb.append(block.runs.get(i).text);
        }
        return sb.toString();
    }

    /** 重复字符串 count 次。 */
    private static String repeat(String unit, int count) {
        StringBuilder sb = new StringBuilder(unit.length() * Math.max(count, 1));
        for (int i = 0; i < count; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    /** 重复字符 count 次。 */
    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(Math.max(count, 1));
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
