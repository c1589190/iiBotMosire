package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 好友申请持久化端口。
 *
 * <p>所有读写都必须带稳定业务 {@code accountId}：不同 Bot 账号可能收到完全相同的 OneBot
 * {@code flag}，按账号分区才能保证互不覆盖、不会把 A 账号的申请批到 B 账号。</p>
 */
public interface FriendRequestStore {

    StoredFriendRequest upsert(String accountId, FriendRequestEvent event);

    Optional<StoredFriendRequest> find(String accountId, String requestId);

    List<FriendRequestRecord> listPending(String accountId, int limit);

    void markDecided(String accountId, String requestId, String status, String remark,
                     Instant decidedAt);
}
