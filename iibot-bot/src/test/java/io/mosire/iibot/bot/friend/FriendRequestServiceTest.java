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
        FriendRequestService service = new FriendRequestService(store, provider, CLOCK);
        FriendRequestRecord request = service.receive(new FriendRequestEvent(
                "raw-flag", "qq-2", "Bob", "你好", T0));

        assertThat(request.requestId()).doesNotContain("raw-flag");
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
}
