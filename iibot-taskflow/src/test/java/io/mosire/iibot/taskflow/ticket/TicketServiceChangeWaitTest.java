package io.mosire.iibot.taskflow.ticket;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 工单板长轮询依赖的变更版本、唤醒和超时语义。 */
class TicketServiceChangeWaitTest {

    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void newTicketWakesWaiterAndAdvancesVersion() throws Exception {
        TicketService service = new TicketService(new MemoryTicketStore());
        TicketService.StateView initial = service.stateView();

        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return service.awaitChange(initial.version(), Duration.ofSeconds(3));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        Thread.sleep(100);
        assertThat(waiter.isDone()).isFalse();

        TicketCommandResult submitted = service.submit(PLAYER, TicketType.SETTING_QUERY,
                "报名问题", "我想加入", "wait-new-ticket");

        assertThat(submitted.ok()).isTrue();
        assertThat(waiter.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.stateView().version()).isGreaterThan(initial.version());
        assertThat(service.snapshot().tickets()).hasSize(1);
    }

    @Test
    void timeoutReturnsFalseAndInterruptedFlagIsPreservedByCaller() throws Exception {
        TicketService service = new TicketService(new MemoryTicketStore());
        long version = service.stateView().version();

        assertThat(service.awaitChange(version, Duration.ofMillis(80))).isFalse();
    }

    @Test
    void replyOnExistingTicketAlsoAdvancesVersion() {
        TicketService service = new TicketService(new MemoryTicketStore());
        String ticketId = service.submit(PLAYER, TicketType.SETTING_QUERY,
                "问题", "正文", "wait-reply-create").ticketId();
        long before = service.stateView().version();

        assertThat(service.claim(GM, ticketId, 1, "wait-reply-claim").ok()).isTrue();
        // claim 本身也应推进版本；这里用回复再验证一次。
        long afterClaim = service.stateView().version();
        assertThat(afterClaim).isGreaterThan(before);
        assertThat(service.reply(GM, ticketId, 2, "回复正文",
                "wait-reply-reply").ok()).isTrue();
        assertThat(service.stateView().version()).isGreaterThan(afterClaim);
    }

    private static final class MemoryTicketStore implements TicketStore {
        private TicketSnapshot state = TicketSnapshot.empty();

        @Override
        public TicketSnapshot load() {
            return state;
        }

        @Override
        public void save(TicketSnapshot snapshot) {
            state = snapshot;
        }
    }
}
