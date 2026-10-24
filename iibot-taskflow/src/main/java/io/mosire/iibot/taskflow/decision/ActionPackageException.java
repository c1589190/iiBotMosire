package io.mosire.iibot.taskflow.decision;

/** 裁定包服务的宿主侧具名失败；消息不包含条目正文。 */
public final class ActionPackageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_PROPOSED = "NOT_PROPOSED";
    public static final String INVALID_STATUS = "INVALID_STATUS";

    private final String code;

    public ActionPackageException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ActionPackageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
