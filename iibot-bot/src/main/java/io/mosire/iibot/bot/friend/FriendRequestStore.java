package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 好友申请持久化端口。 */
public interface FriendRequestStore {

    StoredFriendRequest upsert(FriendRequestEvent event);

    Optional<StoredFriendRequest> find(String requestId);

    List<FriendRequestRecord> listPending(int limit);

    void markDecided(String requestId, String status, String remark, Instant decidedAt);
}
