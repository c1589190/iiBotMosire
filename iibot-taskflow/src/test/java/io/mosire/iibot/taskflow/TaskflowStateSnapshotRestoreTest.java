package io.mosire.iibot.taskflow;

import io.mosire.iibot.taskflow.snapshot.FactSnapshot;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import io.mosire.iibot.taskflow.snapshot.TurnStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 完整状态快照导出/恢复、非法快照拒绝与提交监听器语义。 */
class TaskflowStateSnapshotRestoreTest {

    private static final Set<String> ALLOWED = WorldDelta.DEFAULT_ALLOWED_TYPES;

    @Test
    void exportRestoreRoundTripPreservesFullStateAndContinuesCommands() {
        Prepared prepared = prepareFullState();
        EngineFixture f = prepared.fixture;
        TaskflowStateSnapshot exported = f.engine.exportState();

        assertThat(exported.schemaVersion()).isEqualTo(TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION);
        assertThat(exported.lastEventSeq()).isEqualTo(f.store.lastSeq());
        assertThat(exported.eventCount()).isEqualTo(f.store.size());
        assertThat(exported.parties()).hasSize(3);
        assertThat(exported.turns()).hasSize(2);
        assertThat(exported.infoRequests()).isNotEmpty();
        assertThat(exported.briefsByRequest()).isNotEmpty();
        assertThat(exported.settingProposals()).hasSize(4);
        assertThat(exported.idempotencyResults()).isNotEmpty();

        TaskflowEngine restored = restore(f, exported);

        assertThat(restored.status()).isEqualTo(f.engine.status());
        assertThat(restored.turnView()).isEqualTo(f.engine.turnView());
        assertThat(restored.turnView(prepared.firstTurnId))
                .isEqualTo(f.engine.turnView(prepared.firstTurnId));
        assertThat(restored.snapshot()).isEqualTo(f.engine.snapshot());
        assertThat(restored.allWorldFacts()).isEqualTo(f.engine.allWorldFacts());
        assertThat(restored.playerProfiles()).isEqualTo(f.engine.playerProfiles());
        assertThat(restored.directorProfiles()).isEqualTo(f.engine.directorProfiles());
        assertThat(restored.visibleFactsFor("player-1"))
                .isEqualTo(f.engine.visibleFactsFor("player-1"));
        assertThat(restored.visibleFactsFor("player-2"))
                .isEqualTo(f.engine.visibleFactsFor("player-2"));
        assertThat(restored.knowledgeForSecretary("player-1"))
                .isEqualTo(f.engine.knowledgeForSecretary("player-1"));
        assertThat(restored.briefsForPlayer("player-1"))
                .isEqualTo(f.engine.briefsForPlayer("player-1"));
        assertThat(restored.pendingSettings()).isEqualTo(f.engine.pendingSettings());
        for (String proposalId : exported.settingProposals().keySet()) {
            assertThat(restored.settingProposal(proposalId))
                    .isEqualTo(f.engine.settingProposal(proposalId));
        }
        assertThat(restored.exportState()).isEqualTo(exported);

        // 恢复后旧幂等键仍返回原回执，且不会重复追加事件。
        long eventCountBeforeDuplicate = f.store.size();
        CommandResult originalConfirm = exported.idempotencyResults().get("confirm-first-p1");
        CommandResult duplicate = restored.confirmDecision(f.p1, prepared.firstDraftId,
                prepared.firstPreviewHash, 99, "confirm-first-p1");
        assertThat(duplicate).isEqualTo(originalConfirm);
        assertThat(f.store.size()).isEqualTo(eventCountBeforeDuplicate);

        // 恢复后的新写命令继续使用原 revision/CAS 语义并推进事件序号。
        long revisionBefore = restored.turnView().orElseThrow().revision();
        long eventSeqBefore = f.store.lastSeq();
        CommandResult newDraft = restored.createDecisionDraft(f.p2,
                new DecisionDraftInput("恢复后行动", List.of("继续调查"), Map.of("focus", "steady"),
                        "harbor", "撤退", f.clock.instant().plus(Duration.ofHours(1))),
                revisionBefore, "restored-new-draft");
        assertThat(newDraft.ok()).isTrue();
        assertThat(newDraft.revision()).isEqualTo(revisionBefore + 1);
        assertThat(newDraft.eventSeq()).isEqualTo(eventSeqBefore + 1);
        assertThat(restored.turnView().orElseThrow().drafts())
                .anyMatch(draft -> draft.draftId().equals(newDraft.referenceId().orElseThrow()));
        assertThat(restored.exportState().lastEventSeq()).isEqualTo(f.store.lastSeq());
    }

    @Test
    void exportedCollectionsAreDefensiveAndDoNotExposeEngineState() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("public-1", 1, "港口开放", Visibility.PUBLIC),
                new WorldFact("p1-only", 1, "密信", Visibility.forPlayers(Set.of("player-1"))));
        f.openTurn();
        f.createDraft(f.p1, "观察", "draft-copy");
        TaskflowStateSnapshot snapshot = f.engine.exportState();
        List<WorldFact> factsBefore = f.engine.allWorldFacts();

        assertThatThrownBy(() -> snapshot.parties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.worldFacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.infoRequests().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.briefsByRequest().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.settingProposals().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.turns().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.idempotencyResults().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        TurnStateSnapshot turn = snapshot.turns().values().iterator().next();
        assertThatThrownBy(() -> turn.drafts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> turn.decisionsByPlayer().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        FactSnapshot fact = snapshot.worldFacts().get("public-1");
        assertThat(fact.toDomain().visibility()).isEqualTo(Visibility.PUBLIC);

        assertThat(f.engine.allWorldFacts()).isEqualTo(factsBefore);
        assertThat(f.engine.turnView().orElseThrow().drafts()).hasSize(1);
    }

    @Test
    void restoreRejectsInvalidSnapshotsInsteadOfSkipping() {
        Prepared prepared = prepareFullState();
        TaskflowStateSnapshot valid = prepared.fixture.engine.exportState();

        assertThatThrownBy(() -> restore(prepared.fixture, copy(valid, 999, valid.promptVersion(),
                valid.worldVersion(), valid.currentTurnId(), valid.worldFacts(), valid.turns())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");

        assertThatThrownBy(() -> restore(prepared.fixture, copy(valid, valid.schemaVersion(),
                valid.promptVersion(), valid.worldVersion(), "missing-turn", valid.worldFacts(),
                valid.turns())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentTurnId");

        Map<String, FactSnapshot> badFactKey = new LinkedHashMap<>(valid.worldFacts());
        FactSnapshot firstFact = badFactKey.values().iterator().next();
        badFactKey.remove(firstFact.factId());
        badFactKey.put("wrong-fact-key", firstFact);
        assertThatThrownBy(() -> restore(prepared.fixture, copy(valid, valid.schemaVersion(),
                valid.promptVersion(), valid.worldVersion(), valid.currentTurnId(), badFactKey,
                valid.turns())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worldFacts key");

        Map<String, TurnStateSnapshot> badTurnKey = new LinkedHashMap<>(valid.turns());
        TurnStateSnapshot firstTurn = badTurnKey.remove(valid.currentTurnId());
        badTurnKey.put("wrong-turn-key", firstTurn);
        assertThatThrownBy(() -> restore(prepared.fixture, copy(valid, valid.schemaVersion(),
                valid.promptVersion(), valid.worldVersion(), valid.currentTurnId(), valid.worldFacts(),
                badTurnKey)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> restore(prepared.fixture, copy(valid, valid.schemaVersion(),
                valid.promptVersion(), 0, valid.currentTurnId(), valid.worldFacts(), valid.turns())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worldVersion");

        TaskflowEngine expectedPrompt = new TaskflowEngine(prepared.fixture.clock,
                prepared.fixture.store, "prompt-expected", ALLOWED, 64, 200_000, 8_192);
        assertThatThrownBy(() -> TaskflowEngine.restore(prepared.fixture.clock,
                prepared.fixture.store, "prompt-expected", ALLOWED, 64, 200_000, 8_192, valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("promptVersion");
        assertThat(expectedPrompt).isNotNull();
    }

    @Test
    void commitListenerFiresOnlyForSuccessfulWritesAndCanBeSetOnce() {
        EngineFixture f = EngineFixture.create();
        AtomicInteger callbacks = new AtomicInteger();
        List<CommandResult> received = new ArrayList<>();
        StateCommitListener listener = (engine, result) -> {
            // 仍在引擎锁内时可重入读取完整快照。
            assertThat(engine.exportState().engineRevision()).isEqualTo(result.engineRevision());
            callbacks.incrementAndGet();
            received.add(result);
        };

        assertThat(f.engine.stateCommitListener()).isEmpty();
        f.engine.setStateCommitListener(null);
        assertThat(f.engine.stateCommitListener()).isEmpty();
        f.engine.setStateCommitListener(listener);
        assertThat(f.engine.stateCommitListener()).containsSame(listener);
        assertThatThrownBy(() -> f.engine.setStateCommitListener((engine, result) -> { }))
                .isInstanceOf(IllegalStateException.class);

        f.loadFacts(new WorldFact("listener-fact", 1, "监听事实", Visibility.PUBLIC));
        assertThat(callbacks).hasValue(1);
        assertThat(received).singleElement().satisfies(result -> assertThat(result.ok()).isTrue());

        CommandResult failed = f.engine.openTurn(f.director, 9_999, "failed-open",
                f.clock.instant().plus(Duration.ofHours(1)));
        assertThat(failed.ok()).isFalse();
        assertThat(callbacks).hasValue(1);
    }

    @Test
    void listenerExceptionPropagatesAndStateIsNotSilentlyRolledBack() {
        EngineFixture f = EngineFixture.create();
        f.engine.setStateCommitListener((engine, result) -> {
            throw new IllegalStateException("listener-boom");
        });
        long revisionBefore = f.globalRevision();
        int playersBefore = f.engine.status().playerCount();
        long eventsBefore = f.store.size();

        assertThatThrownBy(() -> f.engine.registerPlayer(f.director,
                new PlayerProfile("player-3", "qq-p3", "Player Three"),
                revisionBefore, "reg-p3-listener-throws"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener-boom");

        assertThat(f.globalRevision()).isEqualTo(revisionBefore + 1);
        assertThat(f.engine.status().playerCount()).isEqualTo(playersBefore + 1);
        assertThat(f.store.size()).isEqualTo(eventsBefore + 1);
    }

    private static TaskflowEngine restore(EngineFixture f, TaskflowStateSnapshot snapshot) {
        return TaskflowEngine.restore(f.clock, f.store, ALLOWED, 64, 200_000, 8_192, snapshot);
    }

    private static TaskflowStateSnapshot copy(TaskflowStateSnapshot source, int schemaVersion,
                                              String promptVersion, long worldVersion,
                                              String currentTurnId,
                                              Map<String, FactSnapshot> worldFacts,
                                              Map<String, TurnStateSnapshot> turns) {
        return new TaskflowStateSnapshot(schemaVersion, promptVersion,
                source.engineRevision(), worldVersion, source.eventCount(), source.lastEventSeq(),
                source.lastTurnNo(), currentTurnId, source.parties(), worldFacts,
                source.infoRequests(), source.briefsByRequest(), source.settingProposals(), turns,
                source.idempotencyResults());
    }

    private static Prepared prepareFullState() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f-public", 1, "港口钟声", Visibility.PUBLIC),
                new WorldFact("f-p1", 1, "给玩家一的密信",
                        Visibility.forPlayers(Set.of("player-1"))),
                new WorldFact("f-director", 1, "仅推演可见", Visibility.DIRECTOR_ONLY));

        String pendingSetting = f.expectOk(f.engine.proposeSetting(f.p1, "待审设定",
                Visibility.PUBLIC, f.globalRevision(), "setting-pending"))
                .referenceId().orElseThrow();
        String approvedSetting = f.expectOk(f.engine.proposeSetting(f.director, "批准后设定",
                Visibility.forPlayers(Set.of("player-1")), f.globalRevision(), "setting-approved"))
                .referenceId().orElseThrow();
        CommandResult approve = f.expectOk(f.engine.decideSetting(f.director, approvedSetting,
                true, "同意", f.globalRevision(), "setting-approve-decision"));
        String approvedFactId = approve.detail("worldFactId").orElseThrow();
        String rejectedSetting = f.expectOk(f.engine.proposeSetting(f.p1, "拒绝设定",
                Visibility.PUBLIC, f.globalRevision(), "setting-rejected"))
                .referenceId().orElseThrow();
        f.expectOk(f.engine.decideSetting(f.director, rejectedSetting, false, "不采用",
                f.globalRevision(), "setting-reject-decision"));

        f.openTurn();
        String firstTurnId = f.turnView().turnId();
        String requestId = f.expectOk(f.engine.requestBrief(f.p1, "港口安全吗？",
                f.turnRevision(), "brief-request-first")).referenceId().orElseThrow();
        f.expectOk(f.engine.issueBrief(f.director, requestId, "港口目前安全。",
                List.of("f-public", approvedFactId), f.clock.instant().plus(Duration.ofHours(1)),
                f.turnRevision(), "brief-issue-first"));

        DecisionDraft firstDraft = f.createDraft(f.p1, "调查港口", "draft-first-p1");
        f.confirm(f.p1, firstDraft, "confirm-first-p1");
        DecisionDraft secondDraft = f.createDraft(f.p2, "巡逻码头", "draft-first-p2");
        f.confirm(f.p2, secondDraft, "confirm-first-p2");
        f.lock();
        f.start();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION, firstTurnId, f.worldVersion(),
                "港口的第一回合结束。",
                Map.of("player-1", "发现线索", "player-2", "完成巡逻"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "investigated")), List.of());
        f.expectOk(f.engine.commitAdjudication(new Actor("gm-1", ActorRole.GM),
                f.turnRevision(), "commit-first", proposal));
        f.expectOk(f.engine.publishTurn(f.director, f.turnRevision(), "publish-first"));

        f.openTurn();
        f.createDraft(f.p1, "第二回合待确认", "draft-second-p1");
        f.expectOk(f.engine.requestBrief(f.p2, "第二回合有什么线索？",
                f.turnRevision(), "brief-request-second"));
        f.expectOk(f.engine.proposeSetting(f.p1, "第二回合待审设定", Visibility.PUBLIC,
                f.globalRevision(), "setting-pending-second"));

        return new Prepared(f, firstTurnId, firstDraft.draftId(), firstDraft.previewHash(),
                pendingSetting);
    }

    private record Prepared(EngineFixture fixture, String firstTurnId, String firstDraftId,
                            String firstPreviewHash, String pendingSettingId) {
    }
}
