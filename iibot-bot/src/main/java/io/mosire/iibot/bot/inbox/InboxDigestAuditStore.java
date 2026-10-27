package io.mosire.iibot.bot.inbox;

import java.time.Instant;
import java.util.List;

/** Inbox digest 审计端口；Inbox done item 不再可 view 时仍可查摘要。 */
public interface InboxDigestAuditStore {

    void upsert(InboxDigestAudit audit);

    void markDone(String threadId, String itemId, Instant at);

    List<InboxDigestAudit> list(String threadId, int limit);
}
