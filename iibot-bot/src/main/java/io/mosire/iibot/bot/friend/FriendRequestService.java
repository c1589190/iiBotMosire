package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestCapability;
import io.mosire.iibot.connect.core.FriendRequestEvent;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.ops.OpsLog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 入站好友申请持久化与审批；不实现主动加好友。 */
public final class FriendRequestService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";

    private final FriendRequestStore store;
    private final FriendRequestCapability provider;
    private final Clock clock;

    public FriendRequestService(FriendRequestStore store,
                                FriendRequestCapability provider,
                                Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FriendRequestRecord receive(FriendRequestEvent event) {
        FriendRequestRecord request = store.upsert(event).request();
        OpsLog.info("event=friend_request_received requestId={} userId={} status={}",
                request.requestId(), request.userId(), request.status());
        return request;
    }

    public List<FriendRequestRecord> listPending(int limit) {
        return store.listPending(limit);
    }

    public FriendRequestDecision decide(String requestId, boolean approve, String remark) {
        StoredFriendRequest stored = store.find(requestId).orElseThrow(() ->
                new IllegalArgumentException("好友申请不存在"));
        FriendRequestRecord request = stored.request();
        if (!PENDING.equals(request.status())) {
            OpsLog.info("event=friend_request_decided requestId={} outcome=already_done "
                            + "status={}", request.requestId(), request.status());
            return new FriendRequestDecision(request.requestId(), request.status(),
                    true, "ALREADY_DONE", "");
        }
        SendReceipt receipt;
        try {
            receipt = provider.decideFriendRequest(
                    stored.flag(), approve, remark == null ? "" : remark)
                    .toCompletableFuture().join();
        } catch (RuntimeException failure) {
            store.markDecided(request.requestId(), UNKNOWN, remark == null ? "" : remark,
                    clock.instant());
            OpsLog.error("event=friend_request_decided requestId={} outcome=unknown "
                            + "state=UNKNOWN code=FRIEND_REQUEST_UNKNOWN errorType={}",
                    request.requestId(), failure.getClass().getSimpleName());
            return new FriendRequestDecision(request.requestId(), UNKNOWN,
                    false, "UNKNOWN", "FRIEND_REQUEST_UNKNOWN");
        }
        String status = switch (receipt.state()) {
            case SENT -> approve ? APPROVED : REJECTED;
            case FAILED_BEFORE_SEND -> PENDING;
            case UNKNOWN -> UNKNOWN;
        };
        if (!PENDING.equals(status)) {
            store.markDecided(request.requestId(), status,
                    remark == null ? "" : remark, clock.instant());
        }
        OpsLog.info("event=friend_request_decided requestId={} outcome={} status={} code={}",
                request.requestId(), approve ? "approve" : "reject", status,
                receipt.errorCode() == null ? "" : receipt.errorCode());
        return new FriendRequestDecision(request.requestId(), status, false,
                receipt.state().name(), receipt.errorCode() == null ? "" : receipt.errorCode());
    }
}
