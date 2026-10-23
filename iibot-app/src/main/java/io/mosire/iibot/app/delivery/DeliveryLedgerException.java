package io.mosire.iibot.app.delivery;

import java.util.Objects;

/**
 * 投递声明失败。异常消息只包含路径/异常类别等诊断信息，不包含消息正文或凭据。
 */
public final class DeliveryLedgerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败类别；测试和日志可据此做脱敏分类。 */
    public enum Code {
        INVALID_KEY,
        IO,
        CORRUPT,
        SCHEMA,
        SERIALIZE,
        SAVE
    }

    private final Code code;

    public DeliveryLedgerException(Code code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public DeliveryLedgerException(Code code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
