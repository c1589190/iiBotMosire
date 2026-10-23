package io.mosire.iibot.taskflow;

import io.mosire.iibot.taskflow.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 正常全流程必须在一个回合内完成，并把关键事件全部写入事件流。 */
class TaskflowHappyPathTest {

    @Test
    void fullFlowRegistersOpensConfirmsLocksAdjudicatesAndPublishes() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("public-1", 1, "港口的钟声响起", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "调查港口", "draft-1");
        Decision decision = f.confirm(f.p1, draft, "confirm-1");

        assertThat(decision.playerId()).isEqualTo("player-1");
        assertThat(decision.confirmedByPlayerId()).isEqualTo("player-1");
        assertThat(decision.contentHash()).isNotBlank();

        f.lock();
        TurnSnapshot frozen = f.engine.snapshot().orElseThrow();
        assertThat(f.turnView().state()).isEqualTo(TurnState.LOCKED);
        assertThat(frozen.decisions()).containsExactly(decision);

        f.start();
        assertThat(f.turnView().state()).isEqualTo(TurnState.ADJUDICATING);

        long worldBeforeCommit = f.worldVersion();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION,
                f.turnView().turnId(),
                worldBeforeCommit,
                "港口事件尘埃落定。",
                Map.of("player-1", "你找到了线索。"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "investigated")),
                List.of("潮汐将在下一回合改变"));
        CommandResult committed = f.expectOk(f.engine.commitAdjudication(
                new Actor("gm-1", ActorRole.GM), f.turnRevision(), "commit-1", proposal));

        assertThat(committed.turnState()).isEqualTo(TurnState.COMMITTED);
        assertThat(f.worldVersion()).isEqualTo(worldBeforeCommit + 1);
        assertThat(f.engine.snapshot().orElseThrow()).isEqualTo(frozen);

        f.expectOk(f.engine.publishTurn(f.director, f.turnRevision(), "publish-1"));
        assertThat(f.turnView().state()).isEqualTo(TurnState.PUBLISHED);
        assertThat(f.engine.snapshot().orElseThrow()).isEqualTo(frozen);

        assertThat(f.store.events())
                .extracting(e -> e.type())
                .contains(EventType.ACTOR_REGISTERED,
                        EventType.WORLD_FACTS_LOADED,
                        EventType.TURN_OPENED,
                        EventType.DRAFT_CREATED,
                        EventType.DECISION_CONFIRMED,
                        EventType.TURN_LOCKED,
                        EventType.ADJUDICATION_STARTED,
                        EventType.ADJUDICATION_COMMITTED,
                        EventType.TURN_PUBLISHED);
        assertThat(f.store.events()).extracting(e -> e.seq())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(
                        1, f.store.size()).boxed().toList());
        assertThat(f.store.events()).extracting(e -> e.idempotencyKey()).doesNotHaveDuplicates();
    }
}
