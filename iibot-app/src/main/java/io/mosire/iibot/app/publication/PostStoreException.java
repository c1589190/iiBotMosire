package io.mosire.iibot.app.publication;

import java.util.Objects;

/**
 * 推文快照存储失败。异常消息只包含稳定错误码和诊断路径/类型，不包含推文正文。
 */
public final class PostStoreException extends RuntimeException {

    /** 稳定存储失败类别。 */
    public enum Code {
        CORRUPT,
        SCHEMA,
        IO,
        SERIALIZE,
        SAVE
    }

    private final Code code;

    public PostStoreException(Code code, String detail) {
        super("[" + Objects.requireNonNull(code, "code").name() + "] "
                + (detail == null ? "" : detail));
        this.code = code;
    }

    public PostStoreException(Code code, String detail, Throwable cause) {
        super("[" + Objects.requireNonNull(code, "code").name() + "] "
                + (detail == null ? "" : detail), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
