package io.mosire.iibot.bot.inbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试/单 JVM 用审计存储。 */
public final class InMemoryInboxDigestAuditStore implements InboxDigestAuditStore {

    private final Map<String, InboxDigestAudit> byItem = new LinkedHashMap<>();

    @Override
    public synchronized void upsert(InboxDigestAudit audit) {
        byItem.put(audit.itemId(), audit);
    }

    @Override
    public synchronized void markDone(String threadId, String itemId, Instant at) {
        InboxDigestAudit current = byItem.get(itemId);
        if (current != null && current.threadId().equals(threadId)) {
            byItem.put(itemId, current.withDone(at));
        }
    }

    @Override
    public synchronized List<InboxDigestAudit> list(String threadId, int limit) {
        List<InboxDigestAudit> result = new ArrayList<>();
        for (InboxDigestAudit audit : byItem.values()) {
            if (threadId == null || threadId.isBlank() || audit.threadId().equals(threadId)) {
                result.add(audit);
            }
        }
        int from = Math.max(0, result.size() - limit);
        return List.copyOf(result.subList(from, result.size()));
    }
}
