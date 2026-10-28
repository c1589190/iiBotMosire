package io.mosire.iibot.bot.friend;

import io.mosire.iibot.connect.core.FriendRequestCapability;
import io.mosire.iibot.connect.core.FriendRequestEvent;
import io.mosire.iibot.connect.core.FriendRequestHandler;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class FriendRequestServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void listsOpaqueRequestIdAndApprovesOnce() {
        InMemoryFriendRequestStore store = new InMemoryFriendRequestStore();
        FakeCapability provider = new FakeCapability();
        FriendRequestService service = new FriendRequestService("bot-1", store, provider, CLOCK);
        FriendRequestRecord request = service.receive(new FriendRequestEvent(
                "raw-flag", "qq-2", "Bob", "你好", T0));

        assertThat(request.requestId()).doesNotContain("raw-flag");
        assertThat(service.accountId()).isEqualTo("bot-1");
        assertThat(service.listPending(10)).extracting(FriendRequestRecord::requestId)
                .containsExactly(request.requestId());

        FriendRequestDecision first = service.decide(request.requestId(), true, "备注");
        FriendRequestDecision second = service.decide(request.requestId(), true, "备注");

        assertThat(first.status()).isEqualTo(FriendRequestService.APPROVED);
        assertThat(first.idempotent()).isFalse();
        assertThat(second.idempotent()).isTrue();
        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.flag).isEqualTo("raw-flag");
    }

    @Test
    void sameFlagOnTwoAccountsStaysIsolated() {
        InMemoryFriendRequestStore store = new InMemoryFriendRequestStore();
        FakeCapability capabilityA = new FakeCapability();
        FakeCapability capabilityB = new FakeCapability();
        FriendRequestService serviceA = new FriendRequestService("bot-a", store, capabilityA, CLOCK);
        FriendRequestService serviceB = new FriendRequestService("bot-b", store, capabilityB, CLOCK);

        FriendRequestRecord requestA = serviceA.receive(new FriendRequestEvent(
                "flag-shared", "qq-9", "Carol", "来自 A", T0));
        FriendRequestRecord requestB = serviceB.receive(new FriendRequestEvent(
                "flag-shared", "qq-9", "Carol", "来自 B", T0));

        assertThat(requestA.requestId()).isNotEqualTo(requestB.requestId());
        assertThat(serviceA.listPending(10)).extracting(FriendRequestRecord::requestId)
                .containsExactly(requestA.requestId());
        assertThat(serviceB.listPending(10)).extracting(FriendRequestRecord::requestId)
                .containsExactly(requestB.requestId());

        FriendRequestDecision decidedA = serviceA.decide(requestA.requestId(), true, "A 备注");
        assertThat(decidedA.status()).isEqualTo(FriendRequestService.APPROVED);
        assertThat(capabilityA.calls).isEqualTo(1);
        assertThat(capabilityB.calls).isZero();
        assertThat(serviceA.listPending(10)).isEmpty();
        assertThat(serviceB.listPending(10)).hasSize(1);

        // A 的 requestId 在 B 账号下不可见，也不会误批 B 的申请。
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> serviceB.decide(requestA.requestId(), true, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThat(capabilityB.calls).isZero();
    }

    @Test
    void unknownDecisionIsNotRetriedAutomatically() {
        InMemoryFriendRequestStore store = new InMemoryFriendRequestStore();
        FailingCapability capability = new FailingCapability();
        FriendRequestService service = new FriendRequestService("bot-1", store, capability, CLOCK);
        FriendRequestRecord request = service.receive(new FriendRequestEvent(
                "flag-unknown", "qq-7", "Dave", "", T0));

        FriendRequestDecision first = service.decide(request.requestId(), true, "");
        FriendRequestDecision second = service.decide(request.requestId(), true, "");

        assertThat(first.status()).isEqualTo(FriendRequestService.UNKNOWN);
        assertThat(first.errorCode()).isEqualTo("FRIEND_REQUEST_UNKNOWN");
        assertThat(first.idempotent()).isFalse();
        assertThat(second.idempotent()).isTrue();
        assertThat(capability.calls).isEqualTo(1);
    }

    private static final class FakeCapability implements FriendRequestCapability {
        private int calls;
        private String flag;

        @Override
        public void setFriendRequestHandler(FriendRequestHandler handler) {
        }

        @Override
        public CompletableFuture<SendReceipt> decideFriendRequest(
                String flag, boolean approve, String remark) {
            calls++;
            this.flag = flag;
            return CompletableFuture.completedFuture(
                    SendReceipt.sent(List.of("friend-request")));
        }
    }

    private static final class FailingCapability implements FriendRequestCapability {
        private int calls;

        @Override
        public void setFriendRequestHandler(FriendRequestHandler handler) {
        }

        @Override
        public CompletableFuture<SendReceipt> decideFriendRequest(
                String flag, boolean approve, String remark) {
            calls++;
            return CompletableFuture.failedFuture(new IllegalStateException("传输中断"));
        }
    }
}
