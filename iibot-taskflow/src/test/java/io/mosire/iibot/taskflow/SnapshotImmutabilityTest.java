package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 冻结后快照只能被读取，后续状态推进不能改变其内容。 */
class SnapshotImmutabilityTest {

    @Test
    void snapshotDoesNotChangeAfterLateCommandsOrAdjudication() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口开放", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-1");
        f.confirm(f.p1, draft, "confirm-1");
        f.lock();

        TurnSnapshot snapshot = f.engine.snapshot().orElseThrow();
        String hash = snapshot.contentHash();
        List<Decision> decisions = snapshot.decisions();
        int eventCount = f.store.size();

        CommandResult lateDraft = f.engine.createDecisionDraft(f.p2,
                new DecisionDraftInput("迟到", List.of("act"), Map.of(), "", "",
                        f.clock.instant().plus(Duration.ofHours(1))),
                f.turnRevision(), "late-p2");
        assertThat(lateDraft.code()).isEqualTo(ErrorCode.LATE_DRAFT);
        assertThat(f.store.size()).isEqualTo(eventCount);

        f.start();
        AdjudicationProposal proposal = AdjudicationProposal.create(1, snapshot.turnId(),
                f.worldVersion(), "结果", Map.of("player-1", "发现线索"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "done")), List.of());
        f.expectOk(f.engine.commitAdjudication(new Actor("gm-1", ActorRole.GM),
                f.turnRevision(), "commit-1", proposal));
        f.expectOk(f.engine.publishTurn(f.director, f.turnRevision(), "publish-1"));

        assertThat(f.engine.snapshot().orElseThrow().contentHash()).isEqualTo(hash);
        assertThat(f.engine.snapshot().orElseThrow().decisions()).isEqualTo(decisions);
        assertThatThrownBy(() -> f.engine.snapshot().orElseThrow().decisions().add(decisions.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
