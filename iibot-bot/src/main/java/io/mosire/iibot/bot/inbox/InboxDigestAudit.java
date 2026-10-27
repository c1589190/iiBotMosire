package io.mosire.iibot.bot.inbox;

import java.time.Instant;
import java.util.Objects;

/** Inbox digest 的本地审计投影；不保存正文。 */
public record InboxDigestAudit(
        String threadId,
        String itemId,
        long fromSeq,
        long toSeq,
        String summary,
        int priority,
        String status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant ackedAt) {

    public InboxDigestAudit {
        threadId = requireText(threadId, "threadId");
        itemId = requireText(itemId, "itemId");
        summary = summary == null ? "" : summary;
        status = requireText(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public InboxDigestAudit withDone(Instant at) {
        return new InboxDigestAudit(threadId, itemId, fromSeq, toSeq, summary, priority,
                "done", revision, createdAt, at, at);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value.trim();
    }
}
