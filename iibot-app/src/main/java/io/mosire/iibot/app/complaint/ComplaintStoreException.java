package io.mosire.iibot.app.complaint;

import java.util.Objects;

/**
 * 投诉快照存储失败。异常消息只包含稳定错误码和诊断路径/类型，不包含投诉或回应正文。
 */
public final class ComplaintStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 稳定存储失败类别。 */
    public enum Code {
        CORRUPT,
        SCHEMA,
        IO,
        SERIALIZE,
        SAVE
    }

    private final Code code;

    public ComplaintStoreException(Code code, String detail) {
        super("[" + Objects.requireNonNull(code, "code").name() + "] "
                + (detail == null ? "" : detail));
        this.code = code;
    }

    public ComplaintStoreException(Code code, String detail, Throwable cause) {
        super("[" + Objects.requireNonNull(code, "code").name() + "] "
                + (detail == null ? "" : detail), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
