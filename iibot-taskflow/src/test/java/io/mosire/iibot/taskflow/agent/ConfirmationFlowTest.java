package io.mosire.iibot.taskflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.ConfirmationCodes;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.ticket.InMemoryTicketStore;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ConfirmationFlowTest {

    @Test
    void decisionShortCodeConfirmsOnceAndReplyHasNoInternalIds() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("public-1", 1, "港口公开", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraftDirect(f.p1, "调查北码头");
        String code = ConfirmationCodes.forDecisionDraft(draft.draftId());
        InteractionTaskflowService service = service(f, null);

        List<InteractionReply> first = service.handle(
                privateMessage("qq-p1", "/确认 " + code, "confirm-short-1"));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).text())
                .contains("已确认")
                .doesNotContain(draft.draftId())
                .doesNotContain("decisionId")
                .doesNotContain("contentHash")
                .doesNotContain("turnId");

        List<InteractionReply> again = service.handle(
                privateMessage("qq-p1", "/确认 " + code, "confirm-short-2"));
        assertThat(again).hasSize(1);
        assertThat(again.get(0).text()).contains("已经确认过");
        assertThat(f.turn().decisions()).hasSize(1);
    }

    @Test
    void actionDraftTicketMustBeConfirmedBeforePromotion() {
        AgentTestFixture f = AgentTestFixture.create();
        TicketService tickets = new TicketService(new InMemoryTicketStore(), f.clock);
        TicketCommandResult submitted = tickets.submit(f.p1, TicketType.ACTION_DRAFT,
                "行动草案", "调查北码头", "ticket-submit");
        assertThat(submitted.ok()).isTrue();
        assertThat(tickets.getForGm(submitted.ticketId()).orElseThrow().status())
                .isEqualTo(TicketStatus.OPEN);

        InteractionTaskflowService service = service(f, tickets);
        String code = ConfirmationCodes.forActionTicket(submitted.ticketId());
        List<InteractionReply> confirmed = service.handle(
                privateMessage("qq-p1", "/确认 " + code, "confirm-ticket-short"));

        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.get(0).text())
                .contains("已确认")
                .doesNotContain(submitted.ticketId())
                .doesNotContain("ticketId");
        assertThat(tickets.getForGm(submitted.ticketId()).orElseThrow().status())
                .isEqualTo(TicketStatus.CONFIRMED);

        List<InteractionReply> again = service.handle(
                privateMessage("qq-p1", "/确认 " + code, "confirm-ticket-short-2"));
        assertThat(again.get(0).text()).contains("已经确认过");
    }

    private static InteractionTaskflowService service(AgentTestFixture f, TicketService tickets) {
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        registry.register(new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15)));
        registry.register(new SecretarySession("player-2", f.p2, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15)));
        AdjudicationService adjudication = new AdjudicationService(f.engine,
                ScriptedLlm.with(LlmResponse.text("unused")));
        return new InteractionTaskflowService(f.engine,
                IdentityResolver.fromMap(f.identities()), registry,
                adjudication, () -> f.loadedFacts, "group-public", f.clock, 240,
                f.director, tickets);
    }

    private static InboundMessage privateMessage(String qqId, String text, String dedupe) {
        return new InboundMessage("adapter-1", "bot-1", "msg-" + UUID.randomUUID(),
                qqId, InboundMessage.SessionType.PRIVATE, null, text,
                AgentTestFixture.BASE, dedupe);
    }
}
