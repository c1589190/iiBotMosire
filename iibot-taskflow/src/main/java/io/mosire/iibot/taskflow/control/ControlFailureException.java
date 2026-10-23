package io.mosire.iibot.taskflow.control;

/** 控制面可预期失败（如只读资源不存在、事件端口未注入），携带稳定外部错误码。 */
final class ControlFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    ControlFailureException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
