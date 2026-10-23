package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 推演失败进入可重试态；重试复用冻结快照，不改变世界版本。 */
class FailureRetryFlowTest {

    @Test
    void failedAdjudicationCanRetryThenCommitAndPublish() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-1");
        f.confirm(f.p1, draft, "confirm-1");
        f.lock();
        f.start();

        TurnSnapshot snapshot = f.engine.snapshot().orElseThrow();
        long worldBefore = f.worldVersion();
        CommandResult failed = f.expectOk(f.engine.failAdjudication(f.director,
                "provider timeout", f.turnRevision(), "fail-1"));
        assertThat(failed.turnState()).isEqualTo(TurnState.FAILED_RETRYABLE);
        assertThat(f.worldVersion()).isEqualTo(worldBefore);
        assertThat(f.engine.snapshot().orElseThrow()).isEqualTo(snapshot);

        CommandResult retried = f.expectOk(f.engine.retryAdjudication(f.director,
                f.turnRevision(), "retry-1"));
        assertThat(retried.turnState()).isEqualTo(TurnState.ADJUDICATING);
        assertThat(f.engine.snapshot().orElseThrow()).isEqualTo(snapshot);

        AdjudicationProposal proposal = AdjudicationProposal.create(1, snapshot.turnId(),
                worldBefore, "重试后成功", Map.of("player-1", "结果"), List.of(), List.of());
        f.expectOk(f.engine.commitAdjudication(new Actor("gm-1", ActorRole.GM),
                f.turnRevision(), "commit-after-retry", proposal));
        f.expectOk(f.engine.publishTurn(f.director, f.turnRevision(), "publish-after-retry"));

        assertThat(f.worldVersion()).isEqualTo(worldBefore + 1);
        assertThat(f.turnView().state()).isEqualTo(TurnState.PUBLISHED);
        assertThat(f.store.events()).extracting(e -> e.type())
                .contains(io.mosire.iibot.taskflow.event.EventType.ADJUDICATION_FAILED,
                        io.mosire.iibot.taskflow.event.EventType.ADJUDICATION_RETRY_STARTED);
    }
}
