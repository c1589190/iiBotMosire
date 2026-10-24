package io.mosire.iibot.app.event.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.app.complaint.EventSourcedComplaintStore;
import io.mosire.iibot.app.complaint.JsonComplaintStore;
import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.TaskflowSnapshotSnapshotReader;
import io.mosire.iibot.app.event.TaskflowSnapshotStore;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.publication.EventSourcedPostStore;
import io.mosire.iibot.app.publication.JsonPostStore;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintSnapshot;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

/** P2：旧 JSON 迁移两阶段 reconciled 标记、幂等跳过、ambiguous fail-closed 与对账失败。 */
class LegacyEventMigratorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void migratesAllLegacyJsonWritesExactReconciledAndLaterJsonUpdatesCannotOverride() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);

        PostService legacyPosts = new PostService(new JsonPostStore(dataDir), CLOCK);
        assertThat(legacyPosts.createDraft(PLAYER, "旧推文标题", "旧推文正文",
                PostVisibility.PUBLIC, 0, "legacy-post-1").ok()).isTrue();
        PostSnapshot postSnapshot = legacyPosts.snapshot();

        ComplaintService legacyComplaints = new ComplaintService(
                new JsonComplaintStore(dataDir), CLOCK);
        assertThat(legacyComplaints.submit(PLAYER, "旧投诉标题", "旧投诉正文",
                "legacy-complaint-1").ok()).isTrue();
        ComplaintSnapshot complaintSnapshot = legacyComplaints.snapshot();

        TaskflowStateSnapshot taskflowSnapshot = minimalTaskflowSnapshot();
        new TaskflowSnapshotStore(dataDir).save(taskflowSnapshot);

        Path postsFile = dataDir.resolve(JsonPostStore.FILE_NAME);
        Path complaintsFile = dataDir.resolve(JsonComplaintStore.FILE_NAME);
        Path taskflowFile = dataDir.resolve(TaskflowSnapshotStore.FILE_NAME);
        byte[] postsBytes = Files.readAllBytes(postsFile);
        byte[] complaintsBytes = Files.readAllBytes(complaintsFile);
        byte[] taskflowBytes = Files.readAllBytes(taskflowFile);

        Path db = dataDir.resolve("events.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            LegacyEventMigrator migrator = new LegacyEventMigrator(
                    dataDir, store, journal, CLOCK);

            LegacyEventMigrator.MigrationReport first = migrator.migrate();
            assertThat(first.postMigrated()).isTrue();
            assertThat(first.postRecordCount()).isEqualTo(1);
            assertThat(first.complaintMigrated()).isTrue();
            assertThat(first.complaintRecordCount()).isEqualTo(1);
            assertThat(first.taskflowMigrated()).isTrue();
            assertThat(first.taskflowEventSeq()).isPositive();

            assertThat(new EventSourcedPostStore(store, journal, CLOCK).load())
                    .isEqualTo(postSnapshot);
            assertThat(new EventSourcedComplaintStore(store, journal, CLOCK).load())
                    .isEqualTo(complaintSnapshot);
            assertThat(new TaskflowSnapshotSnapshotReader(store).loadLatest())
                    .contains(taskflowSnapshot);

            List<StoredEvent> events = store.readAfter(0, 1000);
            assertThat(events).extracting(StoredEvent::eventType)
                    .contains("POST_LEGACY_MIGRATED", "COMPLAINT_LEGACY_MIGRATED",
                            "TASKFLOW_SNAPSHOT_MIGRATED",
                            "LEGACY_POST_MIGRATION_RECONCILED",
                            "LEGACY_COMPLAINT_MIGRATION_RECONCILED",
                            "TASKFLOW_BASELINE_RECONCILED");

            StoredEvent postReconciled = byType(events,
                    DomainEventTypes.LEGACY_POST_MIGRATION_RECONCILED);
            assertThat(postReconciled.contentMissing()).isFalse();
            JsonNode postMarker = JSON.readTree(postReconciled.content().json());
            assertThat(postMarker.path("migrationEventType").asText())
                    .isEqualTo(DomainEventTypes.POST_LEGACY_MIGRATED);
            assertThat(postMarker.path("migrationEventSeq").asLong()).isPositive();
            assertThat(postMarker.path("sourceFile").asText()).isEqualTo("posts.json");
            assertThat(postMarker.path("recordCount").asLong()).isEqualTo(1);
            String postContentHash = postMarker.path("contentHash").asText();
            assertThat(postContentHash).hasSize(64);
            StoredEvent postTarget = store.read(
                    postMarker.path("migrationEventSeq").asLong()).orElseThrow();
            assertThat(postTarget.eventType())
                    .isEqualTo(DomainEventTypes.POST_LEGACY_MIGRATED);
            assertThat(postTarget.contentHash()).isEqualTo(postContentHash);
            assertThat(postReconciled.content().json()).doesNotContain("旧推文正文");
            assertThat(postReconciled.payloadJson())
                    .contains("\"migrationEventType\":\"POST_LEGACY_MIGRATED\"",
                            "\"sourceFile\":\"posts.json\"", "\"recordCount\":1",
                            "\"migrationEventSeq\":")
                    .doesNotContain("旧推文正文");

            StoredEvent complaintReconciled = byType(events,
                    DomainEventTypes.LEGACY_COMPLAINT_MIGRATION_RECONCILED);
            JsonNode complaintMarker = JSON.readTree(complaintReconciled.content().json());
            assertThat(complaintMarker.path("migrationEventType").asText())
                    .isEqualTo(DomainEventTypes.COMPLAINT_LEGACY_MIGRATED);
            assertThat(complaintMarker.path("sourceFile").asText())
                    .isEqualTo("complaints.json");
            assertThat(complaintMarker.path("recordCount").asLong()).isEqualTo(1);
            StoredEvent complaintTarget = store.read(
                    complaintMarker.path("migrationEventSeq").asLong()).orElseThrow();
            assertThat(complaintTarget.eventType())
                    .isEqualTo(DomainEventTypes.COMPLAINT_LEGACY_MIGRATED);
            assertThat(complaintTarget.contentHash())
                    .isEqualTo(complaintMarker.path("contentHash").asText());
            assertThat(complaintReconciled.content().json()).doesNotContain("旧投诉正文");

            StoredEvent taskflowReconciled = byType(events,
                    DomainEventTypes.TASKFLOW_BASELINE_RECONCILED);
            JsonNode taskflowMarker = JSON.readTree(taskflowReconciled.content().json());
            assertThat(taskflowMarker.path("migrationEventType").asText())
                    .isEqualTo(DomainEventTypes.TASKFLOW_SNAPSHOT_MIGRATED);
            assertThat(taskflowMarker.path("sourceFile").asText())
                    .isEqualTo("taskflow-state.json");
            assertThat(taskflowMarker.path("recordCount").asLong()).isZero();
            assertThat(taskflowMarker.path("lastEventSeq").asLong()).isZero();
            assertThat(taskflowMarker.path("eventCount").asLong()).isZero();
            StoredEvent taskflowTarget = store.read(
                    taskflowMarker.path("migrationEventSeq").asLong()).orElseThrow();
            assertThat(taskflowTarget.eventType())
                    .isEqualTo(DomainEventTypes.TASKFLOW_SNAPSHOT_MIGRATED);
            assertThat(taskflowTarget.contentHash())
                    .isEqualTo(taskflowMarker.path("contentHash").asText());
            assertThat(taskflowReconciled.content().json()).doesNotContain("prompt-v1");

            long eventCountAfterFirst = events.size();
            LegacyEventMigrator.MigrationReport second = migrator.migrate();
            assertThat(second.postMigrated()).isFalse();
            assertThat(second.complaintMigrated()).isFalse();
            assertThat(second.taskflowMigrated()).isFalse();
            assertThat(store.readAfter(0, 1000)).hasSize((int) eventCountAfterFirst);

            // reconciled 完成后旧 JSON 即使更新，也只读保留，不得覆盖 events.db。
            PostService updatedPosts = new PostService(new JsonPostStore(dataDir), CLOCK);
            assertThat(updatedPosts.createDraft(PLAYER, "后来新增推文标题", "后来新增推文正文",
                    PostVisibility.PRIVATE, 1, "legacy-post-2").ok()).isTrue();
            ComplaintService updatedComplaints = new ComplaintService(
                    new JsonComplaintStore(dataDir), CLOCK);
            assertThat(updatedComplaints.submit(PLAYER, "后来新增投诉", "后来新增投诉正文",
                    "legacy-complaint-2").ok()).isTrue();
            TaskflowStateSnapshot updatedTaskflow = withEngineRevision(
                    taskflowSnapshot, taskflowSnapshot.engineRevision() + 1);
            new TaskflowSnapshotStore(dataDir).save(updatedTaskflow);

            byte[] updatedPostsBytes = Files.readAllBytes(postsFile);
            byte[] updatedComplaintsBytes = Files.readAllBytes(complaintsFile);
            byte[] updatedTaskflowBytes = Files.readAllBytes(taskflowFile);
            assertThat(updatedPostsBytes).isNotEqualTo(postsBytes);

            LegacyEventMigrator.MigrationReport third = migrator.migrate();
            assertThat(third.postMigrated()).isFalse();
            assertThat(third.complaintMigrated()).isFalse();
            assertThat(third.taskflowMigrated()).isFalse();
            assertThat(store.readAfter(0, 1000)).hasSize((int) eventCountAfterFirst);
            assertThat(new EventSourcedPostStore(store, journal, CLOCK).load())
                    .isEqualTo(postSnapshot);
            assertThat(new EventSourcedComplaintStore(store, journal, CLOCK).load())
                    .isEqualTo(complaintSnapshot);
            assertThat(new TaskflowSnapshotSnapshotReader(store).loadLatest())
                    .contains(taskflowSnapshot);

            assertThat(Files.readAllBytes(postsFile)).isEqualTo(updatedPostsBytes);
            assertThat(Files.readAllBytes(complaintsFile)).isEqualTo(updatedComplaintsBytes);
            assertThat(Files.readAllBytes(taskflowFile)).isEqualTo(updatedTaskflowBytes);
        }

        assertThat(postsFile).exists();
        assertThat(complaintsFile).exists();
        assertThat(taskflowFile).exists();
    }

    @Test
    void missingReconciledWithOrdinaryMigratedEventFailsClosedAmbiguousOnEveryStart() throws Exception {
        Path dataDir = tempDir.resolve("ambiguous-data");
        Files.createDirectories(dataDir);
        ComplaintService legacyComplaints = new ComplaintService(
                new JsonComplaintStore(dataDir), CLOCK);
        assertThat(legacyComplaints.submit(PLAYER, "ambiguous 投诉", "ambiguous 投诉正文",
                "ambiguous-complaint").ok()).isTrue();
        Path complaintsFile = dataDir.resolve(JsonComplaintStore.FILE_NAME);
        byte[] before = Files.readAllBytes(complaintsFile);

        Path db = dataDir.resolve("events.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal realJournal = new SqliteDomainEventJournal(store);
            // 模拟迁移事件已成功、reconciled 标记写入前进程崩溃。
            DomainEventJournal crashBeforeReconciled = event -> {
                if (DomainEventTypes.LEGACY_COMPLAINT_MIGRATION_RECONCILED.equals(
                        event.eventType())) {
                    throw new IllegalStateException("模拟 reconciled 写入前崩溃");
                }
                return realJournal.append(event);
            };
            assertThatThrownBy(new LegacyEventMigrator(
                    dataDir, store, crashBeforeReconciled, CLOCK)::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模拟 reconciled 写入前崩溃");
            assertThat(store.latestByType(DomainEventTypes.COMPLAINT_LEGACY_MIGRATED))
                    .isPresent();
            assertThat(store.latestByType(
                    DomainEventTypes.LEGACY_COMPLAINT_MIGRATION_RECONCILED)).isEmpty();

            // 之后每次启动都必须 ambiguous fail-closed，不能猜来源。
            LegacyEventMigrator realMigrator = new LegacyEventMigrator(
                    dataDir, store, realJournal, CLOCK);
            assertThatThrownBy(realMigrator::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LEGACY_EVENT_MIGRATION_STATE_AMBIGUOUS]");
            assertThatThrownBy(realMigrator::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LEGACY_EVENT_MIGRATION_STATE_AMBIGUOUS]");
        }
        assertThat(complaintsFile).exists();
        assertThat(Files.readAllBytes(complaintsFile)).isEqualTo(before);
    }

    @Test
    void reconciliationMismatchFailsClosedAndPreservesLegacyFile() throws Exception {
        Path dataDir = tempDir.resolve("mismatch-data");
        Files.createDirectories(dataDir);
        ComplaintService legacyComplaints = new ComplaintService(
                new JsonComplaintStore(dataDir), CLOCK);
        assertThat(legacyComplaints.submit(PLAYER, "对账投诉", "对账不一致正文",
                "mismatch-complaint").ok()).isTrue();
        Path complaintsFile = dataDir.resolve(JsonComplaintStore.FILE_NAME);
        byte[] before = Files.readAllBytes(complaintsFile);

        Path db = dataDir.resolve("events.db");
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal realJournal = new SqliteDomainEventJournal(store);
            DomainEventJournal tamperingJournal = event -> {
                if (DomainEventTypes.COMPLAINT_LEGACY_MIGRATED.equals(event.eventType())) {
                    return realJournal.append(new DomainEvent(event.occurredAt(),
                            event.eventType(), event.actorRole(), event.actorId(),
                            event.correlationId(), event.turnId(), event.visibility(),
                            ComplaintSnapshot.empty(), event.summary()));
                }
                return realJournal.append(event);
            };
            assertThatThrownBy(new LegacyEventMigrator(
                    dataDir, store, tamperingJournal, CLOCK)::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LEGACY_EVENT_MIGRATION_RECONCILIATION]");
        }
        assertThat(complaintsFile).exists();
        assertThat(Files.readAllBytes(complaintsFile)).isEqualTo(before);
    }

    @Test
    void tamperedMarkerTargetTypeFailsClosedReconciliationInsteadOfSkipping() throws Exception {
        MigratedPostFixture fixture = migratePostsFixture("marker-target-tampered");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + fixture.db());
             PreparedStatement tamper = connection.prepareStatement(
                     "UPDATE events SET type = 'POST_SNAPSHOT' WHERE seq = ?")) {
            tamper.setLong(1, fixture.targetSeq());
            assertThat(tamper.executeUpdate()).isEqualTo(1);
        }

        try (EventContentStore store = new EventContentStore(fixture.db())) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            LegacyEventMigrator migrator = new LegacyEventMigrator(
                    fixture.dataDir(), store, journal, CLOCK);
            assertThatThrownBy(migrator::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LEGACY_EVENT_MIGRATION_RECONCILIATION]");
        }
        assertThat(fixture.postsFile()).exists();
        assertThat(Files.readAllBytes(fixture.postsFile())).isEqualTo(fixture.postsBytes());
    }

    @Test
    void deletedMarkerTargetEventFailsClosedReconciliationInsteadOfSkipping() throws Exception {
        MigratedPostFixture fixture = migratePostsFixture("marker-target-deleted");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + fixture.db());
             PreparedStatement deleteContent = connection.prepareStatement(
                     "DELETE FROM event_contents WHERE seq = ?");
             PreparedStatement deleteEvent = connection.prepareStatement(
                     "DELETE FROM events WHERE seq = ?")) {
            deleteContent.setLong(1, fixture.targetSeq());
            deleteContent.executeUpdate();
            deleteEvent.setLong(1, fixture.targetSeq());
            assertThat(deleteEvent.executeUpdate()).isEqualTo(1);
        }

        try (EventContentStore store = new EventContentStore(fixture.db())) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            LegacyEventMigrator migrator = new LegacyEventMigrator(
                    fixture.dataDir(), store, journal, CLOCK);
            assertThatThrownBy(migrator::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LEGACY_EVENT_MIGRATION_RECONCILIATION]");
        }
        assertThat(fixture.postsFile()).exists();
        assertThat(Files.readAllBytes(fixture.postsFile())).isEqualTo(fixture.postsBytes());
    }

    private MigratedPostFixture migratePostsFixture(String directoryName) throws Exception {
        Path dataDir = tempDir.resolve(directoryName);
        Files.createDirectories(dataDir);
        PostService legacyPosts = new PostService(new JsonPostStore(dataDir), CLOCK);
        assertThat(legacyPosts.createDraft(PLAYER, "marker 目标标题", "marker 目标正文",
                PostVisibility.PUBLIC, 0, "marker-target-post").ok()).isTrue();
        Path postsFile = dataDir.resolve(JsonPostStore.FILE_NAME);
        byte[] postsBytes = Files.readAllBytes(postsFile);
        Path db = dataDir.resolve("events.db");
        long targetSeq;
        try (EventContentStore store = new EventContentStore(db)) {
            DomainEventJournal journal = new SqliteDomainEventJournal(store);
            assertThat(new LegacyEventMigrator(dataDir, store, journal, CLOCK)
                    .migrate().postMigrated()).isTrue();
            StoredEvent marker = store.latestByType(
                    DomainEventTypes.LEGACY_POST_MIGRATION_RECONCILED).orElseThrow();
            JsonNode markerContent = JSON.readTree(marker.content().json());
            targetSeq = markerContent.path("migrationEventSeq").asLong();
            assertThat(targetSeq).isPositive();
        }
        return new MigratedPostFixture(dataDir, db, postsFile, postsBytes, targetSeq);
    }

    private record MigratedPostFixture(Path dataDir, Path db, Path postsFile,
                                       byte[] postsBytes, long targetSeq) {
    }

    private static StoredEvent byType(List<StoredEvent> events, String type) {
        return events.stream()
                .filter(event -> event.eventType().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少事件 " + type));
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

    private static TaskflowStateSnapshot minimalTaskflowSnapshot() {
        return new TaskflowStateSnapshot(TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION,
                "prompt-v1", 0L, 0L, 0L, 0L, 0, null,
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
