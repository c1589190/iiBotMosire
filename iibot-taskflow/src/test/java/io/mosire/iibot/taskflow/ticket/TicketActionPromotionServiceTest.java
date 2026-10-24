package io.mosire.iibot.taskflow.ticket;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.InMemoryActionPoolStore;
import io.mosire.iibot.taskflow.action.PlayerAction;
import io.mosire.iibot.taskflow.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P5 ACTION_DRAFT ticket → ActionPool 晋升、幂等、冲突与 partial 重试。 */
class TicketActionPromotionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void promoteCreatesQueuedActionLinksTicketAndIsIdempotent() {
        Fixture fixture = fixture(new InMemoryTicketStore());
        String ticketId = fixture.submitActionDraft("第一份行动草稿", "promote-submit-1");

        TicketActionPromotionService.PromotionResult first = fixture.promotion.promote(
                GM, ticketId, 1L, "promote-1");
        assertThat(first.ok()).as(first.toString()).isTrue();
        assertThat(first.actionId()).isNotBlank();
        Ticket ticket = fixture.tickets.getForGm(ticketId).orElseThrow();
        assertThat(ticket.status()).isEqualTo(TicketStatus.CLAIMED);
        assertThat(ticket.revision()).isEqualTo(2);
        assertThat(ticket.relatedActionId()).isEqualTo(first.actionId());
        PlayerAction action = fixture.actions.getForGm(first.actionId()).orElseThrow();
        assertThat(action.status()).isEqualTo(ActionStatus.QUEUED);
        assertThat(action.playerId()).isEqualTo("player-1");
        assertThat(action.text()).isEqualTo("第一份行动草稿");
        assertThat(action.source()).isEqualTo("ticket:" + ticketId);

        TicketActionPromotionService.PromotionResult repeat = fixture.promotion.promote(
                GM, ticketId, 2L, "promote-1-repeat");
        assertThat(repeat.ok()).isTrue();
        assertThat(repeat.actionId()).isEqualTo(first.actionId());
        assertThat(fixture.actions.listForGm()).hasSize(1);

        String secondTicket = fixture.submitActionDraft("第二份行动草稿", "promote-submit-2");
        TicketActionPromotionService.PromotionResult second = fixture.promotion.promote(
                GM, secondTicket, 1L, "promote-2");
        assertThat(second.ok()).isTrue();
        assertThat(second.actionId()).isNotEqualTo(first.actionId());
        assertThat(fixture.actions.listForPlayer("player-1")).hasSize(2);
    }

    @Test
    void nonActionDraftNonGmAndDifferentActionAttachFailClosed() {
        Fixture fixture = fixture(new InMemoryTicketStore());
        String complaint = fixture.tickets.submit(PLAYER, TicketType.COMPLAINT,
                "投诉标题", "投诉正文", "complaint-submit").ticketId();
        TicketActionPromotionService.PromotionResult wrongType = fixture.promotion.promote(
                GM, complaint, 1L, "promote-complaint");
        assertThat(wrongType.code()).isEqualTo(TicketActionPromotionService.INVALID_STATUS);

        String draft = fixture.submitActionDraft("越权草稿", "promote-permission");
        TicketActionPromotionService.PromotionResult wrongActor = fixture.promotion.promote(
                PLAYER, draft, 1L, "promote-by-player");
        assertThat(wrongActor.code()).isEqualTo(TicketActionPromotionService.FORBIDDEN);

        TicketActionPromotionService.PromotionResult promoted = fixture.promotion.promote(
                GM, draft, 1L, "promote-draft");
        assertThat(promoted.ok()).isTrue();
        TicketCommandResult different = fixture.tickets.attachAction(
                GM, draft, 2L, "act-other", "attach-other");
        assertThat(different.ok()).isFalse();
        assertThat(different.code()).isEqualTo(TicketService.CONFLICT);

        // 工单已指向不存在的 action：晋升必须返回可重试 partial，而不是伪造成功。
        String dangling = fixture.submitActionDraft("悬空关联草稿", "promote-dangling");
        assertThat(fixture.tickets.attachAction(
                GM, dangling, 1L, "act-missing", "attach-missing").ok()).isTrue();
        TicketActionPromotionService.PromotionResult partial = fixture.promotion.promote(
                GM, dangling, 2L, "promote-dangling");
        assertThat(partial.ok()).isFalse();
        assertThat(partial.code()).isEqualTo(TicketActionPromotionService.PROMOTION_PARTIAL);
        assertThat(partial.actionId()).isEqualTo("act-missing");
    }

    @Test
    void attachFailureReturnsPartialAndRetryFindsSameActionThenSucceeds() {
        FlakyTicketStore flaky = new FlakyTicketStore();
        Fixture fixture = fixture(flaky);
        String ticketId = fixture.submitActionDraft("partial 重试草稿", "promote-partial-submit");

        flaky.failAttach = true;
        TicketActionPromotionService.PromotionResult partial = fixture.promotion.promote(
                GM, ticketId, 1L, "promote-partial");
        assertThat(partial.ok()).isFalse();
        assertThat(partial.code()).isEqualTo(TicketActionPromotionService.PROMOTION_PARTIAL);
        assertThat(partial.actionId()).isNotBlank();
        assertThat(fixture.actions.findByTicketId(ticketId)).isPresent();
        assertThat(fixture.tickets.getForGm(ticketId).orElseThrow().relatedActionId()).isNull();

        flaky.failAttach = false;
        TicketActionPromotionService.PromotionResult retried = fixture.promotion.promote(
                GM, ticketId, 1L, "promote-partial-retry");
        assertThat(retried.ok()).as(retried.toString()).isTrue();
        assertThat(retried.actionId()).isEqualTo(partial.actionId());
        assertThat(fixture.tickets.getForGm(ticketId).orElseThrow().relatedActionId())
                .isEqualTo(partial.actionId());
        assertThat(fixture.actions.listForGm()).hasSize(1);
    }

    private Fixture fixture(TicketStore ticketStore) {
        TicketService tickets = new TicketService(ticketStore, CLOCK);
        ActionPoolService actions = new ActionPoolService(
                new InMemoryActionPoolStore(), CLOCK);
        return new Fixture(tickets, actions,
                new TicketActionPromotionService(tickets, actions));
    }

    private record Fixture(TicketService tickets, ActionPoolService actions,
                           TicketActionPromotionService promotion) {
        String submitActionDraft(String body, String key) {
            TicketCommandResult result = tickets.submit(PLAYER, TicketType.ACTION_DRAFT,
                    "行动草稿标题", body, key);
            assertThat(result.ok()).isTrue();
            return result.ticketId();
        }
    }

    /** 仅在 attach 保存快照时失败一次，用于验证 PROMOTION_PARTIAL 的可重试语义。 */
    private static final class FlakyTicketStore implements TicketStore {
        private final InMemoryTicketStore delegate = new InMemoryTicketStore();
        private boolean failAttach;

        @Override
        public TicketSnapshot load() {
            return delegate.load();
        }

        @Override
        public void save(TicketSnapshot snapshot) {
            delegate.save(snapshot);
        }

        @Override
        public void save(TicketSnapshot snapshot, DomainEvent event) {
            if (failAttach && snapshot.tickets().stream()
                    .anyMatch(ticket -> ticket.relatedActionId() != null)) {
                throw new IllegalStateException("模拟 attach 保存失败");
            }
            delegate.save(snapshot);
        }
    }
}
