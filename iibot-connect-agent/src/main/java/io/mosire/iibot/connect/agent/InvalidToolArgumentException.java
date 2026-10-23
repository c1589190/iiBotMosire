package io.mosire.iibot.connect.agent;

/**
 * 工具参数校验失败。
 *
 * <p>异常消息只包含字段名与固定说明，不回显模型输入；由 {@link AbstractQqTool} 转换为稳定错误码
 * {@code INVALID_ARGUMENTS}，不会抛到工具调用方。</p>
 */
final class InvalidToolArgumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidToolArgumentException(String message) {
        super(message, null, false, false);
    }
}
