package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试/单 JVM 好友申请存储；按 (accountId, flag) 分区，账号之间互不覆盖。 */
public final class InMemoryFriendRequestStore implements FriendRequestStore {

    private record Key(String accountId, String flag) {
    }

    private final Map<Key, StoredFriendRequest> byAccountFlag = new LinkedHashMap<>();

    @Override
    public synchronized StoredFriendRequest upsert(String accountId, FriendRequestEvent event) {
        String account = requireAccountId(accountId);
        Key key = new Key(account, event.flag());
        String requestId = "fr-" + Integer.toUnsignedString(
                (account + "\u0000" + event.flag()).hashCode(), 16);
        StoredFriendRequest existing = byAccountFlag.get(key);
        FriendRequestRecord previous = existing == null ? null : existing.request();
        FriendRequestRecord record = new FriendRequestRecord(
                requestId, event.userId(), event.nickname(), event.comment(),
                event.receivedAt(), previous == null ? "PENDING" : previous.status(),
                previous == null ? "" : previous.remark(),
                previous == null ? null : previous.decidedAt());
        StoredFriendRequest stored = new StoredFriendRequest(record, event.flag());
        byAccountFlag.put(key, stored);
        return stored;
    }

    @Override
    public synchronized Optional<StoredFriendRequest> find(String accountId, String requestId) {
        String account = requireAccountId(accountId);
        return byAccountFlag.entrySet().stream()
                .filter(entry -> entry.getKey().accountId().equals(account))
                .map(Map.Entry::getValue)
                .filter(item -> item.request().requestId().equals(requestId))
                .findFirst();
    }

    @Override
    public synchronized List<FriendRequestRecord> listPending(String accountId, int limit) {
        String account = requireAccountId(accountId);
        return byAccountFlag.entrySet().stream()
                .filter(entry -> entry.getKey().accountId().equals(account))
                .map(Map.Entry::getValue)
                .map(StoredFriendRequest::request)
                .filter(item -> "PENDING".equals(item.status()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void markDecided(String accountId, String requestId, String status,
                                          String remark, Instant decidedAt) {
        String account = requireAccountId(accountId);
        byAccountFlag.replaceAll((key, stored) ->
                key.accountId().equals(account)
                        && stored.request().requestId().equals(requestId)
                        ? new StoredFriendRequest(new FriendRequestRecord(
                                stored.request().requestId(), stored.request().userId(),
                                stored.request().displayName(), stored.request().comment(),
                                stored.request().receivedAt(), status, remark, decidedAt),
                                stored.flag())
                        : stored);
    }

    private static String requireAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        return accountId.trim();
    }
}
