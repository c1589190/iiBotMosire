package io.mosire.iibot.taskflow.action;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionPoolServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor P1 = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor P2 = new Actor("player-2", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void submitNeedsNoTurnAndUsesPlayerActorIdentity() {
        ActionPoolService service = new ActionPoolService(CLOCK);

        PlayerAction action = service.submit(P1, "我去北门找守卫打听消息。", "private-chat", "k1");

        assertThat(action.actionId()).startsWith("act-");
        assertThat(action.playerId()).isEqualTo("player-1");
        assertThat(action.status()).isEqualTo(ActionStatus.QUEUED);
        assertThat(action.packageId()).isNull();
        assertThat(action.createdAt()).isEqualTo(CLOCK.instant());
        assertThat(action.updatedAt()).isEqualTo(CLOCK.instant());
        assertThat(service.listForPlayer("player-1")).containsExactly(action);
        assertThat(action.toString()).doesNotContain("北门找守卫").contains("textLength");
    }

    @Test
    void playersAreIsolatedAndMaySubmitMultipleActions() {
        ActionPoolService service = new ActionPoolService(CLOCK);
        PlayerAction a1 = service.submit(P1, "行动一", "chat", "a");
        PlayerAction a2 = service.submit(P1, "行动二", "chat", "b");
        PlayerAction b1 = service.submit(P2, "别的行动", "chat", "c");

        assertThat(service.listForPlayer("player-1")).containsExactly(a1, a2);
        assertThat(service.listForPlayer("player-2")).containsExactly(b1);
        assertThat(service.listForGm(ActionStatus.QUEUED))
                .extracting(PlayerAction::actionId)
                .containsExactly(a1.actionId(), a2.actionId(), b1.actionId());
        assertThat(service.getForGm(a1.actionId())).contains(a1);
        assertThatThrownBy(() -> service.submit(GM, "gm text", "chat", "d"))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.FORBIDDEN));
    }

    @Test
    void duplicateIdempotencyKeyReturnsFirstResultPerPlayer() {
        ActionPoolService service = new ActionPoolService(CLOCK);

        PlayerAction first = service.submit(P1, "第一次正文", "chat", "same");
        PlayerAction repeat = service.submit(P1, "完全不同的第二次正文", "chat", "same");
        PlayerAction otherPlayer = service.submit(P2, "玩家二正文", "chat", "same");

        assertThat(repeat).isEqualTo(first);
        assertThat(otherPlayer.actionId()).isNotEqualTo(first.actionId());
        assertThat(service.listForPlayer("player-1")).hasSize(1);
        assertThat(service.listForPlayer("player-2")).hasSize(1);
        ActionPoolSnapshot snapshot = service.snapshot();
        assertThat(snapshot.idempotentActionId("player-1", "same"))
                .contains(first.actionId());
        assertThat(snapshot.idempotentActionId("player-2", "same"))
                .contains(otherPlayer.actionId());
    }

    @Test
    void textMustBeOneByteToSixteenKiB() {
        ActionPoolService service = new ActionPoolService(CLOCK);
        String max = "a".repeat(ActionPoolService.MAX_TEXT_BYTES);

        assertThat(service.submit(P1, max, "chat", "max").text()).hasSize(max.length());
        assertThatThrownBy(() -> service.submit(P1, "a".repeat(
                ActionPoolService.MAX_TEXT_BYTES + 1), "chat", "over"))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.INVALID_ARGUMENTS));
        assertThatThrownBy(() -> service.submit(P1, "  ", "chat", "blank"))
                .isInstanceOf(ActionPoolException.class);
    }

    @Test
    void withdrawOnlyOwnQueuedActionAndSelectedIsBlocked() {
        ActionPoolService service = new ActionPoolService(CLOCK);
        PlayerAction own = service.submit(P1, "自己的行动", "chat", "submit-1");
        PlayerAction other = service.submit(P2, "别人的行动", "chat", "submit-2");

        assertThatThrownBy(() -> service.withdraw(P1, other.actionId(), "w-other"))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.FORBIDDEN));

        service.markSelected(own.actionId(), "pkg-1");
        assertThatThrownBy(() -> service.withdraw(P1, own.actionId(), "w-selected"))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.INVALID_STATUS));

        service.releaseSelected(own.actionId());
        PlayerAction withdrawn = service.withdraw(P1, own.actionId(), "w-queued");
        assertThat(withdrawn.status()).isEqualTo(ActionStatus.WITHDRAWN);
        assertThat(service.withdraw(P1, own.actionId(), "w-repeat")).isEqualTo(withdrawn);
    }

    @Test
    void hostTransitionsFailClosedOnWrongStatus() {
        ActionPoolService service = new ActionPoolService(CLOCK);
        PlayerAction action = service.submit(P1, "行动", "chat", "submit");

        assertThatThrownBy(() -> service.releaseSelected(action.actionId()))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.INVALID_STATUS));
        service.markSelected(action.actionId(), "pkg-1");
        assertThatThrownBy(() -> service.markSelected(action.actionId(), "pkg-2"))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.INVALID_STATUS));
        PlayerAction committed = service.markCommitted(action.actionId());
        assertThat(committed.status()).isEqualTo(ActionStatus.COMMITTED);
        assertThat(committed.packageId()).isEqualTo("pkg-1");
    }

    @Test
    void storeSaveFailureDoesNotReplaceInMemorySnapshot() {
        AtomicInteger saves = new AtomicInteger();
        ActionPoolStore failing = new ActionPoolStore() {
            @Override
            public ActionPoolSnapshot load() {
                return ActionPoolSnapshot.empty();
            }

            @Override
            public void save(ActionPoolSnapshot snapshot) {
                saves.incrementAndGet();
                throw new IllegalStateException("disk full");
            }
        };
        ActionPoolService service = new ActionPoolService(failing, CLOCK);

        assertThatThrownBy(() -> service.submit(P1, "不会落定", "chat", "k"))
                .isInstanceOfSatisfying(ActionPoolException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ActionPoolException.STORE_UNAVAILABLE));
        assertThat(saves).hasValue(1);
        assertThat(service.snapshot().actions()).isEmpty();
        assertThat(service.listForPlayer("player-1")).isEmpty();
    }

    @Test
    void snapshotIsImmutableAndExposesIdempotentIndex() {
        ActionPoolService service = new ActionPoolService(CLOCK);
        PlayerAction action = service.submit(P1, "正文", "chat", "key");
        ActionPoolSnapshot snapshot = service.snapshot();

        assertThat(snapshot.actions()).containsExactly(action);
        assertThat(snapshot.idempotentResults()).containsKey("player-1");
        assertThatThrownBy(() -> snapshot.actions().add(action))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.idempotentResults()
                .put("player-x", Map.of("k", "v")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
