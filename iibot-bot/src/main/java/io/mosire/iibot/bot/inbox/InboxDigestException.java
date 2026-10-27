package io.mosire.iibot.bot.inbox;

import java.util.Map;
import java.util.Objects;

/**
 * Inbox digest 操作的稳定失败分类。
 *
 * <p>{@code code} 面向 MCP 调用方；{@code details} 只允许放不含 token、正文或
 * 本地路径的机器可读字段。</p>
 */
public class InboxDigestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final Map<String, Object> details;

    public InboxDigestException(String code, String message) {
        this(code, message, Map.of(), null);
    }

    public InboxDigestException(String code, String message,
                                Map<String, Object> details) {
        this(code, message, details, null);
    }

    public InboxDigestException(String code, String message,
                                Map<String, Object> details, Throwable cause) {
        super(requireText(message, "message"), cause);
        this.code = requireText(code, "code");
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
