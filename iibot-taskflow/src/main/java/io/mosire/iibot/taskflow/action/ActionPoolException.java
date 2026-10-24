package io.mosire.iibot.taskflow.action;

/** 行动池具名失败；消息不包含行动正文。 */
public final class ActionPoolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String STORE_UNAVAILABLE = "STORE_UNAVAILABLE";
    public static final String CORRUPT_SNAPSHOT = "CORRUPT_SNAPSHOT";
    public static final String CONFLICT = "CONFLICT";
    public static final String ACTION_IN_CANONICAL_PACKAGE = "ACTION_IN_CANONICAL_PACKAGE";

    private final String code;

    public ActionPoolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ActionPoolException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
