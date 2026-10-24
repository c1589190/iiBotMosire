package io.mosire.iibot.app.event.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.complaint.JsonComplaintStore;
import io.mosire.iibot.app.event.TaskflowSnapshotStore;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.publication.JsonPostStore;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.ComplaintSnapshot;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import io.mosire.iibot.taskflow.snapshot.TurnStateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 启动期旧 JSON → events.db 迁移与持久完成标记器。
 *
 * <p>迁移使用两阶段标记：先追加 exact {@code POST_LEGACY_MIGRATED} /
 * {@code COMPLAINT_LEGACY_MIGRATED} / {@code TASKFLOW_SNAPSHOT_MIGRATED} 完整快照
 * 事件并立即对账，再追加不含正文的 exact {@code LEGACY_POST_MIGRATION_RECONCILED} /
 * {@code LEGACY_COMPLAINT_MIGRATION_RECONCILED} /
 * {@code TASKFLOW_BASELINE_RECONCILED} SQLite 完成标记。完成标记存在后，迁移阶段直接
 * 跳过，不再要求后续可能继续变化的 taskflow-state.json 与旧迁移事件永久 equals。</p>
 *
 * <p>若迁移事件已写入但完成标记缺失/进程崩溃，下次启动会因为“无完成标记 + 对应普通
 * 事件已存在”而报 {@code [LEGACY_EVENT_MIGRATION_STATE_AMBIGUOUS]}，不静默选择来源。
 * 旧 JSON 永不删除、改名或覆盖。</p>
 */
public final class LegacyEventMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyEventMigrator.class);
    private static final String MIGRATION_ACTOR = "system:legacy-migration";
    private static final ObjectMapper MAPPER = createMapper();

    private final Path dataDir;
    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;
    private final EventReconciliationMarkers reconciliationMarkers;

    public LegacyEventMigrator(Path dataDir, EventContentStore contentStore,
                               DomainEventJournal journal) {
        this(dataDir, contentStore, journal, Clock.systemUTC());
    }

    public LegacyEventMigrator(Path dataDir, EventContentStore contentStore,
                               DomainEventJournal journal, Clock clock) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reconciliationMarkers = new EventReconciliationMarkers(
                this.contentStore, this.journal, this.clock);
    }

    public LegacyEventMigrator(EventContentStore contentStore, DomainEventJournal journal,
                               Path dataDir) {
        this(dataDir, contentStore, journal);
    }

    /** 执行迁移与对账；返回不含正文的计数报告。失败时抛异常并保留旧 JSON。 */
    public MigrationReport migrate() {
        PostMigration post = migratePosts();
        ComplaintMigration complaint = migrateComplaints();
        TaskflowMigration taskflow = migrateTaskflowSnapshot();
        return new MigrationReport(post.migrated(), post.recordCount(),
                complaint.migrated(), complaint.recordCount(),
                taskflow.migrated(), taskflow.eventSeq());
    }

    /** 供启动路径以 Runnable 形式调用的别名。 */
    public void run() {
        migrate();
    }

    private PostMigration migratePosts() {
        if (reconciliationMarkers.verify(
                DomainEventTypes.LEGACY_POST_MIGRATION_RECONCILED,
                Set.of(DomainEventTypes.POST_LEGACY_MIGRATED)).isPresent()) {
            return new PostMigration(false, 0);
        }
        Path legacyFile = dataDir.resolve(JsonPostStore.FILE_NAME);
        if (!Files.exists(legacyFile)) {
            return new PostMigration(false, 0);
        }

        Optional<StoredEvent> ordinary = contentStore.latestByTypePrefix(
                DomainEventTypes.POST_PREFIX);
        if (ordinary.isPresent()) {
            throw ambiguous("posts.json", "POST_", ordinary.orElseThrow().seq());
        }

        PostSnapshot legacy = new JsonPostStore(dataDir).load();
        DomainEvent event = new DomainEvent(clock.instant(),
                DomainEventTypes.POST_LEGACY_MIGRATED, ActorRole.SUPERVISOR, MIGRATION_ACTOR,
                "legacy:posts.json", null, EventVisibility.GM, legacy, Map.of(
                "migrated", true,
                "sourceFile", JsonPostStore.FILE_NAME,
                "schemaVersion", JsonPostStore.CURRENT_SCHEMA_VERSION,
                "recordCount", legacy.size()));
        long seq = appendMigrationEvent(event, "推文");
        StoredEvent stored = requireMarker(DomainEventTypes.POST_LEGACY_MIGRATED, "posts");
        PostSnapshot reloaded = readSnapshot(stored, PostSnapshot.class, "posts");
        reconcile("posts.json", legacy, reloaded, seq);
        reconciliationMarkers.append(DomainEventTypes.LEGACY_POST_MIGRATION_RECONCILED,
                stored, JsonPostStore.FILE_NAME, legacy.size(), Map.of());
        LOG.info("旧推文 JSON 已迁移、对账并写入完成标记 sourceFile={} records={} eventSeq={}",
                JsonPostStore.FILE_NAME, legacy.size(), seq);
        return new PostMigration(true, legacy.size());
    }

    private ComplaintMigration migrateComplaints() {
        if (reconciliationMarkers.verify(
                DomainEventTypes.LEGACY_COMPLAINT_MIGRATION_RECONCILED,
                Set.of(DomainEventTypes.COMPLAINT_LEGACY_MIGRATED)).isPresent()) {
            return new ComplaintMigration(false, 0);
        }
        Path legacyFile = dataDir.resolve(JsonComplaintStore.FILE_NAME);
        if (!Files.exists(legacyFile)) {
            return new ComplaintMigration(false, 0);
        }

        Optional<StoredEvent> ordinary = contentStore.latestByTypePrefix(
                DomainEventTypes.COMPLAINT_PREFIX);
        if (ordinary.isPresent()) {
            throw ambiguous("complaints.json", "COMPLAINT_", ordinary.orElseThrow().seq());
        }

        ComplaintSnapshot legacy = new JsonComplaintStore(dataDir).load();
        DomainEvent event = new DomainEvent(clock.instant(),
                DomainEventTypes.COMPLAINT_LEGACY_MIGRATED, ActorRole.SUPERVISOR,
                MIGRATION_ACTOR, "legacy:complaints.json", null, EventVisibility.GM,
                legacy, Map.of(
                "migrated", true,
                "sourceFile", JsonComplaintStore.FILE_NAME,
                "schemaVersion", JsonComplaintStore.CURRENT_SCHEMA_VERSION,
                "recordCount", legacy.size()));
        long seq = appendMigrationEvent(event, "投诉");
        StoredEvent stored = requireMarker(DomainEventTypes.COMPLAINT_LEGACY_MIGRATED,
                "complaints");
        ComplaintSnapshot reloaded = readSnapshot(stored, ComplaintSnapshot.class, "complaints");
        reconcile("complaints.json", legacy, reloaded, seq);
        reconciliationMarkers.append(
                DomainEventTypes.LEGACY_COMPLAINT_MIGRATION_RECONCILED,
                stored, JsonComplaintStore.FILE_NAME, legacy.size(), Map.of());
        LOG.info("旧投诉 JSON 已迁移、对账并写入完成标记 sourceFile={} records={} eventSeq={}",
                JsonComplaintStore.FILE_NAME, legacy.size(), seq);
        return new ComplaintMigration(true, legacy.size());
    }

    private TaskflowMigration migrateTaskflowSnapshot() {
        if (reconciliationMarkers.verify(
                DomainEventTypes.TASKFLOW_BASELINE_RECONCILED,
                Set.of(DomainEventTypes.TASKFLOW_SNAPSHOT_MIGRATED,
                        DomainEventTypes.TASKFLOW_SNAPSHOT)).isPresent()) {
            return new TaskflowMigration(false, 0L);
        }
        Path legacyFile = dataDir.resolve(TaskflowSnapshotStore.FILE_NAME);
        if (!Files.exists(legacyFile)) {
            return new TaskflowMigration(false, 0L);
        }

        Optional<StoredEvent> ordinary = contentStore.latestByTypePrefix(
                DomainEventTypes.TASKFLOW_SNAPSHOT_PREFIX);
        if (ordinary.isPresent()) {
            throw ambiguous(TaskflowSnapshotStore.FILE_NAME, "TASKFLOW_SNAPSHOT",
                    ordinary.orElseThrow().seq());
        }

        TaskflowStateSnapshot legacy = new TaskflowSnapshotStore(dataDir).load()
                .orElseThrow(() -> new IllegalStateException(
                        "[LEGACY_EVENT_MIGRATION] taskflow-state.json 存在但快照读取为空"));
        TurnStateSnapshot turn = legacy.currentTurnId() == null
                ? null : legacy.turns().get(legacy.currentTurnId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("migrated", true);
        summary.put("sourceFile", TaskflowSnapshotStore.FILE_NAME);
        summary.put("schemaVersion", legacy.schemaVersion());
        summary.put("eventSeq", legacy.lastEventSeq());
        summary.put("eventCount", legacy.eventCount());
        summary.put("worldVersion", legacy.worldVersion());
        summary.put("turnId", legacy.currentTurnId() == null ? "" : legacy.currentTurnId());
        summary.put("turnNo", turn == null ? 0 : turn.turnNo());
        summary.put("state", turn == null ? "NONE" : turn.state().name());
        DomainEvent event = new DomainEvent(clock.instant(),
                DomainEventTypes.TASKFLOW_SNAPSHOT_MIGRATED, ActorRole.SUPERVISOR,
                MIGRATION_ACTOR, "legacy:taskflow-state.json", legacy.currentTurnId(),
                EventVisibility.GM, legacy, summary);
        long seq = appendMigrationEvent(event, "Taskflow 快照");
        StoredEvent stored = requireMarker(DomainEventTypes.TASKFLOW_SNAPSHOT_MIGRATED,
                "taskflow");
        TaskflowStateSnapshot reloaded = readSnapshot(stored, TaskflowStateSnapshot.class,
                "taskflow");
        reconcile(TaskflowSnapshotStore.FILE_NAME, legacy, reloaded, seq);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("lastEventSeq", legacy.lastEventSeq());
        extra.put("eventCount", legacy.eventCount());
        reconciliationMarkers.append(DomainEventTypes.TASKFLOW_BASELINE_RECONCILED,
                stored, TaskflowSnapshotStore.FILE_NAME, legacy.turns().size(), extra);
        LOG.info("旧 Taskflow 快照 JSON 已迁移、对账并写入完成标记 sourceFile={} eventSeq={} "
                        + "worldVersion={} eventCount={}",
                TaskflowSnapshotStore.FILE_NAME, seq, legacy.lastEventSeq(),
                legacy.eventCount());
        return new TaskflowMigration(true, seq);
    }

    private long appendMigrationEvent(DomainEvent event, String domain) {
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new IllegalStateException("[LEGACY_EVENT_MIGRATION] " + domain
                    + "迁移事件未返回有效 seq");
        }
        return seq;
    }

    private StoredEvent requireMarker(String eventType, String domain) {
        return contentStore.latestByType(eventType).orElseThrow(() ->
                new IllegalStateException("[LEGACY_EVENT_MIGRATION] " + domain
                        + " 迁移后缺少 exact 标记事件 type=" + eventType));
    }

    private <T> T readSnapshot(StoredEvent event, Class<T> type, String domain) {
        if (event.contentMissing() || event.content() == null
                || event.content().type() != EventContentType.JSON) {
            throw new IllegalStateException("[LEGACY_EVENT_MIGRATION_CONTENT_CORRUPT] domain="
                    + domain + " seq=" + event.seq() + " 缺少 JSON 完整正文");
        }
        try {
            T value = MAPPER.readValue(event.content().json(), type);
            if (value == null) {
                throw new IllegalStateException("[LEGACY_EVENT_MIGRATION_CONTENT_CORRUPT] domain="
                        + domain + " seq=" + event.seq() + " 反序列化结果为空");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("[LEGACY_EVENT_MIGRATION_CONTENT_CORRUPT] domain="
                    + domain + " seq=" + event.seq() + " JSON 无法解析 error="
                    + failure.getClass().getSimpleName(), failure);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("[LEGACY_EVENT_MIGRATION_CONTENT_CORRUPT] domain="
                    + domain + " seq=" + event.seq() + " 领域校验失败 error="
                    + failure.getClass().getSimpleName(), failure);
        }
    }

    private static IllegalStateException ambiguous(String sourceFile, String prefix, long seq) {
        return new IllegalStateException("[LEGACY_EVENT_MIGRATION_STATE_AMBIGUOUS] "
                + sourceFile + " 存在但缺少完成标记，且已有 " + prefix + " 普通事件 seq=" + seq);
    }

    private static void reconcile(String sourceFile, Object legacy, Object migrated, long seq) {
        if (!legacy.equals(migrated)) {
            throw new IllegalStateException("[LEGACY_EVENT_MIGRATION_RECONCILIATION] "
                    + sourceFile + " 与 events.db 迁移事件内容不一致 eventSeq=" + seq);
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }

    /** 迁移结果只含布尔、计数与 seq，不含正文。字段保持既有兼容形状。 */
    public record MigrationReport(
            boolean postMigrated,
            int postRecordCount,
            boolean complaintMigrated,
            int complaintRecordCount,
            boolean taskflowMigrated,
            long taskflowEventSeq) {
    }

    private record PostMigration(boolean migrated, int recordCount) {
    }

    private record ComplaintMigration(boolean migrated, int recordCount) {
    }

    private record TaskflowMigration(boolean migrated, long eventSeq) {
    }
}
