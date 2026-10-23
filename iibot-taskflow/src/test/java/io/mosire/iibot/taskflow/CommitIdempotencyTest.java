package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 成功 commit 只增加一次世界版本；重复 key 不得再次生效。 */
class CommitIdempotencyTest {

    @Test
    void commitIncrementsWorldVersionExactlyOnceAndDuplicateDoesNothing() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-1");
        f.confirm(f.p1, draft, "confirm-1");
        f.lock();
        f.start();
        long worldBefore = f.worldVersion();
        long revision = f.turnRevision();
        int eventsBefore = f.store.size();
        Actor gm = new Actor("gm-1", ActorRole.GM);
        AdjudicationProposal proposal = AdjudicationProposal.create(1, f.turnView().turnId(),
                worldBefore, "结果", Map.of("player-1", "发现线索"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "done")), List.of("下一步"));

        CommandResult first = f.expectOk(f.engine.commitAdjudication(gm, revision,
                "commit-once", proposal));
        assertThat(f.worldVersion()).isEqualTo(worldBefore + 1);
        assertThat(f.turnView().state()).isEqualTo(TurnState.COMMITTED);
        assertThat(f.store.size()).isEqualTo(eventsBefore + 1);

        CommandResult duplicate = f.engine.commitAdjudication(gm, revision,
                "commit-once", proposal);
        assertThat(duplicate).isEqualTo(first);
        assertThat(f.worldVersion()).isEqualTo(worldBefore + 1);
        assertThat(f.store.size()).isEqualTo(eventsBefore + 1);

        CommandResult secondDifferentKey = f.engine.commitAdjudication(gm, f.turnRevision(),
                "commit-second-key", proposal);
        assertThat(secondDifferentKey.code()).isEqualTo(ErrorCode.TURN_ALREADY_COMMITTED);
        assertThat(f.worldVersion()).isEqualTo(worldBefore + 1);
        assertThat(f.store.size()).isEqualTo(eventsBefore + 1);
        assertThat(f.turnView().committedProposal()).isEqualTo(proposal);
    }
}
