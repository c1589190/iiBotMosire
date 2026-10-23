package io.mosire.iibot.taskflow.control;

/** 控制面工具参数错误；由工具边界映射为稳定错误码 {@code INVALID_ARGUMENTS}。 */
final class InvalidControlArgumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidControlArgumentException(String message) {
        super(message);
    }
}
