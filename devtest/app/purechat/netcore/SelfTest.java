/*
 * 模块 B 自测：app.purechat.netcore（JSON + SSE + 流事件）。
 *
 * 运行方式：
 *   javac --release 8 -encoding UTF-8 -d out\selftest app\src\app\purechat\netcore\*.java devtest\app\purechat\netcore\SelfTest.java
 *   java -cp out\selftest app.purechat.netcore.SelfTest
 *
 * 覆盖边界：空串、未闭合标记、超长输入、嵌套标记、异常 JSON、分片到达的 SSE、CRLF/CR/LF 换行。
 * 末尾打印 "SUMMARY pass=N fail=M"，有失败时以退出码 1 结束。
 */
package app.purechat.netcore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块 B 的纯 JVM 自测入口。
 *
 * <p>不依赖 JUnit，直接在主方法里执行断言并打印 PASS/FAIL 与汇总行。</p>
 */
public final class SelfTest {

    /** 通过的断言数。 */
    private static int pass = 0;

    /** 失败的断言数。 */
    private static int fail = 0;

    /** 失败详情，最后统一打印。 */
    private static final List<String> FAILURES = new ArrayList<String>();

    /**
     * 工具类构造器：私有，禁止实例化。
     */
    private SelfTest() {
    }

    /**
     * 自测入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        testJsonParseBasics();
        testJsonParseErrors();
        testJsonStringify();
        testJsonAccessors();
        testJsonLargeInput();
        testSseDecoderBasics();
        testSseNewlines();
        testSseChunked();
        testSseEdgeCases();
        testStreamEventParser();
        testStreamParserErrorAndUnknown();
        testPipeline();

        System.out.println("SUMMARY pass=" + pass + " fail=" + fail);
        for (int i = 0; i < FAILURES.size(); i++) {
            System.out.println("  FAILED -> " + FAILURES.get(i));
        }
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 断言助手
    // ------------------------------------------------------------------

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            FAILURES.add(name);
            System.out.println("FAIL: " + name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok;
        if (expected == null) {
            ok = actual == null;
        } else {
            ok = expected.equals(actual);
        }
        if (!ok) {
            fail++;
            FAILURES.add(name + "（期望 " + expected + "，实际 " + actual + "）");
            System.out.println("FAIL: " + name + "（期望 " + expected + "，实际 " + actual + "）");
        } else {
            pass++;
            System.out.println("PASS " + name);
        }
    }

    private static void near(String name, double expected, double actual) {
        if (Math.abs(expected - actual) < 1.0e-9d) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            FAILURES.add(name + "（期望 " + expected + "，实际 " + actual + "）");
            System.out.println("FAIL: " + name + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    private static void expectJsonError(String name, String text) {
        try {
            Json.parse(text);
            fail++;
            FAILURES.add(name + "（应当抛出 JsonException 却没有）");
            System.out.println("FAIL: " + name + "（应当抛出 JsonException 却没有）");
        } catch (JsonException e) {
            pass++;
            System.out.println("PASS " + name);
        }
    }

    // ------------------------------------------------------------------
    // JSON 解析：基础
    // ------------------------------------------------------------------

    private static void testJsonParseBasics() {
        check("parse(null 字面量) 返回 null", Json.parse("null") == null);
        check("parse(true) 返回 Boolean.TRUE", Boolean.TRUE.equals(Json.parse("true")));
        check("parse(false) 返回 Boolean.FALSE", Boolean.FALSE.equals(Json.parse("false")));
        eq("parse(42) 返回 Double", Double.valueOf(42.0d), Json.parse("42"));
        eq("parse(-7) 返回 Double", Double.valueOf(-7.0d), Json.parse("-7"));
        eq("parse(0) 返回 Double", Double.valueOf(0.0d), Json.parse("0"));
        eq("parse(3.5) 返回 Double", Double.valueOf(3.5d), Json.parse("3.5"));
        eq("parse(1e3) 返回 Double", Double.valueOf(1000.0d), Json.parse("1e3"));
        eq("parse(\\\"hi\\\") 返回 String", "hi", Json.parse("\"hi\""));
        eq("parse 处理 \\u 转义", "\u4f60\u597d", Json.parse("\"\\u4f60\\u597d\""));
        eq("parse 处理转义引号与反斜杠", "a\"b\\c/d", Json.parse("\"a\\\"b\\\\c\\/d\""));
        eq("parse 处理 \\n\\t\\r\\b\\f", "x\ny\tz\r\b\f", Json.parse("\"x\\ny\\tz\\r\\b\\f\""));
        eq("parse 处理代理对 \\uD83D\\uDE00 长度", Integer.valueOf(2),
                Integer.valueOf(((String) Json.parse("\"\\uD83D\\uDE00\"")).length()));

        Object nested = Json.parse("{\"a\":{\"b\":[1,2,3]}}");
        check("嵌套对象是 LinkedHashMap", nested instanceof LinkedHashMap);
        Object list = Json.get(Json.get(nested, "a"), "b");
        check("嵌套数组是 ArrayList", list instanceof ArrayList);
        eq("嵌套数组长度", Integer.valueOf(3), Integer.valueOf(Json.arr(list).size()));
        eq("嵌套数组第一个元素", Double.valueOf(1.0d), Json.arr(list).get(0));

        Object ordered = Json.parse("{\"z\":1,\"a\":2,\"m\":3}");
        List<String> keys = new ArrayList<String>(Json.obj(ordered).keySet());
        eq("对象保持插入顺序 key[0]", "z", keys.get(0));
        eq("对象保持插入顺序 key[2]", "m", keys.get(2));

        Object dup = Json.parse("{\"k\":1,\"k\":2}");
        eq("重复键后者覆盖前者", Double.valueOf(2.0d), Json.get(dup, "k"));

        check("空对象解析为空 Map", Json.obj(Json.parse("{}")) != null
                && Json.obj(Json.parse("{}")).isEmpty());
        check("空数组解析为空 List", Json.arr(Json.parse("[]")) != null
                && Json.arr(Json.parse("[]")).isEmpty());
        eq("前后空白被忽略", Double.valueOf(1.0d), Json.get(Json.parse("  \t\r\n {\"a\" : 1} \n"), "a"));
        check("超大整数也是 Double", Json.parse("123456789012345678901234567890") instanceof Double);
        eq("嵌套对象内的类型正确", Double.valueOf(1.0d), Json.get(Json.get(Json.parse("{\"a\":{\"b\":1}}"), "a"), "b"));
        near("num 可直接读取整数", 42.0d, Json.num(Json.parse("{\"n\":42}"), "n", -1));
    }

    // ------------------------------------------------------------------
    // JSON 解析：异常
    // ------------------------------------------------------------------

    private static void testJsonParseErrors() {
        expectJsonError("空串抛异常", "");
        expectJsonError("仅空白抛异常", "   \t\r\n ");
        expectJsonError("null 输入抛异常", null);
        expectJsonError("未闭合对象抛异常", "{");
        expectJsonError("未闭合数组抛异常", "[1,2");
        expectJsonError("未闭合字符串抛异常", "\"abc");
        expectJsonError("缺少值抛异常", "{\"a\":}");
        expectJsonError("键未加引号抛异常", "{a:1}");
        expectJsonError("数组尾逗号抛异常", "[1,]");
        expectJsonError("对象尾逗号抛异常", "{\"a\":1,}");
        expectJsonError("字面量不完整抛异常", "tru");
        expectJsonError("前导零抛异常", "01");
        expectJsonError("缺少小数部分抛异常", "1.");
        expectJsonError("缺少指数部分抛异常", "1e");
        expectJsonError("多余内容抛异常", "1 2");
        expectJsonError("字符串内裸换行抛异常", "\"a\nb\"");
        expectJsonError("非法 \\u 转义抛异常", "\"\\u12\"");
        expectJsonError("非法转义字符抛异常", "\"\\q\"");
        expectJsonError("单个字符抛异常", "[");
        expectJsonError("嵌套标签/括号超限抛异常", deepJson(300));
        check("嵌套 50 层可以正常解析", Json.arr(Json.parse(deepJson(50))) != null);
    }

    private static String deepJson(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        sb.append('1');
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // JSON 序列化
    // ------------------------------------------------------------------

    private static void testJsonStringify() {
        eq("stringify(null) 输出 null", "null", Json.stringify(null));
        eq("stringify 转义引号与换行", "\"a\\\"b\\n\"", Json.stringify("a\"b\n"));
        eq("stringify 转义控制字符", "\"\\u0001\"", Json.stringify("\u0001"));
        eq("stringify 转义反斜杠", "\"a\\\\b\"", Json.stringify("a\\b"));
        eq("stringify 长整型", "5", Json.stringify(Long.valueOf(5L)));
        eq("stringify 小数", "2.5", Json.stringify(Double.valueOf(2.5d)));
        eq("stringify 整数值的 double", "3", Json.stringify(Double.valueOf(3.0d)));
        eq("stringify 负整数", "-8", Json.stringify(Double.valueOf(-8.0d)));
        eq("stringify 布尔", "false", Json.stringify(Boolean.FALSE));
        eq("stringify 解析出的整数", "42", Json.stringify(Json.parse("42")));
        eq("stringify NaN 输出 null", "null", Json.stringify(Double.valueOf(Double.NaN)));

        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", Double.valueOf(1.0d));
        List<Object> arr = new ArrayList<Object>();
        arr.add(Boolean.TRUE);
        arr.add(null);
        arr.add("x");
        map.put("b", arr);
        eq("stringify 复合结构", "{\"a\":1,\"b\":[true,null,\"x\"]}", Json.stringify(map));

        // Java 侧常见容器：HashMap 键顺序不保证，只校验内容可被正确解析回来
        java.util.HashMap<String, Object> hash = new java.util.HashMap<String, Object>();
        hash.put("k1", Integer.valueOf(7));
        hash.put("k2", Boolean.TRUE);
        String hashJson = Json.stringify(hash);
        Object hashBack = Json.parse(hashJson);
        eq("HashMap 序列化后键 1 可读", Double.valueOf(7.0d), Json.get(hashBack, "k1"));
        eq("HashMap 序列化后键 2 可读", Boolean.TRUE, Json.get(hashBack, "k2"));

        List<Object> plainList = new ArrayList<Object>();
        plainList.add("a");
        plainList.add(Integer.valueOf(1));
        eq("List 序列化（String 与 Integer 混排）", "[\"a\",1]", Json.stringify(plainList));

        eq("Object[] 序列化", "[\"a\",1,true]", Json.stringify(new Object[] {"a", Integer.valueOf(1), Boolean.TRUE}));
        eq("嵌套 List 序列化", "[[1],[2,3]]", Json.stringify(Json.parse("[[1],[2,3]]")));
        eq("空 Map 序列化", "{}", Json.stringify(new LinkedHashMap<String, Object>()));
        eq("空 List 序列化", "[]", Json.stringify(new ArrayList<Object>()));

        Object roundTrip = Json.parse("{\"a\":[1,2.5,\"s\",true,null],\"b\":{}}");
        eq("序列化后再解析保持等价", Json.stringify(roundTrip), Json.stringify(Json.parse(Json.stringify(roundTrip))));
        eq("中文经序列化后原样保留", "\"中文\"", Json.stringify("中文"));
    }

    // ------------------------------------------------------------------
    // JSON 取值便捷方法
    // ------------------------------------------------------------------

    private static void testJsonAccessors() {
        Object node = Json.parse("{\"s\":\"v\",\"n\":42,\"d\":1.5,\"o\":{\"k\":1},\"a\":[1,2],\"nil\":null}");
        eq("str 正常取值", "v", Json.str(node, "s", "def"));
        eq("str 类型不符返回默认值", "def", Json.str(node, "n", "def"));
        eq("str 缺失返回默认值", "def", Json.str(node, "missing", "def"));
        eq("str 值为 null 返回默认值", "def", Json.str(node, "nil", "def"));
        eq("str 节点不是 Map 返回默认值", "def", Json.str("字符串节点", "s", "def"));
        eq("str 节点为 null 返回默认值", "def", Json.str(null, "s", "def"));
        eq("str key 为 null 返回默认值", "def", Json.str(node, null, "def"));

        near("num 整数取值", 42.0d, Json.num(node, "n", -1));
        near("num 小数取值", 1.5d, Json.num(node, "d", -1));
        near("num 缺失返回默认值", -1.0d, Json.num(node, "missing", -1));
        near("num 类型不符返回默认值", -1.0d, Json.num(node, "s", -1));

        eq("get 取对象", Double.valueOf(1.0d), Json.get(Json.get(node, "o"), "k"));
        eq("get 缺失返回 null", null, Json.get(node, "missing"));
        check("obj 取对象", Json.obj(Json.get(node, "o")) != null);
        eq("obj 对数组返回 null", null, Json.obj(Json.get(node, "a")));
        eq("obj 对 null 返回 null", null, Json.obj(null));
        check("arr 取数组", Json.arr(Json.get(node, "a")) != null);
        eq("arr 对对象返回 null", null, Json.arr(Json.get(node, "o")));
        eq("arr 对 null 返回 null", null, Json.arr(null));
    }

    // ------------------------------------------------------------------
    // JSON 超长输入
    // ------------------------------------------------------------------

    private static void testJsonLargeInput() {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < 200000; i++) {
            sb.append('x');
        }
        sb.append('"');
        String big = (String) Json.parse(sb.toString());
        eq("超长字符串解析长度", Integer.valueOf(200000), Integer.valueOf(big.length()));

        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < 50000; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(i);
        }
        arr.append(']');
        eq("超长数组元素个数", Integer.valueOf(50000), Integer.valueOf(Json.arr(Json.parse(arr.toString())).size()));

        StringBuilder obj = new StringBuilder("{");
        for (int i = 0; i < 20000; i++) {
            if (i > 0) {
                obj.append(',');
            }
            obj.append("\"k").append(i).append("\":").append(i);
        }
        obj.append('}');
        eq("超长对象键个数", Integer.valueOf(20000), Integer.valueOf(Json.obj(Json.parse(obj.toString())).size()));

        String bigStr = big;
        eq("超长字符串序列化往返", bigStr, Json.parse(Json.stringify(bigStr)));
    }

    // ------------------------------------------------------------------
    // SSE 基础
    // ------------------------------------------------------------------

    /** 解码一段完整文本（一次性 push + end）。 */
    private static List<SseEvent> decode(String text) {
        SseDecoder d = new SseDecoder();
        List<SseEvent> out = new ArrayList<SseEvent>();
        d.push(text, out);
        d.end(out);
        return out;
    }

    private static String dump(List<SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            SseEvent e = events.get(i);
            if (i > 0) {
                sb.append('|');
            }
            sb.append(e.event).append(':').append(e.data);
        }
        return sb.toString();
    }

    private static void testSseDecoderBasics() {
        List<SseEvent> one = decode("data: hello\n\n");
        eq("单条 data 事件数量", Integer.valueOf(1), Integer.valueOf(one.size()));
        eq("单条 data 事件内容", "hello", one.get(0).data);
        eq("无 event 字段时事件名为 null", null, one.get(0).event);

        List<SseEvent> named = decode("event: ping\ndata: {}\n\n");
        eq("带事件名的事件名", "ping", named.get(0).event);
        eq("带事件名的数据", "{}", named.get(0).data);

        List<SseEvent> multi = decode("data: a\ndata: b\n\n");
        eq("多行 data 以换行拼接", "a\nb", multi.get(0).data);

        List<SseEvent> comment = decode(": keep-alive\n\ndata: x\n\n");
        eq("注释行不产生事件", Integer.valueOf(1), Integer.valueOf(comment.size()));
        eq("注释行之后的事件正常", "x", comment.get(0).data);

        List<SseEvent> onlyComment = decode(": ping\n: pong\n\n");
        eq("仅注释行不产生事件", Integer.valueOf(0), Integer.valueOf(onlyComment.size()));

        List<SseEvent> noColon = decode("data\n\n");
        eq("data 无冒号时为空数据", "", noColon.get(0).data);

        List<SseEvent> spaces = decode("data:  x\n\n");
        eq("仅去掉一个前导空格", " x", spaces.get(0).data);

        List<SseEvent> idEvent = decode("id: 7\ndata: z\n\n");
        eq("id 字段被忽略但事件正常派发", "z", idEvent.get(0).data);

        List<SseEvent> unknown = decode("foo: bar\n\n");
        eq("未知字段不产生事件", Integer.valueOf(0), Integer.valueOf(unknown.size()));

        List<SseEvent> many = decode("data: 1\n\ndata: 2\n\ndata: 3\n\n");
        eq("一个分片内多事件", "null:1|null:2|null:3", dump(many));

        List<SseEvent> eventNoData = decode("event: ping\n\n");
        eq("只有 event 名时 data 为空串", Integer.valueOf(1), Integer.valueOf(eventNoData.size()));
        eq("只有 event 名时 event 名正确", "ping", eventNoData.get(0).event);
        eq("只有 event 名时 data 为空", "", eventNoData.get(0).data);

        List<SseEvent> trailing = new ArrayList<SseEvent>();
        SseDecoder d = new SseDecoder();
        d.push("data: a\n\ndata: b\n", trailing);
        eq("未以空行结尾的事件被缓冲", Integer.valueOf(1), Integer.valueOf(trailing.size()));
        d.end(trailing);
        eq("end 丢弃未以空行结尾的事件", Integer.valueOf(1), Integer.valueOf(trailing.size()));

        List<SseEvent> reused = new ArrayList<SseEvent>();
        SseDecoder reuse = new SseDecoder();
        reuse.push("data: a\n\n", reused);
        reuse.end(reused);
        reuse.push("data: b\n\n", reused);
        reuse.end(reused);
        eq("end 之后解码器可复用", "null:a|null:b", dump(reused));

        List<SseEvent> nullChunk = new ArrayList<SseEvent>();
        SseDecoder dc = new SseDecoder();
        dc.push(null, nullChunk);
        dc.push("", nullChunk);
        eq("push(null) 与 push(空串) 无副作用", Integer.valueOf(0), Integer.valueOf(nullChunk.size()));
        dc.push("data: q\n\n", nullChunk);
        eq("push 之后仍能正常解码", "q", nullChunk.get(0).data);

        List<SseEvent> toNull = new ArrayList<SseEvent>();
        SseDecoder dn = new SseDecoder();
        dn.push("data: drop\n\n", null);
        eq("push 输出列表为 null 时不抛异常", Integer.valueOf(0), Integer.valueOf(toNull.size()));

        List<SseEvent> bom = decode("\uFEFFdata: bom\n\n");
        eq("UTF-8 BOM 被跳过", "bom", bom.get(0).data);
    }

    // ------------------------------------------------------------------
    // SSE 换行：CRLF / CR / LF
    // ------------------------------------------------------------------

    private static void testSseNewlines() {
        List<SseEvent> crlf = decode("event: e\r\ndata: a\r\n\r\n");
        eq("CRLF 分隔的事件数", Integer.valueOf(1), Integer.valueOf(crlf.size()));
        eq("CRLF 事件的 event 名", "e", crlf.get(0).event);
        eq("CRLF 事件的 data", "a", crlf.get(0).data);

        List<SseEvent> cr = decode("event: e\rdata: a\r\r");
        eq("CR 分隔的事件数", Integer.valueOf(1), Integer.valueOf(cr.size()));
        eq("CR 事件的 data", "a", cr.get(0).data);

        List<SseEvent> lf = decode("event: e\ndata: a\n\n");
        eq("LF 分隔的事件数", Integer.valueOf(1), Integer.valueOf(lf.size()));
        eq("LF 事件的 data", "a", lf.get(0).data);

        List<SseEvent> mixed = decode("event: e\r\ndata: a\n\r\ndata: b\rdata: c\r\r");
        eq("混合换行的事件总数", Integer.valueOf(2), Integer.valueOf(mixed.size()));
        eq("混合换行事件 1", "e:a", mixed.get(0).event + ":" + mixed.get(0).data);
        eq("混合换行事件 2", "b\nc", mixed.get(1).data);

        List<SseEvent> split = new ArrayList<SseEvent>();
        SseDecoder d = new SseDecoder();
        d.push("data: a\r", split);
        eq("CR 结尾分片暂不派发", Integer.valueOf(0), Integer.valueOf(split.size()));
        d.push("\n\r\n", split);
        eq("跨分片 CRLF 正确合并", Integer.valueOf(1), Integer.valueOf(split.size()));
        eq("跨分片 CRLF 的数据", "a", split.get(0).data);

        List<SseEvent> splitCr = new ArrayList<SseEvent>();
        SseDecoder d2 = new SseDecoder();
        d2.push("data: b\r", splitCr);
        d2.push("data: c\r\r", splitCr);
        eq("CR 与后续 data 行正确分界", "b\nc", splitCr.get(0).data);
    }

    // ------------------------------------------------------------------
    // SSE 分片
    // ------------------------------------------------------------------

    /** 按固定分片长度增量解码，返回与 dump 相同的表示。 */
    private static String decodeChunked(String text, int chunk) {
        SseDecoder d = new SseDecoder();
        List<SseEvent> out = new ArrayList<SseEvent>();
        for (int i = 0; i < text.length(); i += chunk) {
            int end = Math.min(text.length(), i + chunk);
            d.push(text.substring(i, end), out);
        }
        d.end(out);
        return dump(out);
    }

    private static void testSseChunked() {
        String stream = "event: message_start\ndata: {\"a\":1}\n\n"
                + ": comment line\n"
                + "event: content_block_delta\r\ndata: {\"b\":\"two\"}\r\n\r\n"
                + "data: line1\r"
                + "data: line2\r\r"
                + "data: last\n\n";
        String expected = "message_start:{\"a\":1}"
                + "|content_block_delta:{\"b\":\"two\"}"
                + "|null:line1\nline2"
                + "|null:last";
        eq("整段一次推入的结果", expected, decodeChunked(stream, stream.length()));
        for (int chunk = 1; chunk <= 17; chunk++) {
            eq("分片大小为 " + chunk + " 时结果一致", expected, decodeChunked(stream, chunk));
        }
        eq("分片大小为 1 时事件数", Integer.valueOf(4), Integer.valueOf(decode(stream).size()));

        // 每个事件单独到达，模拟真实网络
        SseDecoder d = new SseDecoder();
        List<SseEvent> out = new ArrayList<SseEvent>();
        d.push("event: ping\ndata: {}", out);
        eq("没有空行的分片暂存事件", Integer.valueOf(0), Integer.valueOf(out.size()));
        d.push("\n\n", out);
        eq("补上空行后派发", "ping:{}", dump(out));
        d.push("data: tail", out);
        d.end(out);
        eq("end 后仍只有一条事件", Integer.valueOf(1), Integer.valueOf(out.size()));
    }

    // ------------------------------------------------------------------
    // SSE 边界
    // ------------------------------------------------------------------

    private static void testSseEdgeCases() {
        eq("空串输入无事件", Integer.valueOf(0), Integer.valueOf(decode("").size()));
        eq("仅空行无事件", Integer.valueOf(0), Integer.valueOf(decode("\n\n\n").size()));
        eq("仅 CRLF 空行无事件", Integer.valueOf(0), Integer.valueOf(decode("\r\n\r\n").size()));

        StringBuilder sb = new StringBuilder("data: ");
        for (int i = 0; i < 100000; i++) {
            sb.append('z');
        }
        sb.append("\n\n");
        List<SseEvent> big = decode(sb.toString());
        eq("超长 SSE 行事件数", Integer.valueOf(1), Integer.valueOf(big.size()));
        eq("超长 SSE 行长度", Integer.valueOf(100000), Integer.valueOf(big.get(0).data.length()));

        // 超长分片流：2000 条事件
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            many.append("data: ").append(i).append("\n\n");
        }
        eq("超长流的事件数", Integer.valueOf(2000), Integer.valueOf(decode(many.toString()).size()));
        eq("超长流最后一条数据", "1999", decode(many.toString()).get(1999).data);

        // data 值保持原始内容（不 trim）
        List<SseEvent> preserve = decode("data:  pad  \n\n");
        eq("data 保留行尾空格", " pad  ", preserve.get(0).data);

        // 多个 data 行中的一个空值
        List<SseEvent> blank = decode("data:\ndata: x\n\n");
        eq("空 data 行参与拼接", "\nx", blank.get(0).data);
    }

    // ------------------------------------------------------------------
    // StreamEventParser：正常帧
    // ------------------------------------------------------------------

    private static void testStreamEventParser() {
        StreamEvent start = StreamEventParser.parse("message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-3\","
                        + "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}");
        eq("message_start 类型", Integer.valueOf(StreamEvent.MESSAGE_START), Integer.valueOf(start.type));
        eq("message_start 消息 ID", "msg_1", start.messageId);
        eq("message_start 模型", "claude-3", start.model);
        eq("message_start 输入 token", Long.valueOf(25L), Long.valueOf(start.inputTokens));
        eq("message_start 输出 token", Long.valueOf(1L), Long.valueOf(start.outputTokens));

        StreamEvent startFlat = StreamEventParser.parse(null,
                "{\"type\":\"message_start\",\"id\":\"msg_2\",\"model\":\"m\",\"usage\":{\"input_tokens\":3}}");
        eq("message_start 支持平铺字段", "msg_2", startFlat.messageId);
        eq("message_start 平铺 usage", Long.valueOf(3L), Long.valueOf(startFlat.inputTokens));

        StreamEvent textStart = StreamEventParser.parse("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        eq("content_block_start 类型", Integer.valueOf(StreamEvent.CONTENT_BLOCK_START),
                Integer.valueOf(textStart.type));
        eq("content_block_start 下标", Integer.valueOf(0), Integer.valueOf(textStart.index));
        eq("content_block_start 块类型", "text", textStart.blockType);
        eq("content_block_start 初始文本", "", textStart.text);

        StreamEvent thinkStart = StreamEventParser.parse("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"thinking\","
                        + "\"thinking\":\"let me think\",\"signature\":\"sig0\"}}");
        eq("thinking 块类型", "thinking", thinkStart.blockType);
        eq("thinking 块初始内容", "let me think", thinkStart.text);
        eq("thinking 块签名", "sig0", thinkStart.signature);
        eq("thinking 块下标", Integer.valueOf(1), Integer.valueOf(thinkStart.index));

        StreamEvent textDelta = StreamEventParser.parse("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
        eq("text_delta 类型", Integer.valueOf(StreamEvent.CONTENT_BLOCK_DELTA),
                Integer.valueOf(textDelta.type));
        eq("text_delta deltaType", "text_delta", textDelta.deltaType);
        eq("text_delta 文本", "Hello", textDelta.text);
        eq("text_delta 下标", Integer.valueOf(0), Integer.valueOf(textDelta.index));

        StreamEvent thinkDelta = StreamEventParser.parse("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"thinking_delta\","
                        + "\"thinking\":\"hmm\"}}");
        eq("thinking_delta deltaType", "thinking_delta", thinkDelta.deltaType);
        eq("thinking_delta 文本", "hmm", thinkDelta.text);

        StreamEvent sigDelta = StreamEventParser.parse("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"signature_delta\","
                        + "\"signature\":\"abc\"}}");
        eq("signature_delta deltaType", "signature_delta", sigDelta.deltaType);
        eq("signature_delta 签名", "abc", sigDelta.signature);
        eq("signature_delta 下标", Integer.valueOf(2), Integer.valueOf(sigDelta.index));

        StreamEvent citeDelta = StreamEventParser.parse("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\","
                        + "\"citation\":{\"url\":\"https://x\"}}}");
        eq("citations_delta deltaType", "citations_delta", citeDelta.deltaType);
        eq("citations_delta 类型", Integer.valueOf(StreamEvent.CONTENT_BLOCK_DELTA),
                Integer.valueOf(citeDelta.type));

        StreamEvent stop = StreamEventParser.parse("content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":3}");
        eq("content_block_stop 类型", Integer.valueOf(StreamEvent.CONTENT_BLOCK_STOP),
                Integer.valueOf(stop.type));
        eq("content_block_stop 下标", Integer.valueOf(3), Integer.valueOf(stop.index));

        StreamEvent md = StreamEventParser.parse("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                        + "\"usage\":{\"output_tokens\":42}}");
        eq("message_delta 类型", Integer.valueOf(StreamEvent.MESSAGE_DELTA), Integer.valueOf(md.type));
        eq("message_delta 停止原因", "end_turn", md.stopReason);
        eq("message_delta 输出 token", Long.valueOf(42L), Long.valueOf(md.outputTokens));

        StreamEvent title = StreamEventParser.parse("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"title\":\"会话标题\"}}");
        eq("message_delta 标题", "会话标题", title.title);

        StreamEvent stopMsg = StreamEventParser.parse("message_stop", "{\"type\":\"message_stop\"}");
        eq("message_stop 类型", Integer.valueOf(StreamEvent.MESSAGE_STOP), Integer.valueOf(stopMsg.type));

        StreamEvent ping = StreamEventParser.parse("ping", "{\"type\":\"ping\"}");
        eq("ping 类型", Integer.valueOf(StreamEvent.PING), Integer.valueOf(ping.type));
        StreamEvent pingNamed = StreamEventParser.parse("ping", "{}");
        eq("无名数据但事件名为 ping 时识别为 ping", Integer.valueOf(StreamEvent.PING),
                Integer.valueOf(pingNamed.type));

        StreamEvent blockStopNoIndex = StreamEventParser.parse("content_block_stop",
                "{\"type\":\"content_block_stop\"}");
        eq("缺失 index 时为 -1", Integer.valueOf(-1), Integer.valueOf(blockStopNoIndex.index));
    }

    // ------------------------------------------------------------------
    // StreamEventParser：错误与未知帧
    // ------------------------------------------------------------------

    private static void testStreamParserErrorAndUnknown() {
        StreamEvent err = StreamEventParser.parse(null,
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"服务繁忙\"}}");
        eq("error 类型", Integer.valueOf(StreamEvent.ERROR), Integer.valueOf(err.type));
        eq("error 消息", "服务繁忙", err.errorMessage);

        StreamEvent errNoType = StreamEventParser.parse(null, "{\"error\":{\"message\":\"boom\"}}");
        eq("无名 error 帧识别为错误", Integer.valueOf(StreamEvent.ERROR), Integer.valueOf(errNoType.type));
        eq("无名 error 帧消息", "boom", errNoType.errorMessage);

        StreamEvent errNamed = StreamEventParser.parse("error", "plain text failure");
        eq("事件名为 error 的非 JSON 数据识别为错误", Integer.valueOf(StreamEvent.ERROR),
                Integer.valueOf(errNamed.type));
        eq("事件名为 error 的非 JSON 数据保留原文", "plain text failure", errNamed.errorMessage);

        StreamEvent unknownType = StreamEventParser.parse(null, "{\"type\":\"weird_event\",\"x\":1}");
        eq("未知 type 返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN),
                Integer.valueOf(unknownType.type));

        StreamEvent badJson = StreamEventParser.parse(null, "{not json");
        eq("异常 JSON 返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN),
                Integer.valueOf(badJson.type));

        StreamEvent truncated = StreamEventParser.parse("message_start", "{\"type\":\"message_start\",");
        eq("截断 JSON 返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN),
                Integer.valueOf(truncated.type));

        StreamEvent nullData = StreamEventParser.parse(null, null);
        check("null data 不返回 null", nullData != null);
        eq("null data 返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN), Integer.valueOf(nullData.type));

        StreamEvent emptyData = StreamEventParser.parse(null, "   ");
        eq("空 data 返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN), Integer.valueOf(emptyData.type));

        StreamEvent arrayRoot = StreamEventParser.parse(null, "[1,2,3]");
        eq("数组根返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN), Integer.valueOf(arrayRoot.type));

        StreamEvent doubleEncoded = StreamEventParser.parse(null,
                "\"{\\\"type\\\":\\\"ping\\\"}\"");
        eq("二次编码的 data 也能识别", Integer.valueOf(StreamEvent.PING),
                Integer.valueOf(doubleEncoded.type));

        StreamEvent noType = StreamEventParser.parse("content_block_stop", "{\"index\":5}");
        eq("无 type 时按事件名回退", Integer.valueOf(StreamEvent.CONTENT_BLOCK_STOP),
                Integer.valueOf(noType.type));
        eq("无 type 时下标仍可读", Integer.valueOf(5), Integer.valueOf(noType.index));

        StreamEvent nullLike = StreamEventParser.parse("content_block_delta", "null");
        eq("data 为 null 字面量返回 UNKNOWN", Integer.valueOf(StreamEvent.UNKNOWN),
                Integer.valueOf(nullLike.type));
    }

    // ------------------------------------------------------------------
    // 端到端：SSE 分片 -> 解析 -> 文本拼接
    // ------------------------------------------------------------------

    private static String buildStream(String nl) {
        String br = nl + nl;
        return "event: message_start" + nl
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_9\",\"model\":\"claude-3\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}" + br
                + ": keep-alive" + br
                + "event: content_block_start" + nl
                + "data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}" + br
                + "event: content_block_delta" + nl
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}" + br
                + "event: content_block_delta" + nl
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}" + br
                + "event: content_block_stop" + nl
                + "data: {\"type\":\"content_block_stop\",\"index\":0}" + br
                + "event: message_delta" + nl
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":12}}" + br
                + "event: message_stop" + nl
                + "data: {\"type\":\"message_stop\"}" + br
                + "event: ping" + nl
                + "data: {\"type\":\"ping\"}" + br;
    }

    private static void testPipeline() {
        String[] newlines = new String[] {"\n", "\r\n", "\r"};
        for (int n = 0; n < newlines.length; n++) {
            String text = buildStream(newlines[n]);
            for (int chunk = 1; chunk <= 7; chunk++) {
                SseDecoder d = new SseDecoder();
                List<SseEvent> events = new ArrayList<SseEvent>();
                for (int i = 0; i < text.length(); i += chunk) {
                    d.push(text.substring(i, Math.min(text.length(), i + chunk)), events);
                }
                d.end(events);
                StringBuilder parsedText = new StringBuilder();
                StringBuilder types = new StringBuilder();
                String messageId = null;
                String model = null;
                String stopReason = null;
                long outTokens = 0;
                for (int i = 0; i < events.size(); i++) {
                    SseEvent e = events.get(i);
                    StreamEvent se = StreamEventParser.parse(e.event, e.data);
                    check("端到端解析不返回 null", se != null);
                    types.append(se.type).append(',');
                    if (se.type == StreamEvent.CONTENT_BLOCK_DELTA) {
                        if (se.text != null) {
                            parsedText.append(se.text);
                        }
                    }
                    if (se.type == StreamEvent.MESSAGE_START) {
                        messageId = se.messageId;
                        model = se.model;
                    }
                    if (se.type == StreamEvent.MESSAGE_DELTA) {
                        stopReason = se.stopReason;
                        outTokens = se.outputTokens;
                    }
                    // 每轮只断言一次总量，避免断言数随分片数量爆炸
                    if (chunk == 7) {
                        if (se.type == StreamEvent.MESSAGE_START) {
                            eq("端到端消息 ID", "msg_9", se.messageId);
                        }
                    }
                }
                eq("端到端事件序列（换行 " + describe(n) + " 分片 " + chunk + "）",
                        "1,2,3,3,4,5,6,7,", types.toString());
                eq("端到端拼接文本（换行 " + describe(n) + " 分片 " + chunk + "）",
                        "Hello world", parsedText.toString());
                eq("端到端消息 ID（换行 " + describe(n) + " 分片 " + chunk + "）", "msg_9", messageId);
                eq("端到端模型名（换行 " + describe(n) + " 分片 " + chunk + "）", "claude-3", model);
                eq("端到端停止原因（换行 " + describe(n) + " 分片 " + chunk + "）", "end_turn", stopReason);
                eq("端到端输出 token（换行 " + describe(n) + " 分片 " + chunk + "）",
                        Long.valueOf(12L), Long.valueOf(outTokens));
            }
        }
    }

    private static String describe(int index) {
        if (index == 0) {
            return "LF";
        }
        if (index == 1) {
            return "CRLF";
        }
        return "CR";
    }
}
