package io.mosire.iibot.app.event;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 事件 payload 的最后一道脱敏。
 *
 * <p>任务流 payload 本身只含标识与摘要；这里仍按“敏感键名 + 已知密钥值”双重规则再收口一次，
 * 避免未来新增字段时把 token/API key 或完整模型原文写进审计库。替换值固定为
 * {@code [REDACTED]}，不保留长度以外的任何信息。</p>
 */
final class EventRedactor {

    static final String PLACEHOLDER = "[REDACTED]";

    private final List<String> secrets;

    EventRedactor(Collection<String> secrets) {
        this.secrets = secrets == null ? List.of() : secrets.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    Map<String, String> sanitize(Map<String, String> payload) {
        Map<String, String> result = new LinkedHashMap<>();
        if (payload == null) {
            return result;
        }
        payload.forEach((key, value) -> result.put(key, sanitize(key, value)));
        return Map.copyOf(result);
    }

    String sanitize(String key, String value) {
        if (key != null && sensitiveKey(key)) {
            return PLACEHOLDER;
        }
        if (value == null || value.isEmpty()) {
            return "";
        }
        String redacted = value;
        for (String secret : secrets) {
            redacted = redacted.replace(secret, PLACEHOLDER);
        }
        return redacted;
    }

    private static boolean sensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("token") || lower.contains("secret")
                || lower.contains("password") || lower.contains("authorization")
                || lower.contains("apikey") || lower.contains("api_key");
    }
}
