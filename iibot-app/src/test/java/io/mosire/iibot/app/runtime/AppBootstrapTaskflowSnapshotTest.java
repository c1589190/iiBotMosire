package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.event.SqliteEventReadPort;
import io.mosire.iibot.app.event.TaskflowSnapshotSnapshotReader;
import io.mosire.iibot.app.event.TaskflowSnapshotStore;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P2：TASKFLOW_SNAPSHOT/baseline reconciled、恢复锚点与 ensureRecorded 追平。 */
@Timeout(60)
class AppBootstrapTaskflowSnapshotTest {

    private static final String CONTROL_TOKEN = "taskflow-snapshot-token-6f21";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_NAPCAT_TOKEN", "napcat-token",
            "IIBOT_LLM_KEY", "llm-token",
            "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN));
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void bootstrapWritesSnapshotAndBaselineReconciledAndAnchorIgnoresDomainEvents() throws Exception {
        AppConfig config = config();
        Path eventsFile = config.dataDir().resolve("events.db");
        TaskflowStateSnapshot jsonSnapshot;
        long anchor;
        String postId;

        FakeMessagingAdapter firstAdapter = new FakeMessagingAdapter();
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(), firstAdapter));
        try {
            first.start();
            jsonSnapshot = new TaskflowSnapshotStore(config.dataDir()).load().orElseThrow();
            assertThat(jsonSnapshot.lastEventSeq()).isPositive();
            try (SqliteEventReadPort port = new SqliteEventReadPort(eventsFile)) {
                anchor = port.lastStoredSeq();
                assertThat(anchor).isEqualTo(jsonSnapshot.lastEventSeq());
            }

            ControlMcpServer control = controlServer(first);
            PostService postService = postService(control);
            PostCommandResult created = postService.createDraft(
                    new Actor("player-1", ActorRole.PLAYER), "快照锚点测试",
                    "领域事件不得推进恢复锚点", PostVisibility.PUBLIC,
                    postService.collectionRevision("player-1"), "anchor-post");
            assertThat(created.ok()).isTrue();
            postId = created.postId();
            ComplaintService complaintService = complaintService(control);
            assertThat(complaintService.submit(new Actor("player-1", ActorRole.PLAYER),
                    "快照锚点投诉", "领域事件不得推进恢复锚点", "anchor-complaint").ok()).isTrue();

            try (SqliteEventReadPort port = new SqliteEventReadPort(eventsFile)) {
                assertThat(port.lastStoredSeq()).isEqualTo(anchor);
            }
        } finally {
            first.close();
        }

        long snapshotCountAfterFresh;
        try (EventContentStore store = new EventContentStore(eventsFile)) {
            List<StoredEvent> events = store.readAfter(0, 1000);
            assertThat(events).extracting(StoredEvent::eventType)
                    .contains(DomainEventTypes.TASKFLOW_SNAPSHOT,
                            DomainEventTypes.TASKFLOW_BASELINE_RECONCILED,
                            DomainEventTypes.POST_DRAFTED,
                            DomainEventTypes.COMPLAINT_SUBMITTED);
            StoredEvent snapshotEvent = latestOfType(events, DomainEventTypes.TASKFLOW_SNAPSHOT);
            assertThat(snapshotEvent.contentMissing()).isFalse();
            assertThat(snapshotEvent.content().json()).contains("prompt-v1");
            assertThat(snapshotEvent.payloadJson())
                    .contains("eventSeq", "eventCount", "worldVersion", "turnId", "state");
            StoredEvent baseline = latestOfType(events,
                    DomainEventTypes.TASKFLOW_BASELINE_RECONCILED);
            assertThat(baseline.contentMissing()).isFalse();
            JsonNode baselineContent = JSON.readTree(baseline.content().json());
            assertThat(baselineContent.path("migrationEventType").asText())
                    .isEqualTo(DomainEventTypes.TASKFLOW_SNAPSHOT);
            assertThat(baselineContent.path("migrationEventSeq").asLong()).isPositive();
            assertThat(baselineContent.path("sourceFile").asText())
                    .isEqualTo("fresh-bootstrap");
            assertThat(baselineContent.path("recordCount").asLong()).isZero();
            assertThat(baselineContent.path("lastEventSeq").asLong())
                    .isEqualTo(jsonSnapshot.lastEventSeq());
            String baselineContentHash = baselineContent.path("contentHash").asText();
            assertThat(baselineContentHash).hasSize(64);
            StoredEvent baselineTarget = store.read(
                    baselineContent.path("migrationEventSeq").asLong()).orElseThrow();
            assertThat(baselineTarget.eventType())
                    .isEqualTo(DomainEventTypes.TASKFLOW_SNAPSHOT);
            assertThat(baselineTarget.contentHash()).isEqualTo(baselineContentHash);
            assertThat(baseline.content().json()).doesNotContain("prompt-v1");
            assertThat(baseline.payloadJson())
                    .contains("\"migrationEventType\":\"TASKFLOW_SNAPSHOT\"",
                            "\"sourceFile\":\"fresh-bootstrap\"")
                    .doesNotContain("prompt-v1");
            snapshotCountAfterFresh = countOfType(events, DomainEventTypes.TASKFLOW_SNAPSHOT);
            assertThat(snapshotCountAfterFresh).isEqualTo(1);
            assertThat(countOfType(events, DomainEventTypes.TASKFLOW_BASELINE_RECONCILED))
                    .isEqualTo(1);
            assertThat(new TaskflowSnapshotSnapshotReader(store).loadLatest())
                    .contains(jsonSnapshot);
            long maxSeq = events.stream().mapToLong(StoredEvent::seq).max().orElseThrow();
            assertThat(maxSeq).isGreaterThan(anchor);
        }

        // baseline reconciled 存在，二次启动不得被 ambiguous 误杀。
        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        try {
            second.start();
            PostService reloaded = postService(controlServer(second));
            assertThat(reloaded.post(postId)).isPresent();
            TaskflowStateSnapshot recovered = new TaskflowSnapshotStore(config.dataDir())
                    .load().orElseThrow();
            assertThat(recovered.lastEventSeq()).isEqualTo(anchor);
        } finally {
            second.close();
        }

        try (EventContentStore store = new EventContentStore(eventsFile)) {
            List<StoredEvent> events = store.readAfter(0, 1000);
            assertThat(countOfType(events, DomainEventTypes.TASKFLOW_SNAPSHOT))
                    .isEqualTo(snapshotCountAfterFresh);
            assertThat(countOfType(events, DomainEventTypes.TASKFLOW_BASELINE_RECONCILED))
                    .isEqualTo(1);
        }
    }

    @Test
    void staleTaskflowJsonIsReconciledByEnsureRecordedAndRecoveryWriteRecordsNewSnapshot()
            throws Exception {
        AppConfig config = config();
        Path dataDir = config.dataDir();
        Path eventsFile = dataDir.resolve("events.db");

        TaskflowStateSnapshot original;
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        try {
            first.start();
            original = new TaskflowSnapshotStore(dataDir).load().orElseThrow();
        } finally {
            first.close();
        }

        // 模拟 taskflow-state.json 比最新 TASKFLOW_SNAPSHOT 新/不同，但锚点 seq 不变。
        TaskflowStateSnapshot variant = withEngineRevision(original,
                original.engineRevision() + 1);
        new TaskflowSnapshotStore(dataDir).save(variant);

        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        try {
            second.start();
            assertThat(new TaskflowSnapshotStore(dataDir).load()).contains(variant);
        } finally {
            second.close();
        }

        long snapshotsBeforeRecoveryWrite;
        try (EventContentStore store = new EventContentStore(eventsFile)) {
            assertThat(new TaskflowSnapshotSnapshotReader(store).loadLatest())
                    .contains(variant);
            snapshotsBeforeRecoveryWrite = countOfType(store.readAfter(0, 1000),
                    DomainEventTypes.TASKFLOW_SNAPSHOT);
        }

        // 恢复路径上的下一次成功写命令必须再产生一条与导出状态等价的 TASKFLOW_SNAPSHOT。
        TaskflowStateSnapshot afterWrite;
        AppRuntime third = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        try {
            third.start();
            CommandResult opened = third.engine().openTurn(
                    new Actor("director-1", ActorRole.DIRECTOR),
                    third.engine().status().engineRevision(), "recovery-open-turn-1",
                    CLOCK.instant().plusSeconds(3600));
            assertThat(opened.ok()).isTrue();
            afterWrite = third.engine().exportState();
            assertThat(afterWrite.currentTurnId()).isNotBlank();
        } finally {
            third.close();
        }

        assertThat(new TaskflowSnapshotStore(dataDir).load()).contains(afterWrite);
        try (EventContentStore store = new EventContentStore(eventsFile)) {
            List<StoredEvent> events = store.readAfter(0, 1000);
            assertThat(new TaskflowSnapshotSnapshotReader(store).loadLatest())
                    .contains(afterWrite);
            assertThat(countOfType(events, DomainEventTypes.TASKFLOW_SNAPSHOT))
                    .isGreaterThan((int) snapshotsBeforeRecoveryWrite);
            assertThat(countOfType(events, DomainEventTypes.TASKFLOW_BASELINE_RECONCILED))
                    .isEqualTo(1);
        }
    }

    @Test
    void deletedBaselineMarkerTargetFailsClosedOnNextStart() throws Exception {
        AppConfig config = config();
        Path eventsFile = config.dataDir().resolve("events.db");

        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                        new FakeMessagingAdapter()));
        try {
            first.start();
        } finally {
            first.close();
        }

        long targetSeq;
        try (EventContentStore store = new EventContentStore(eventsFile)) {
            StoredEvent baseline = store.latestByType(
                    DomainEventTypes.TASKFLOW_BASELINE_RECONCILED).orElseThrow();
            JsonNode content = JSON.readTree(baseline.content().json());
            targetSeq = content.path("migrationEventSeq").asLong();
            assertThat(content.path("migrationEventType").asText())
                    .isEqualTo(DomainEventTypes.TASKFLOW_SNAPSHOT);
            StoredEvent target = store.read(targetSeq).orElseThrow();
            assertThat(target.eventType()).isEqualTo(DomainEventTypes.TASKFLOW_SNAPSHOT);
            assertThat(target.contentHash()).isEqualTo(content.path("contentHash").asText());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + eventsFile);
             PreparedStatement deleteContent = connection.prepareStatement(
                     "DELETE FROM event_contents WHERE seq = ?");
             PreparedStatement deleteEvent = connection.prepareStatement(
                     "DELETE FROM events WHERE seq = ?")) {
            deleteContent.setLong(1, targetSeq);
            deleteContent.executeUpdate();
            deleteEvent.setLong(1, targetSeq);
            assertThat(deleteEvent.executeUpdate()).isEqualTo(1);
        }

        // baseline marker 指向的快照被删除后，下次启动必须对账失败，不得跳过成空状态。
        assertThatThrownBy(() -> {
            AppRuntime runtime = AppBootstrap.build(config,
                    BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(),
                            new FakeMessagingAdapter()));
            try {
                runtime.start();
            } finally {
                runtime.close();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LEGACY_EVENT_MIGRATION_RECONCILIATION]");
    }

    private AppConfig config() throws Exception {
        Path factsFile = TestSupport.writeFacts(
                tempDir.resolve("world").resolve("facts.json"), """
                        {"facts": [
                          {"factId": "public-1", "content": "公共事实", "visibility": "PUBLIC"}
                        ]}
                        """);
        ObjectNode node = TestSupport.validConfigNode(tempDir.resolve("data"), factsFile);
        ObjectNode control = node.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", "/taskflow-snapshot/mcp");
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        return TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot-taskflow-snapshot.json"), node));
    }

    private static StoredEvent latestOfType(List<StoredEvent> events, String type) {
        return events.stream()
                .filter(event -> event.eventType().equals(type))
                .reduce((left, right) -> right)
                .orElseThrow(() -> new AssertionError("缺少事件 " + type));
    }

    private static int countOfType(List<StoredEvent> events, String type) {
        return (int) events.stream()
                .filter(event -> event.eventType().equals(type))
                .count();
    }

    private static TaskflowStateSnapshot withEngineRevision(TaskflowStateSnapshot source,
                                                            long engineRevision) {
        return new TaskflowStateSnapshot(source.schemaVersion(), source.promptVersion(),
                engineRevision, source.worldVersion(), source.eventCount(),
                source.lastEventSeq(), source.lastTurnNo(), source.currentTurnId(),
                source.parties(), source.worldFacts(), source.infoRequests(),
                source.briefsByRequest(), source.settingProposals(), source.turns(),
                source.idempotencyResults());
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        return (ControlMcpServer) field.get(runtime);
    }

    private static PostService postService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("postService");
        field.setAccessible(true);
        return (PostService) field.get(server);
    }

    private static ComplaintService complaintService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("complaintService");
        field.setAccessible(true);
        return (ComplaintService) field.get(server);
    }
}
