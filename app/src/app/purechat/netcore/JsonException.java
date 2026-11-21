/*
 * 模块 B：app.purechat.netcore —— JSON 异常类型。
 *
 * 说明：JsonException 是 Json 解析失败时抛出的非受检异常，
 * 上层网络/流处理代码可直接捕获它做容错（例如跳过坏帧）。
 */
package app.purechat.netcore;

/**
 * JSON 解析异常。
 *
 * <p>在 JSON 文本为空、语法错误、嵌套过深等情况下由
 * {@link Json#parse(String)} 抛出。属于非受检异常，方便上层在网络读取路径上统一容错。</p>
 */
public class JsonException extends RuntimeException {

    /** 序列化版本号，避免 IDE/编译器告警。 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造一个 JSON 解析异常。
     *
     * @param message 中文错误描述，包含出错位置偏移
     */
    public JsonException(String message) {
        super(message);
    }

    /**
     * 构造一个带底层原因的 JSON 解析异常。
     *
     * @param message 中文错误描述
     * @param cause   底层原因，可为 null
     */
    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
