package io.mosire.iibot.app.delivery;

import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TurnResultDistributorTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void publishesPublicNarrativeAndEachPlayerOutcomeOnce() {
        TaskflowEngine engine = publishedEngine();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        TurnResultDistributor distributor = new TurnResultDistributor(engine,
                new OutboxSender(adapter, Duration.ofSeconds(2)), "group-public",
                Map.of("player-1", "qq-p1"));

        assertThat(distributor.distributeIfReady()).hasSize(2);
        assertThat(distributor.distributeIfReady()).isEmpty();

        assertThat(adapter.sent()).hasSize(2);
        assertThat(adapter.sent().get(0).targetType()).isEqualTo(OutboundMessage.TargetType.GROUP);
        assertThat(adapter.sent().get(0).targetId()).isEqualTo("group-public");
        assertThat(adapter.sent().get(0).textSegments()).containsExactly("港口恢复了平静。");
        assertThat(adapter.sent().get(1).targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
        assertThat(adapter.sent().get(1).targetId()).isEqualTo("qq-p1");
        assertThat(adapter.sent().get(1).textSegments()).containsExactly("你找到了航海日志。");
        assertThat(adapter.sent().get(0).idempotencyKey())
                .isNotEqualTo(adapter.sent().get(1).idempotencyKey());
    }

    @Test
    void unknownSendIsNotRetriedAndDoesNotRollbackPublishedTurn() {
        TaskflowEngine engine = publishedEngine();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter(message ->
                CompletableFuture.completedFuture(SendReceipt.unknown("TIMEOUT", "unknown")));
        TurnResultDistributor distributor = new TurnResultDistributor(engine,
                new OutboxSender(adapter, Duration.ofSeconds(2)), "group-public",
                Map.of("player-1", "qq-p1"));

        assertThat(distributor.distributeIfReady()).hasSize(2);
        assertThat(distributor.distributeIfReady()).isEmpty();
        assertThat(adapter.sent()).hasSize(2);
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.PUBLISHED);
    }

    private static TaskflowEngine publishedEngine() {
        InMemoryEventStore events = new InMemoryEventStore();
        TaskflowEngine engine = new TaskflowEngine(CLOCK, events);
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        Actor player = new Actor("player-1", ActorRole.PLAYER);

        ok(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "reg-director"));
        ok(engine.registerPlayer(director,
                new PlayerProfile("player-1", "qq-p1", "Player One"),
                engine.status().engineRevision(), "reg-player"));
        ok(engine.loadWorldFacts(director, 0,
                List.of(new WorldFact("fact-1", 1, "港口", Visibility.PUBLIC)),
                engine.status().engineRevision(), "load-world"));
        ok(engine.openTurn(director, engine.status().engineRevision(), "open",
                NOW.plus(Duration.ofMinutes(30))));

        TurnView opening = engine.turnView().orElseThrow();
        CommandResult draftResult = engine.createDecisionDraft(player,
                new DecisionDraftInput("调查港口", List.of("询问船工"), Map.of("people", "2"),
                        "北码头", "遇险撤回", NOW.plus(Duration.ofMinutes(20))),
                opening.revision(), "draft");
        ok(draftResult);
        DecisionDraft draft = engine.draft(draftResult.referenceId().orElseThrow()).orElseThrow();
        TurnView afterDraft = engine.turnView().orElseThrow();
        ok(engine.confirmDecision(player, draft.draftId(), draft.previewHash(),
                afterDraft.revision(), "confirm"));

        TurnView afterConfirm = engine.turnView().orElseThrow();
        ok(engine.lockTurn(director, afterConfirm.revision(), "lock"));
        TurnView locked = engine.turnView().orElseThrow();
        ok(engine.startAdjudication(director, locked.revision(), "start"));
        TurnView adjudicating = engine.turnView().orElseThrow();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION, adjudicating.turnId(),
                engine.status().worldVersion(), "港口恢复了平静。",
                Map.of("player-1", "你找到了航海日志。"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "calm")),
                List.of("潮汐将在下一回合改变"));
        ok(engine.commitAdjudication(director, adjudicating.revision(), "commit", proposal));
        TurnView committed = engine.turnView().orElseThrow();
        ok(engine.publishTurn(director, committed.revision(), "publish"));
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.PUBLISHED);
        return engine;
    }

    private static void ok(CommandResult result) {
        assertThat(result.ok()).as("command %s: %s", result.code(), result.message()).isTrue();
    }
}
