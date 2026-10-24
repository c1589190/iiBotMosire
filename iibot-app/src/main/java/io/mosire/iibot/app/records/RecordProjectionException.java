package io.mosire.iibot.app.records;

/** Markdown 回合投影/读取的稳定失败类型；不携带正文。 */
public final class RecordProjectionException extends RuntimeException {

    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String UNSAFE_PATH = "UNSAFE_PATH";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String IO_FAILED = "IO_FAILED";
    public static final String PROJECTION_FAILED = "PROJECTION_FAILED";
    public static final String RECORD_INCONSISTENT = "RECORD_INCONSISTENT";

    private final String code;

    public RecordProjectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RecordProjectionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
