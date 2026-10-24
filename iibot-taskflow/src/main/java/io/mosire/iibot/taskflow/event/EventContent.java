package io.mosire.iibot.taskflow.event;

import java.util.Objects;

/**
 * 事件正文。
 *
 * <p>JSON 正文放在 {@code content_json}，Markdown/纯文本放在 {@code content_text}。
 * 两种载体严格二选一，避免同一事件出现两份不一致正文。敏感字段/密钥由持久化层在写入前
 * 脱敏，调用方不得依赖这里做安全过滤。</p>
 */
public record EventContent(EventContentType type, String json, String text) {

    public EventContent {
        Objects.requireNonNull(type, "type");
        if (type == EventContentType.JSON) {
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("JSON 事件正文不能为空");
            }
            if (text != null) {
                throw new IllegalArgumentException("JSON 事件正文不能同时提供 text");
            }
        } else {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException(type + " 事件正文不能为空");
            }
            if (json != null) {
                throw new IllegalArgumentException(type + " 事件正文不能同时提供 json");
            }
        }
    }

    /** 返回唯一正文载体，供 hash/长度校验使用。 */
    public String body() {
        return json != null ? json : text;
    }
}
