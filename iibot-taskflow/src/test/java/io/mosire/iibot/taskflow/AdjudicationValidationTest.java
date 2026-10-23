package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 非法 GM 提案必须在任何事件/世界版本变化之前被拒绝。 */
class AdjudicationValidationTest {

    private AdjudicationProposal proposal(EngineFixture f, long expectedWorldVersion,
                                          Map<String, String> outcomes,
                                          List<WorldDelta> deltas) {
        return AdjudicationProposal.create(AdjudicationProposal.CURRENT_SCHEMA_VERSION,
                f.turnView().turnId(), expectedWorldVersion, "公开叙述", outcomes, deltas, List.of());
    }

    @Test
    void invalidProposalsProduceZeroWorldChange() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-1");
        f.confirm(f.p1, draft, "confirm-1");
        f.lock();
        f.start();
        long worldBefore = f.worldVersion();
        long turnRevision = f.turnRevision();
        TurnSnapshot snapshot = f.engine.snapshot().orElseThrow();
        int eventsBefore = f.store.size();
        Actor gm = new Actor("gm-1", ActorRole.GM);

        CommandResult unknownPlayer = f.engine.commitAdjudication(gm, turnRevision,
                "bad-unknown-player", proposal(f, worldBefore, Map.of("ghost", "偷偷行动"),
                        List.of(new WorldDelta("SET_FLAG", "harbor", "x"))));
        assertThat(unknownPlayer.code()).isEqualTo(ErrorCode.UNKNOWN_PLAYER);

        CommandResult staleWorld = f.engine.commitAdjudication(gm, turnRevision,
                "bad-stale-world", proposal(f, worldBefore - 1, Map.of("player-1", "结果"),
                        List.of(new WorldDelta("SET_FLAG", "harbor", "x"))));
        assertThat(staleWorld.code()).isEqualTo(ErrorCode.WORLD_VERSION_MISMATCH);

        CommandResult unknownDelta = f.engine.commitAdjudication(gm, turnRevision,
                "bad-delta-type", proposal(f, worldBefore, Map.of("player-1", "结果"),
                        List.of(new WorldDelta("DROP_TABLE", "harbor", "x"))));
        assertThat(unknownDelta.code()).isEqualTo(ErrorCode.WORLD_DELTA_NOT_ALLOWED);

        List<WorldDelta> tooManyDeltas = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            tooManyDeltas.add(new WorldDelta("SET_FLAG", "target-" + i, "v"));
        }
        CommandResult tooLarge = f.engine.commitAdjudication(gm, turnRevision,
                "bad-delta-size", proposal(f, worldBefore, Map.of("player-1", "结果"),
                        tooManyDeltas));
        assertThat(tooLarge.code()).isEqualTo(ErrorCode.PROPOSAL_TOO_LARGE);

        AdjudicationProposal validForTamper = proposal(f, worldBefore,
                Map.of("player-1", "结果"), List.of());
        AdjudicationProposal tampered = new AdjudicationProposal(validForTamper.schemaVersion(),
                validForTamper.turnId(), validForTamper.expectedWorldVersion(),
                "被改过的叙述", validForTamper.playerOutcomes(), validForTamper.worldDeltas(),
                validForTamper.nextHooks(), validForTamper.proposalHash());
        CommandResult badHash = f.engine.commitAdjudication(gm, turnRevision,
                "bad-hash", tampered);
        assertThat(badHash.code()).isEqualTo(ErrorCode.PROPOSAL_HASH_MISMATCH);

        AdjudicationProposal wrongSchema = AdjudicationProposal.create(2,
                f.turnView().turnId(), worldBefore, "叙述", Map.of("player-1", "结果"),
                List.of(), List.of());
        CommandResult unsupportedSchema = f.engine.commitAdjudication(gm, turnRevision,
                "bad-schema", wrongSchema);
        assertThat(unsupportedSchema.code()).isEqualTo(ErrorCode.SCHEMA_UNSUPPORTED);

        assertThat(f.worldVersion()).isEqualTo(worldBefore);
        assertThat(f.turnRevision()).isEqualTo(turnRevision);
        assertThat(f.turnView().state()).isEqualTo(TurnState.ADJUDICATING);
        assertThat(f.store.size()).isEqualTo(eventsBefore);
        assertThat(f.engine.snapshot().orElseThrow()).isEqualTo(snapshot);
    }

    @Test
    void commitBeforeAdjudicationIsRejectedWithoutStateChange() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-1");
        f.confirm(f.p1, draft, "confirm-1");
        f.lock();
        long revision = f.turnRevision();
        int events = f.store.size();
        long world = f.worldVersion();
        AdjudicationProposal proposal = proposal(f, world, Map.of("player-1", "结果"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "x")));

        CommandResult result = f.engine.commitAdjudication(new Actor("gm-1", ActorRole.GM),
                revision, "early-commit", proposal);

        assertThat(result.code()).isEqualTo(ErrorCode.TURN_NOT_ADJUDICATING);
        assertThat(f.turnView().state()).isEqualTo(TurnState.LOCKED);
        assertThat(f.turnRevision()).isEqualTo(revision);
        assertThat(f.worldVersion()).isEqualTo(world);
        assertThat(f.store.size()).isEqualTo(events);
    }
}
