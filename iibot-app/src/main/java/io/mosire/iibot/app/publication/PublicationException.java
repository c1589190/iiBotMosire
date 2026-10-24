package io.mosire.iibot.app.publication;

/** 发布编排的稳定失败码；异常消息不包含 LLM 输出正文。 */
public final class PublicationException extends RuntimeException {

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String BATCH_NOT_FOUND = "BATCH_NOT_FOUND";
    public static final String BATCH_NOT_COMMITTED = "BATCH_NOT_COMMITTED";
    public static final String BATCH_NO_OP = "BATCH_NO_OP";
    public static final String OPENING_NOT_ALLOWED = "OPENING_NOT_ALLOWED";
    public static final String PUBLICATION_INPUT_TOO_LARGE = "PUBLICATION_INPUT_TOO_LARGE";
    public static final String PUBLICATION_LLM_FAILED = "PUBLICATION_LLM_FAILED";
    public static final String PUBLICATION_OUTPUT_INVALID = "PUBLICATION_OUTPUT_INVALID";
    public static final String PUBLICATION_POST_UNAVAILABLE = "PUBLICATION_POST_UNAVAILABLE";
    public static final String PUBLICATION_EVENT_UNAVAILABLE = "PUBLICATION_EVENT_UNAVAILABLE";
    public static final String PUBLICATION_RECORD_CORRUPT = "PUBLICATION_RECORD_CORRUPT";

    private final String code;

    public PublicationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PublicationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
