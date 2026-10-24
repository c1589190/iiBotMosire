package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.event.AgentLibEventSink;
import io.mosire.iibot.app.event.SqliteEventReadPort;
import io.mosire.iibot.app.event.TaskflowSnapshotStore;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.DraftStatus;
import io.mosire.iibot.taskflow.SettingProposalStatus;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppRestartRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_NAPCAT_TOKEN", "napcat-secret-value",
            "IIBOT_LLM_KEY", "llm-secret-value"));

    @TempDir
    Path tempDir;

    @Test
    void restartRestoresFullEngineStatePersistsSnapshotsAndContinuesWithoutLlmCalls() throws Exception {
        AppConfig config = config("prompt-v1", "Player One", "qq-p1");
        Path snapshotFile = config.dataDir().resolve("taskflow-state.json");
        Path eventsFile = config.dataDir().resolve("events.db");

        // P5 完整装配默认走 ACTION_DRAFT ticket；本测试保留旧 Turn/确认链路，
        // 因此用一个非 tool-call 回复，然后直接经引擎准备已确认决策。
        FakeLlmClient firstLlm = FakeLlmClient.with(
                LlmResponse.text("行动草稿请通过工单提交给 GM。"));
        FakeMessagingAdapter firstAdapter = new FakeMessagingAdapter();
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, firstLlm, firstAdapter));
        TaskflowStateSnapshot beforeClose;
        String turnId;
        String draftId;
        String proposalId;
        long worldVersion;
        int eventCount;
        long lastEventSeq;
        try {
            first.start();
            assertThat(Files.isRegularFile(snapshotFile)).isTrue();
            assertFreshSnapshot(config, first);

            send(first, firstAdapter, "qq-director", "/开回合 30", "m-open");
            assertThat(lastText(firstAdapter)).contains("回合已开启");
            send(first, firstAdapter, "qq-p1", "帮我起草调查北码头的决策", "m-chat");
            assertThat(lastText(firstAdapter)).contains("工单");

            TurnView opened = first.engine().turnView().orElseThrow();
            var createdDraft = first.engine().createDecisionDraft(
                    new Actor("player-1", ActorRole.PLAYER),
                    new DecisionDraftInput("调查北码头", List.of("询问船工", "搜查仓库"),
                            Map.of("people", "3"), "北码头", "遇险撤回", opened.closesAt()),
                    opened.revision(), "restart-recovery-draft");
            assertThat(createdDraft.ok()).isTrue();
            draftId = createdDraft.referenceId().orElseThrow();
            DecisionDraft draft = first.engine().draft(draftId).orElseThrow();
            send(first, firstAdapter, "qq-p1", "/确认 " + draft.draftId(), "m-confirm");
            assertThat(lastText(firstAdapter)).contains("已确认决策");

            send(first, firstAdapter, "qq-p1", "/提议设定 北码头旧灯塔重新点亮", "m-propose");
            assertThat(lastText(firstAdapter)).contains("设定提案已提交");
            proposalId = first.engine().pendingSettings().get(0).proposalId();
            send(first, firstAdapter, "qq-director", "/通过设定 " + proposalId, "m-approve");
            assertThat(lastText(firstAdapter)).contains("已通过设定");

            TurnView restoredTurn = first.engine().turnView().orElseThrow();
            turnId = restoredTurn.turnId();
            assertThat(restoredTurn.state()).isEqualTo(TurnState.OPEN);
            assertThat(restoredTurn.drafts()).extracting(DecisionDraft::draftId).contains(draftId);
            assertThat(restoredTurn.decisions()).hasSize(1);
            assertThat(first.engine().draft(draftId).orElseThrow().status())
                    .isEqualTo(DraftStatus.CONFIRMED);
            assertThat(first.engine().settingProposal(proposalId).orElseThrow().status())
                    .isEqualTo(SettingProposalStatus.APPROVED);
            assertThat(first.engine().allWorldFacts())
                    .anyMatch(fact -> fact.factId().equals("setting-" + proposalId));
            assertThat(first.worldFacts()).isEqualTo(first.engine().allWorldFacts());
            assertThat(first.taskflowSnapshotPath()).isEqualTo(snapshotFile.toAbsolutePath());

            worldVersion = first.engine().status().worldVersion();
            eventCount = first.engine().status().eventCount();
            lastEventSeq = first.engine().exportState().lastEventSeq();
            beforeClose = first.engine().exportState();
            assertThat(beforeClose.turns()).containsKey(turnId);
            assertThat(beforeClose.infoRequests()).isEmpty();
            assertThat(beforeClose.settingProposals()).containsKey(proposalId);
            assertThat(Files.size(eventsFile)).isPositive();
        } finally {
            first.close();
        }
        assertThat(firstAdapter.isClosed()).isTrue();

        TaskflowSnapshotStore store = new TaskflowSnapshotStore(config.dataDir());
        assertThat(store.load()).isPresent();
        TaskflowStateSnapshot persisted = store.load().orElseThrow();
        assertThat(persisted.lastEventSeq()).isEqualTo(lastEventSeq);
        assertThat(persisted.eventCount()).isEqualTo(eventCount);
        assertThat(persisted.currentTurnId()).isEqualTo(turnId);
        String snapshotJson = Files.readString(snapshotFile);
        assertThat(snapshotJson)
                .doesNotContain("napcat-secret-value")
                .doesNotContain("llm-secret-value");
        try (SqliteEventReadPort port = new SqliteEventReadPort(eventsFile)) {
            assertThat(port.lastStoredSeq()).isEqualTo(lastEventSeq);
            assertThat(port.readAfter(0, 1)).isNotEmpty();
        }

        FakeLlmClient secondLlm = FakeLlmClient.with();
        FakeMessagingAdapter secondAdapter = new FakeMessagingAdapter();
        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, secondLlm, secondAdapter));
        try {
            // 构造与 start 只做快照恢复/装配，不得调用 LLM 或真实网络。
            assertThat(secondLlm.requests()).isEmpty();
            second.start();
            assertThat(secondLlm.requests()).isEmpty();
            assertThat(second.engine().status().playerCount()).isEqualTo(2);
            assertThat(second.engine().status().directorCount()).isEqualTo(1);
            assertThat(second.engine().playerProfiles()).hasSize(2);
            assertThat(second.engine().directorProfiles()).hasSize(1);
            assertThat(second.engine().status().worldVersion()).isEqualTo(worldVersion);
            assertThat(second.engine().status().eventCount()).isEqualTo(eventCount);
            assertThat(second.engine().exportState().lastEventSeq()).isEqualTo(lastEventSeq);

            TurnView recovered = second.engine().turnView().orElseThrow();
            assertThat(recovered.turnId()).isEqualTo(turnId);
            assertThat(recovered.state()).isEqualTo(TurnState.OPEN);
            assertThat(recovered.drafts()).extracting(DecisionDraft::draftId).contains(draftId);
            assertThat(recovered.decisions()).hasSize(1);
            assertThat(second.engine().draft(draftId).orElseThrow().status())
                    .isEqualTo(DraftStatus.CONFIRMED);
            assertThat(second.engine().settingProposal(proposalId).orElseThrow().status())
                    .isEqualTo(SettingProposalStatus.APPROVED);
            assertThat(second.worldFacts()).isEqualTo(second.engine().allWorldFacts());
            assertThat(second.worldFacts())
                    .anyMatch(fact -> fact.factId().equals("setting-" + proposalId));

            // 恢复后的写命令继续走状态机并再次原子持久化。
            send(second, secondAdapter, "qq-director", "/冻结", "m-lock-after-restart");
            assertThat(lastText(secondAdapter)).contains("回合已冻结");
            assertThat(second.engine().turnView().orElseThrow().state()).isEqualTo(TurnState.LOCKED);
            assertThat(secondLlm.requests()).isEmpty();
            TaskflowStateSnapshot after = store.load().orElseThrow();
            assertThat(after.currentTurnId()).isEqualTo(turnId);
            assertThat(after.turns().get(turnId).state()).isEqualTo(TurnState.LOCKED);
            try (SqliteEventReadPort port = new SqliteEventReadPort(eventsFile)) {
                assertThat(after.lastEventSeq()).isEqualTo(port.lastStoredSeq());
            }
        } finally {
            second.close();
        }
        assertThat(secondAdapter.isClosed()).isTrue();
    }

    @Test
    void eventsWithoutSnapshotFailClosedInsteadOfSilentlyRebuilding() {
        AppConfig config = config("prompt-v1", "Player One", "qq-p1");
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        runtime.close();
        try {
            Files.delete(config.dataDir().resolve("taskflow-state.json"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThatThrownBy(() -> AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_SNAPSHOT_MISSING")
                .hasMessageContaining("eventSeq=");
    }

    @Test
    void eventSeqMismatchFailsClosedWithBothSequences() throws Exception {
        AppConfig config = config("prompt-v1", "Player One", "qq-p1");
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        long snapshotSeq = runtime.engine().exportState().lastEventSeq();
        runtime.close();

        try (AgentLibEventSink sink = new AgentLibEventSink(
                config.dataDir().resolve("events.db"))) {
            sink.append(new TaskflowEvent(0, CLOCK.instant(), EventType.TURN_OPENED,
                    ActorRole.SUPERVISOR, "extra", null, "extra-event", Map.of()));
        }

        assertThatThrownBy(() -> AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_STATE_MISMATCH")
                .hasMessageContaining("snapshotSeq=" + snapshotSeq)
                .hasMessageContaining("eventSeq=");
    }

    @Test
    void promptVersionMismatchFailsClosed() {
        AppConfig original = config("prompt-v1", "Player One", "qq-p1");
        AppRuntime runtime = AppBootstrap.build(original,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        runtime.close();

        AppConfig changed = config("prompt-v2", "Player One", "qq-p1");
        assertThatThrownBy(() -> AppBootstrap.build(changed,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_PROMPT_VERSION_MISMATCH");
    }

    @Test
    void configuredIdentityMismatchFailsClosedWithoutOverwritingSnapshot() {
        AppConfig original = config("prompt-v1", "Player One", "qq-p1");
        AppRuntime runtime = AppBootstrap.build(original,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        runtime.close();
        TaskflowStateSnapshot before = new TaskflowSnapshotStore(original.dataDir())
                .load().orElseThrow();

        AppConfig changed = config("prompt-v1", "Renamed Player", "qq-p1");
        assertThatThrownBy(() -> AppBootstrap.build(changed,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOVERY_IDENTITY_MISMATCH");

        assertThat(new TaskflowSnapshotStore(original.dataDir()).load().orElseThrow())
                .isEqualTo(before);
    }

    private AppConfig config(String promptVersion, String firstPlayerName, String firstPlayerQq) {
        Path factsFile = TestSupport.writeFacts(
                tempDir.resolve("world-" + promptVersion).resolve("facts.json"), """
                        {"facts": [
                          {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"},
                          {"factId": "director-1", "content": "推演专用秘密", "visibility": "DIRECTOR_ONLY"}
                        ]}
                        """);
        ObjectNode root = TestSupport.validConfigNode(tempDir.resolve("data"), factsFile);
        ((ObjectNode) root.get("world")).put("promptVersion", promptVersion);
        ((ObjectNode) root.get("players").get(0)).put("displayName", firstPlayerName);
        ((ObjectNode) root.get("players").get(0)).put("qqId", firstPlayerQq);
        try {
            Path configFile = tempDir.resolve("iibot-" + promptVersion + "-"
                    + Math.abs((firstPlayerName + firstPlayerQq).hashCode()) + ".json");
            return TestSupport.loadConfig(TestSupport.writeConfig(configFile, root));
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static void assertFreshSnapshot(AppConfig config, AppRuntime runtime) {
        TaskflowStateSnapshot snapshot = new TaskflowSnapshotStore(config.dataDir())
                .load().orElseThrow();
        assertThat(snapshot.parties()).hasSize(3);
        assertThat(snapshot.lastEventSeq()).isEqualTo(runtime.engine().exportState().lastEventSeq());
        assertThat(snapshot.worldVersion()).isEqualTo(1);
        assertThat(snapshot.eventCount()).isEqualTo(runtime.engine().status().eventCount());
    }

    private static void send(AppRuntime runtime, FakeMessagingAdapter adapter, String qqId,
                             String text, String dedupeKey) {
        int before = adapter.sent().size();
        assertThat(runtime.submit(TestSupport.privateMessage(qqId, text, dedupeKey))).isTrue();
        assertThat(runtime.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(adapter.sent()).as("每条命令应产生一条回复").hasSizeGreaterThan(before);
    }

    private static String lastText(FakeMessagingAdapter adapter) {
        List<OutboundMessage> sent = adapter.sent();
        return String.join("\n", sent.get(sent.size() - 1).textSegments());
    }
}
