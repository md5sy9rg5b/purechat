/*
 * 语音输入（ASR）自测：WAV 头封装与 multipart/form-data 请求体拼装的字节级断言。
 *
 * 运行方式：
 *   pwsh -NoProfile -File D:\work\purechat\build.ps1 -SelfTestOnly
 *
 * 覆盖点：
 * - RIFF/WAVE/fmt /data 各字段值、小端序、ChunkSize、采样率、声道数、位深、字节率、块对齐；
 * - 双声道/其它位深的边界，以及非法参数抛异常；
 * - multipart 的 boundary、Content-Disposition、Content-Type、CRLF 与总长度；
 * - 地址规整与响应解析（成功、缺 text、400/408/413/429/503/504）。
 *
 * 末尾打印 "SUMMARY pass=N fail=M"，有失败时以退出码 1 结束。
 */
package app.purechat.asr;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import app.purechat.AsrClient;

/**
 * 模块 C（语音输入）的纯 JVM 自测入口。
 *
 * <p>不依赖 JUnit，直接在主方法里执行断言并打印 PASS/FAIL 与汇总行。</p>
 */
public final class AsrSelfTest {

    /** 通过的断言数。 */
    private static int pass = 0;

    /** 失败的断言数。 */
    private static int fail = 0;

    /** 失败详情，最后统一打印。 */
    private static final List<String> FAILURES = new ArrayList<String>();

    /**
     * 工具类构造器：私有，禁止实例化。
     */
    private AsrSelfTest() {
    }

    /**
     * 自测入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        testWavHeaderBasics();
        testWavHeaderLayout();
        testWavHeaderEdgeCases();
        testBuildWav();
        testBoundary();
        testMultipartBody();
        testMultipartEmpty();
        testOrigin();
        testParseResponseOk();
        testParseResponseErrors();

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

    private static void fail(String name) {
        fail++;
        FAILURES.add(name);
        System.out.println("FAIL: " + name);
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            FAILURES.add(name + "（期望 " + expected + "，实际 " + actual + "）");
            System.out.println("FAIL: " + name + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    /** 小端序读 4 字节无符号整数。 */
    private static long uint32(byte[] b, int offset) {
        return (b[offset] & 0xFFL)
                | ((b[offset + 1] & 0xFFL) << 8)
                | ((b[offset + 2] & 0xFFL) << 16)
                | ((b[offset + 3] & 0xFFL) << 24);
    }

    /** 小端序读 2 字节无符号整数。 */
    private static int uint16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    /** 取 ASCII 片段。 */
    private static String ascii(byte[] b, int offset, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (b[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    /** 按 UTF-8 编码（自测运行环境固定为 UTF-8）。 */
    private static byte[] utf8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 在字节数组里查找子串，返回首个下标。 */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // WAV 头
    // ------------------------------------------------------------------

    private static void testWavHeaderBasics() {
        byte[] h = AsrClient.buildWavHeader(3200, 16000, 1, 16);
        eq("WAV 头长度固定 44 字节", Integer.valueOf(44), Integer.valueOf(h.length));
        eq("WAV 头长度常量与实现一致", Integer.valueOf(44),
                Integer.valueOf(AsrClient.WAV_HEADER_SIZE));
        eq("RIFF 标记", "RIFF", ascii(h, 0, 4));
        eq("WAVE 标记", "WAVE", ascii(h, 8, 4));
        eq("fmt 标记", "fmt ", ascii(h, 12, 4));
        eq("data 标记", "data", ascii(h, 36, 4));
    }

    private static void testWavHeaderLayout() {
        // 1 秒 16 kHz / 16 bit / 单声道 = 32000 字节
        int dataSize = 32000;
        byte[] h = AsrClient.buildWavHeader(dataSize, 16000, 1, 16);
        eq("ChunkSize = 36 + dataSize", Long.valueOf(36L + dataSize), Long.valueOf(uint32(h, 4)));
        eq("fmt 子块大小 = 16", Long.valueOf(16L), Long.valueOf(uint32(h, 16)));
        eq("音频格式 = 1（PCM）", Integer.valueOf(1), Integer.valueOf(uint16(h, 20)));
        eq("声道数 = 1", Integer.valueOf(1), Integer.valueOf(uint16(h, 22)));
        eq("采样率 = 16000", Long.valueOf(16000L), Long.valueOf(uint32(h, 24)));
        eq("字节率 = 32000", Long.valueOf(32000L), Long.valueOf(uint32(h, 28)));
        eq("块对齐 = 2", Integer.valueOf(2), Integer.valueOf(uint16(h, 32)));
        eq("位深 = 16", Integer.valueOf(16), Integer.valueOf(uint16(h, 34)));
        eq("data 子块大小 = dataSize", Long.valueOf((long) dataSize), Long.valueOf(uint32(h, 40)));

        // 小端序逐字节校验：采样率 16000 = 0x00003E80
        eq("采样率第 1 字节（小端最低位）", Integer.valueOf(0x80), Integer.valueOf(h[24] & 0xFF));
        eq("采样率第 2 字节", Integer.valueOf(0x3E), Integer.valueOf(h[25] & 0xFF));
        eq("采样率第 3 字节", Integer.valueOf(0x00), Integer.valueOf(h[26] & 0xFF));
        eq("采样率第 4 字节", Integer.valueOf(0x00), Integer.valueOf(h[27] & 0xFF));

        // 双声道 44.1 kHz / 16 bit：字节率 176400、块对齐 4
        byte[] stereo = AsrClient.buildWavHeader(1000, 44100, 2, 16);
        eq("双声道块对齐 = 4", Integer.valueOf(4), Integer.valueOf(uint16(stereo, 32)));
        eq("双声道字节率 = 176400", Long.valueOf(176400L), Long.valueOf(uint32(stereo, 28)));
        eq("双声道声道数 = 2", Integer.valueOf(2), Integer.valueOf(uint16(stereo, 22)));

        // 8 位单声道 8000 Hz：块对齐 1、字节率 8000
        byte[] low = AsrClient.buildWavHeader(800, 8000, 1, 8);
        eq("8 位块对齐 = 1", Integer.valueOf(1), Integer.valueOf(uint16(low, 32)));
        eq("8 位字节率 = 8000", Long.valueOf(8000L), Long.valueOf(uint32(low, 28)));
        eq("8 位位深字段 = 8", Integer.valueOf(8), Integer.valueOf(uint16(low, 34)));

        // 24 位双声道
        byte[] h24 = AsrClient.buildWavHeader(600, 48000, 2, 24);
        eq("24 位块对齐 = 6", Integer.valueOf(6), Integer.valueOf(uint16(h24, 32)));
        eq("24 位字节率 = 288000", Long.valueOf(288000L), Long.valueOf(uint32(h24, 28)));

        // 所有接口支持的采样率都能正确写入
        int[] rates = {8000, 16000, 22050, 24000, 32000, 44100, 48000};
        boolean ratesOk = true;
        for (int i = 0; i < rates.length; i++) {
            byte[] rateHeader = AsrClient.buildWavHeader(0, rates[i], 1, 16);
            if (uint32(rateHeader, 24) != rates[i]) {
                ratesOk = false;
            }
        }
        check("7 种受支持的采样率写入正确", ratesOk);
    }

    private static void testWavHeaderEdgeCases() {
        byte[] empty = AsrClient.buildWavHeader(0, 16000, 1, 16);
        eq("空音频 ChunkSize = 36", Long.valueOf(36L), Long.valueOf(uint32(empty, 4)));
        eq("空音频 data 大小 = 0", Long.valueOf(0L), Long.valueOf(uint32(empty, 40)));

        try {
            AsrClient.buildWavHeader(1, 16000, 3, 16);
            fail("声道数 3 应当抛 IllegalArgumentException");
            System.out.println("FAIL: 声道数 3 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS 声道数 3 抛 IllegalArgumentException");
        }
        try {
            AsrClient.buildWavHeader(1, 0, 1, 16);
            fail("采样率 0 应当抛 IllegalArgumentException");
            System.out.println("FAIL: 采样率 0 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS 采样率 0 抛 IllegalArgumentException");
        }
        try {
            AsrClient.buildWavHeader(-1, 16000, 1, 16);
            fail("负长度应当抛 IllegalArgumentException");
            System.out.println("FAIL: 负长度应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS 负长度抛 IllegalArgumentException");
        }
        try {
            AsrClient.buildWavHeader(1, 16000, 1, 12);
            fail("位深 12 应当抛 IllegalArgumentException");
            System.out.println("FAIL: 位深 12 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS 位深 12 抛 IllegalArgumentException");
        }
    }

    private static void testBuildWav() {
        byte[] pcm = new byte[3200];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i & 0xFF);
        }
        byte[] wav = AsrClient.buildWav(pcm, 16000, 1, 16);
        eq("WAV 总长度 = 44 + PCM", Integer.valueOf(44 + pcm.length), Integer.valueOf(wav.length));
        eq("WAV 前 4 字节为 RIFF", "RIFF", ascii(wav, 0, 4));
        eq("WAV 头之后紧跟原始 PCM 首字节", Byte.valueOf(pcm[0]), Byte.valueOf(wav[44]));
        eq("WAV 末字节与 PCM 末字节一致", Byte.valueOf(pcm[pcm.length - 1]),
                Byte.valueOf(wav[wav.length - 1]));
        eq("WAV 头 ChunkSize 与总长度自洽", Long.valueOf((long) (wav.length - 8)),
                Long.valueOf(uint32(wav, 4)));
        eq("WAV data 大小为 PCM 长度", Long.valueOf((long) pcm.length), Long.valueOf(uint32(wav, 40)));

        byte[] empty = AsrClient.buildWav(new byte[0], 16000, 1, 16);
        eq("空 PCM 也产出 44 字节合法头", Integer.valueOf(44), Integer.valueOf(empty.length));

        try {
            AsrClient.buildWav(null, 16000, 1, 16);
            fail("buildWav(null) 应当抛 IllegalArgumentException");
            System.out.println("FAIL: buildWav(null) 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS buildWav(null) 抛 IllegalArgumentException");
        }

        // 120 秒上限长度自洽：16 kHz / 16 bit / 单声道 = 3,840,000 字节
        byte[] maxPcm = new byte[AsrClient.SAMPLE_RATE * 2 * 120];
        byte[] maxWav = AsrClient.buildWav(maxPcm, AsrClient.SAMPLE_RATE, AsrClient.CHANNELS,
                AsrClient.BITS_PER_SAMPLE);
        eq("120 秒 WAV 总长度", Long.valueOf(44L + maxPcm.length), Long.valueOf(maxWav.length));
        check("120 秒 WAV 未超过 8 MiB 上限", maxWav.length <= AsrClient.MAX_BYTES);
    }

    // ------------------------------------------------------------------
    // multipart
    // ------------------------------------------------------------------

    private static void testBoundary() {
        String b1 = AsrClient.newBoundary();
        String b2 = AsrClient.newBoundary();
        check("boundary 非空", b1 != null && b1.length() > 0);
        check("boundary 长度不超过 70", b1.length() <= 70);
        check("两次生成的 boundary 不同", !b1.equals(b2));
        check("boundary 只含 ASCII 安全字符", b1.matches("[A-Za-z0-9'()+_,\\-.\\/:=? ]+"));
        check("boundary 不以 -- 结尾", !b1.endsWith("--"));
    }

    private static void testMultipartBody() {
        byte[] file = utf8("abc");
        String boundary = "TESTBOUNDARY123";
        byte[] body = AsrClient.buildMultipartBody(boundary, "file", "asr.wav", "audio/wav", file);
        String s = new String(body, java.nio.charset.Charset.forName("UTF-8"));

        String head = "--TESTBOUNDARY123\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"asr.wav\"\r\n"
                + "Content-Type: audio/wav\r\n"
                + "\r\n";
        String tail = "\r\n--TESTBOUNDARY123--\r\n";
        eq("multipart 总长度 = head + file + tail",
                Integer.valueOf(utf8(head).length + file.length + utf8(tail).length),
                Integer.valueOf(body.length));
        eq("multipart 请求体逐字节符合规范", head + "abc" + tail, s);
        check("multipart 以 --boundary CRLF 开头", s.startsWith("--TESTBOUNDARY123\r\n"));
        check("multipart 以 --boundary-- CRLF 结尾", s.endsWith("\r\n--TESTBOUNDARY123--\r\n"));
        check("包含 Content-Disposition 字段名", s.contains("name=\"file\""));
        check("包含 filename", s.contains("filename=\"asr.wav\""));
        check("包含 Content-Type: audio/wav", s.contains("Content-Type: audio/wav"));
        eq("头部与数据之间恰好一个空行", Integer.valueOf(1),
                Integer.valueOf(countOccurrences(s, "\r\n\r\n")));
        check("换行统一为 CRLF", !s.replace("\r\n", "").contains("\n"));

        int headIdx = s.indexOf("\r\n\r\n") + 4;
        eq("文件字节起始偏移正确", "abc", s.substring(headIdx, headIdx + 3));
        eq("文件字节之后立即是尾部 CRLF--boundary",
                "\r\n--TESTBOUNDARY123--\r\n", s.substring(headIdx + 3));

        // 默认封装：字段名 file、文件名 asr.wav、类型 audio/wav
        byte[] wav = AsrClient.buildWav(new byte[] {1, 2, 3, 4}, 16000, 1, 16);
        byte[] body2 = AsrClient.buildWavMultipartBody("B2", wav);
        String head2 = "--B2\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"asr.wav\"\r\n"
                + "Content-Type: audio/wav\r\n"
                + "\r\n";
        String tail2 = "\r\n--B2--\r\n";
        eq("默认封装总长度 = 头部 + WAV + 尾部",
                Integer.valueOf(utf8(head2).length + wav.length + utf8(tail2).length),
                Integer.valueOf(body2.length));
        eq("默认封装头部与规范一致", head2, ascii(body2, 0, utf8(head2).length));
        eq("默认封装尾部与规范一致", tail2,
                ascii(body2, utf8(head2).length + wav.length, utf8(tail2).length));
        int wavStart = indexOf(body2, new byte[] {'R', 'I', 'F', 'F'});
        eq("默认封装内 WAV 起始偏移", Integer.valueOf(utf8(head2).length),
                Integer.valueOf(wavStart));
        eq("默认封装内 WAV 标记可解析", "WAVE", ascii(body2, wavStart + 8, 4));
        byte[] expectPcm = new byte[] {1, 2, 3, 4};
        eq("默认封装内 PCM 紧跟在 44 字节 WAV 头之后", new String(expectPcm),
                ascii(body2, wavStart + AsrClient.WAV_HEADER_SIZE, expectPcm.length));

        try {
            AsrClient.buildMultipartBody("", "file", "asr.wav", "audio/wav", file);
            fail("空 boundary 应当抛 IllegalArgumentException");
            System.out.println("FAIL: 空 boundary 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS 空 boundary 抛 IllegalArgumentException");
        }
        try {
            AsrClient.buildMultipartBody("B", "", "asr.wav", "audio/wav", file);
            fail("空字段名应当抛 IllegalArgumentException");
            System.out.println("FAIL: 空字段名应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            pass++;
            System.out.println("PASS 空字段名抛 IllegalArgumentException");
        }
    }

    private static void testMultipartEmpty() {
        byte[] body = AsrClient.buildMultipartBody("EMPTY", "file", "asr.wav", "audio/wav", null);
        String s = new String(body, java.nio.charset.Charset.forName("UTF-8"));
        eq("null 文件内容按空文件封装",
                "--EMPTY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"asr.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\n\r\n--EMPTY--\r\n",
                s);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    // ------------------------------------------------------------------
    // 地址
    // ------------------------------------------------------------------

    private static void testOrigin() {
        eq("补全 https 前缀", "https://api.deepseek.com", AsrClient.normalizeOrigin("api.deepseek.com"));
        eq("去掉末尾斜杠", "https://api.deepseek.com",
                AsrClient.normalizeOrigin("https://api.deepseek.com///"));
        eq("http 升级为 https", "https://api.deepseek.com",
                AsrClient.normalizeOrigin("http://api.deepseek.com"));
        eq("去空白", "https://api.deepseek.com", AsrClient.normalizeOrigin("  https://api.deepseek.com  "));
        eq("空输入返回空串", "", AsrClient.normalizeOrigin("   "));
        eq("null 输入返回空串", "", AsrClient.normalizeOrigin(null));
        eq("ASR 地址拼接", "https://api.deepseek.com/v1/tools/asr",
                AsrClient.asrUrl("https://api.deepseek.com/"));
        eq("ASR 地址对空 origin 返回空串", "", AsrClient.asrUrl(""));
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    private static void testParseResponseOk() {
        AsrClient.AsrResult ok = AsrClient.parseResponse(200,
                "{\"request_id\":\"0b8c1c7e\",\"text\":\"今天上海的天气怎么样？\",\"duration_ms\":3200}");
        check("200 判定为成功", ok.ok);
        eq("200 取到 text", "今天上海的天气怎么样？", ok.text);
        eq("200 取到 duration_ms", Long.valueOf(3200L), Long.valueOf(ok.durationMs));
        eq("200 状态码保留", Integer.valueOf(200), Integer.valueOf(ok.statusCode));

        AsrClient.AsrResult blank = AsrClient.parseResponse(200,
                "{\"request_id\":\"x\",\"text\":\"\",\"duration_ms\":0}");
        check("空文本也算成功（静音录音）", blank.ok);
        eq("空文本内容为空串", "", blank.text);

        AsrClient.AsrResult noText = AsrClient.parseResponse(200, "{\"request_id\":\"x\"}");
        check("200 但缺少 text 判为失败", !noText.ok);
        check("缺少 text 的提示包含状态码", noText.message.contains("HTTP 200"));

        AsrClient.AsrResult notJson = AsrClient.parseResponse(200, "<html>oops</html>");
        check("200 但响应不是 JSON 判为失败", !notJson.ok);
    }

    private static void testParseResponseErrors() {
        AsrClient.AsrResult e400 = AsrClient.parseResponse(400,
                "{\"error\":{\"message\":\"file field is required\"}}");
        check("400 判为失败", !e400.ok);
        check("400 提示包含状态码", e400.message.contains("HTTP 400"));
        check("400 提示包含服务端 message", e400.message.contains("file field is required"));
        eq("400 保留原始响应体", "{\"error\":{\"message\":\"file field is required\"}}", e400.rawBody);

        AsrClient.AsrResult e408 = AsrClient.parseResponse(408, "");
        check("408 提示上传超时", e408.message.contains("上传超时"));
        check("413 提示音频过大", AsrClient.parseResponse(413, "").message.contains("音频过大"));
        check("429 提示限流", AsrClient.parseResponse(429, "").message.contains("限流"));
        check("503 提示服务繁忙", AsrClient.parseResponse(503, "").message.contains("服务繁忙"));
        check("504 提示超时", AsrClient.parseResponse(504, "").message.contains("超时"));

        AsrClient.AsrResult e500 = AsrClient.parseResponse(500, "{\"message\":\"boom\"}");
        check("500 提示包含服务端 message", e500.message.contains("boom"));
        check("未知状态码也判为失败", !e500.ok);

        AsrClient.AsrResult zero = AsrClient.parseResponse(0, "");
        check("状态码 0 判为失败", !zero.ok);
    }
}
