/*
 * 模块 B：app.purechat.netcore —— 手写 JSON 解析与序列化（仅依赖 java.*）。
 *
 * 说明：
 * - 解析结果为 LinkedHashMap<String,Object> / ArrayList<Object> / String / Double / Boolean / null；
 *   所有数字（含整数）统一解析为 Double，取值请用 Json.num(...)，避免自行强转。
 * - 序列化支持 Map / List / Object[] / String / Number / Boolean / null，字符串会正确转义。
 * - 不使用任何第三方库与 android.*，语法保持 Java 8 兼容。
 */
package app.purechat.netcore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 工具类：解析、序列化与取值便捷方法。
 *
 * <p>典型用法：</p>
 * <pre>
 * Object root = Json.parse(body);
 * String model = Json.str(root, "model", "unknown");
 * double tokens = Json.num(root, "input_tokens", 0);
 * </pre>
 *
 * <p>线程安全性：本类无可变静态状态，所有方法都是线程安全的。</p>
 */
public final class Json {

    /** 允许的最大嵌套层级，超过则抛出 {@link JsonException}，防止恶意输入导致栈溢出。 */
    private static final int MAX_DEPTH = 200;

    /** 序列化时 StringBuilder 的初始容量。 */
    private static final int SB_INIT = 64;

    /**
     * 工具类构造器：私有，禁止实例化。
     */
    private Json() {
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 把 JSON 文本解析为 Java 对象。
     *
     * @param text JSON 文本，不能为 null
     * @return 解析结果，可能是 LinkedHashMap、ArrayList、String、Double、Boolean 或 null
     * @throws JsonException 文本为 null、语法错误、嵌套超过 200 层或末尾有多余内容时抛出
     */
    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("JSON 文本为 null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw parser.error("JSON 文本结尾存在多余内容");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 把 Java 对象序列化为 JSON 文本。
     *
     * <p>支持的类型：Map（键会转为字符串）、List、Object[]、String、Number、Boolean、null；
     * 其它类型会退化为对其 {@link Object#toString()} 结果的字符串序列化。
     * Double 的 NaN / Infinity 会被序列化为 null。</p>
     *
     * @param value 待序列化的对象，可为 null（输出 "null"）
     * @return JSON 文本，永不为 null
     * @throws JsonException 嵌套超过 200 层时抛出
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(SB_INIT);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 取值便捷方法
    // ------------------------------------------------------------------

    /**
     * 取字符串字段；节点不是 Map、字段缺失或值不是字符串时返回默认值。
     *
     * @param node JSON 节点，可为 null
     * @param key  字段名，可为 null
     * @param def  默认值
     * @return 字段字符串值或默认值
     */
    public static String str(Object node, String key, String def) {
        Object v = get(node, key);
        if (v instanceof String) {
            return (String) v;
        }
        return def;
    }

    /**
     * 取数字字段；节点不是 Map、字段缺失或值不是数字时返回默认值。
     *
     * @param node JSON 节点，可为 null
     * @param key  字段名，可为 null
     * @param def  默认值
     * @return 字段数字值或默认值
     */
    public static double num(Object node, String key, double def) {
        Object v = get(node, key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return def;
    }

    /**
     * 取任意字段值；节点不是 Map 或字段缺失时返回 null。
     *
     * @param node JSON 节点，可为 null
     * @param key  字段名，可为 null
     * @return 字段原始值或 null
     */
    public static Object get(Object node, String key) {
        if (!(node instanceof Map) || key == null) {
            return null;
        }
        return ((Map<?, ?>) node).get(key);
    }

    /**
     * 把节点当作 JSON 对象；节点不是 Map 时返回 null。
     *
     * @param node JSON 节点，可为 null
     * @return 键为 String 的 Map，或 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object node) {
        if (node instanceof Map) {
            return (Map<String, Object>) node;
        }
        return null;
    }

    /**
     * 把节点当作 JSON 数组；节点不是 List 时返回 null。
     *
     * @param node JSON 节点，可为 null
     * @return Object 列表，或 null
     */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object node) {
        if (node instanceof List) {
            return (List<Object>) node;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 序列化实现
    // ------------------------------------------------------------------

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("序列化嵌套超过上限 " + MAX_DEPTH);
        }
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String) {
            writeString(sb, (String) value);
            return;
        }
        if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
            return;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
                return;
            }
            sb.append(formatDouble(d));
            return;
        }
        if (value instanceof Number) {
            sb.append(value.toString());
            return;
        }
        if (value instanceof Map) {
            writeMap(sb, (Map<?, ?>) value, depth);
            return;
        }
        if (value instanceof List) {
            writeList(sb, (List<?>) value, depth);
            return;
        }
        if (value instanceof Object[]) {
            writeArray(sb, (Object[]) value, depth);
            return;
        }
        writeString(sb, value.toString());
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, int depth) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Object k = entry.getKey();
            writeString(sb, k instanceof String ? (String) k : String.valueOf(k));
            sb.append(':');
            writeValue(sb, entry.getValue(), depth + 1);
        }
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> list, int depth) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, list.get(i), depth + 1);
        }
        sb.append(']');
    }

    private static void writeArray(StringBuilder sb, Object[] array, int depth) {
        sb.append('[');
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, array[i], depth + 1);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    private static String formatDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1.0e15d) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    // ------------------------------------------------------------------
    // 解析实现
    // ------------------------------------------------------------------

    /**
     * 严格的递归下降 JSON 解析器；深度由 readValue 的 depth 参数控制。
     */
    private static final class Parser {

        private final String s;
        private final int len;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
            this.pos = 0;
        }

        boolean isAtEnd() {
            return pos >= len;
        }

        JsonException error(String message) {
            return new JsonException(message + "（偏移 " + pos + "）");
        }

        void skipWhitespace() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("JSON 嵌套超过上限 " + MAX_DEPTH);
            }
            skipWhitespace();
            if (pos >= len) {
                throw error("期待一个 JSON 值，但文本已结束");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("非法的 JSON 值起始字符 '" + c + "'");
            }
        }

        private void expectWord(String word) {
            if (pos + word.length() > len || !s.regionMatches(pos, word, 0, word.length())) {
                throw error("期待字面量 " + word);
            }
            pos += word.length();
        }

        private Map<String, Object> readObject(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (pos >= len) {
                throw error("JSON 对象未闭合");
            }
            if (s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= len) {
                    throw error("JSON 对象未闭合");
                }
                if (s.charAt(pos) != '"') {
                    throw error("JSON 对象的键必须是双引号字符串");
                }
                String key = readString();
                skipWhitespace();
                if (pos >= len || s.charAt(pos) != ':') {
                    throw error("JSON 对象的键之后缺少冒号");
                }
                pos++;
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (pos >= len) {
                    throw error("JSON 对象未闭合");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw error("JSON 对象中期待 ',' 或 '}'");
            }
        }

        private List<Object> readArray(int depth) {
            pos++;
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (pos >= len) {
                throw error("JSON 数组未闭合");
            }
            if (s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (pos >= len) {
                    throw error("JSON 数组未闭合");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw error("JSON 数组中期待 ',' 或 ']'");
            }
        }

        private String readString() {
            pos++;
            StringBuilder sb = new StringBuilder(32);
            while (true) {
                if (pos >= len) {
                    throw error("JSON 字符串未闭合");
                }
                char c = s.charAt(pos);
                pos++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw error("JSON 字符串中的转义序列不完整");
                    }
                    char e = s.charAt(pos);
                    pos++;
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append(readHex4());
                            break;
                        default:
                            throw error("非法的 JSON 转义字符 '\\" + e + "'");
                    }
                    continue;
                }
                if (c < 0x20) {
                    throw error("JSON 字符串中出现未转义的控制字符");
                }
                sb.append(c);
            }
        }

        private char readHex4() {
            if (pos + 4 > len) {
                throw error("JSON \\u 转义长度不足 4 位");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hexValue(s.charAt(pos));
                if (digit < 0) {
                    throw error("JSON \\u 转义包含非十六进制字符");
                }
                value = value * 16 + digit;
                pos++;
            }
            return (char) value;
        }

        private Number readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            int intStart = pos;
            while (pos < len && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == intStart) {
                throw error("JSON 数字缺少整数部分");
            }
            if (pos - intStart > 1 && s.charAt(intStart) == '0') {
                throw error("JSON 数字不允许前导零");
            }
            if (pos < len && s.charAt(pos) == '.') {
                pos++;
                int fracStart = pos;
                while (pos < len && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == fracStart) {
                    throw error("JSON 数字缺少小数部分");
                }
            }
            if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int expStart = pos;
                while (pos < len && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == expStart) {
                    throw error("JSON 数字的指数部分缺少数字");
                }
            }
            String literal = s.substring(start, pos);
            return Double.valueOf(Double.parseDouble(literal));
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static int hexValue(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }
    }
}
