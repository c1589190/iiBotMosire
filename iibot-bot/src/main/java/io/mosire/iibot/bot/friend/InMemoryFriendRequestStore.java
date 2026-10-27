package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试/单 JVM 好友申请存储。 */
public final class InMemoryFriendRequestStore implements FriendRequestStore {

    private final Map<String, StoredFriendRequest> byFlag = new LinkedHashMap<>();

    @Override
    public synchronized StoredFriendRequest upsert(FriendRequestEvent event) {
        String requestId = "fr-" + Integer.toUnsignedString(event.flag().hashCode(), 16);
        FriendRequestRecord existing = byFlag.containsKey(event.flag())
                ? byFlag.get(event.flag()).request() : null;
        FriendRequestRecord record = new FriendRequestRecord(
                requestId, event.userId(), event.nickname(), event.comment(),
                event.receivedAt(), existing == null ? "PENDING" : existing.status(),
                existing == null ? "" : existing.remark(),
                existing == null ? null : existing.decidedAt());
        StoredFriendRequest stored = new StoredFriendRequest(record, event.flag());
        byFlag.put(event.flag(), stored);
        return stored;
    }

    @Override
    public synchronized Optional<StoredFriendRequest> find(String requestId) {
        return byFlag.values().stream()
                .filter(item -> item.request().requestId().equals(requestId))
                .findFirst();
    }

    @Override
    public synchronized List<FriendRequestRecord> listPending(int limit) {
        return byFlag.values().stream()
                .map(StoredFriendRequest::request)
                .filter(item -> "PENDING".equals(item.status()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void markDecided(String requestId, String status,
                                          String remark, Instant decidedAt) {
        byFlag.replaceAll((flag, stored) -> stored.request().requestId().equals(requestId)
                ? new StoredFriendRequest(new FriendRequestRecord(
                        stored.request().requestId(), stored.request().userId(),
                        stored.request().displayName(), stored.request().comment(),
                        stored.request().receivedAt(), status, remark, decidedAt), stored.flag())
                : stored);
    }
}
