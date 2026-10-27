package io.mosire.iibot.bot.note;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** 一条 QQ 用户备注；同一用户跨私聊和群聊共享。 */
public record UserNote(
        String accountId,
        String qqId,
        String displayName,
        Set<String> tags,
        String note,
        int priority,
        long revision,
        Instant updatedAt) {

    public UserNote {
        accountId = requireText(accountId, "accountId");
        qqId = requireText(qqId, "qqId");
        displayName = displayName == null ? "" : displayName.trim();
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        note = note == null ? "" : note.trim();
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision 必须为正");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 不暴露 QQ 号的展示标签，仅供摘要排序使用。 */
    public String priorityLabel() {
        return "P" + priority;
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
