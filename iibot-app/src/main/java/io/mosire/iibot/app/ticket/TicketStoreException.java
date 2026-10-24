package io.mosire.iibot.app.ticket;

/** 事件源工单 Store 的稳定失败类型。 */
public final class TicketStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        CORRUPT,
        SAVE
    }

    private final Code code;

    public TicketStoreException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public TicketStoreException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
