package io.mosire.iibot.bot.inbox;

import java.util.Map;

/** Inbox digest 的 revision/状态冲突。 */
public final class InboxDigestConflictException extends InboxDigestException {

    private static final long serialVersionUID = 1L;

    public InboxDigestConflictException(String message) {
        super("INBOX_CONFLICT", message);
    }

    public InboxDigestConflictException(String message, Throwable cause) {
        super("INBOX_CONFLICT", message, Map.of(), cause);
    }

    public InboxDigestConflictException(String code, String message,
                                        Map<String, Object> details, Throwable cause) {
        super(code, message, details, cause);
    }
}
