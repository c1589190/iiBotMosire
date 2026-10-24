package io.mosire.iibot.app.event;

import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.event.migration.EventReconciliationMarkers;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import io.mosire.iibot.taskflow.snapshot.TurnStateSnapshot;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 TaskflowEngine 的完整状态快照追加为 {@code TASKFLOW_SNAPSHOT} 事件。
 *
 * <p>content 是完整 {@link TaskflowStateSnapshot}；summary 只放 seq/eventCount/
 * worldVersion/turnId/turnNo/state 等索引。原有 taskflow-state.json 仍由
 * {@link TaskflowSnapshotStore} 原子保存，是本阶段的恢复缓存。</p>
 *
 * <p>运行期 listener 由组合根做 best-effort 包装；启动期 {@link #ensureRecorded}
 * 必须 fail-closed，负责修复上次运行未写入或 stale 的快照事件。</p>
 */
public final class TaskflowSnapshotEventRecorder {

    private static final String SNAPSHOT_ACTOR = "system:taskflow-snapshot";

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public TaskflowSnapshotEventRecorder(DomainEventJournal journal) {
        this(null, journal, Clock.systemUTC());
    }

    public TaskflowSnapshotEventRecorder(DomainEventJournal journal, Clock clock) {
        this(null, journal, clock);
    }

    public TaskflowSnapshotEventRecorder(Clock clock, DomainEventJournal journal) {
        this(null, journal, clock);
    }

    public TaskflowSnapshotEventRecorder(EventContentStore contentStore,
                                         DomainEventJournal journal) {
        this(contentStore, journal, Clock.systemUTC());
    }

    public TaskflowSnapshotEventRecorder(EventContentStore contentStore,
                                         DomainEventJournal journal, Clock clock) {
        this.contentStore = contentStore;
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TaskflowSnapshotEventRecorder(EventContentStore contentStore, Clock clock,
                                         DomainEventJournal journal) {
        this(contentStore, journal, clock);
    }

    /** 记录一份完整快照并返回 events.db 分配的 seq。 */
    public long record(TaskflowStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        TurnStateSnapshot turn = currentTurn(snapshot);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventSeq", snapshot.lastEventSeq());
        summary.put("eventCount", snapshot.eventCount());
        summary.put("worldVersion", snapshot.worldVersion());
        summary.put("turnId", snapshot.currentTurnId());
        summary.put("turnNo", turn == null ? 0 : turn.turnNo());
        summary.put("state", turn == null ? "NONE" : turn.state().name());
        DomainEvent event = new DomainEvent(clock.instant(),
                DomainEventTypes.TASKFLOW_SNAPSHOT, ActorRole.SUPERVISOR, SNAPSHOT_ACTOR,
                snapshot.currentTurnId() == null ? "" : snapshot.currentTurnId(),
                snapshot.currentTurnId(), EventVisibility.GM, snapshot, summary);
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new IllegalStateException("[TASKFLOW_SNAPSHOT_EVENT] journal 未返回有效 seq");
        }
        return seq;
    }

    /** {@link #record(TaskflowStateSnapshot)} 的显式别名。 */
    public long recordSnapshot(TaskflowStateSnapshot snapshot) {
        return record(snapshot);
    }

    /**
     * fresh bootstrap 完成发布初始 TASKFLOW_SNAPSHOT 后，写入持久基线完成标记。
     * 该标记避免下一次启动把“taskflow-state.json + 普通 TASKFLOW_SNAPSHOT + 无迁移完成
     * 标记”误判为 ambiguous。marker content 引用目标事件的 contentHash/seq/计数，
     * 不复制完整快照正文。
     */
    public long recordBaselineReconciled(TaskflowStateSnapshot snapshot,
                                         String sourceFile,
                                         long targetEventSeq) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile 不能为空白");
        }
        if (contentStore == null) {
            throw new IllegalStateException(
                    "[TASKFLOW_BASELINE_RECONCILED] 需要 EventContentStore");
        }
        StoredEvent target = contentStore.read(targetEventSeq).orElseThrow(() ->
                new IllegalStateException(
                        "[TASKFLOW_BASELINE_RECONCILED] 目标 TASKFLOW_SNAPSHOT 不存在 seq="
                                + targetEventSeq));
        if (!DomainEventTypes.TASKFLOW_SNAPSHOT.equals(target.eventType())) {
            throw new IllegalStateException(
                    "[TASKFLOW_BASELINE_RECONCILED] 目标事件类型不是 TASKFLOW_SNAPSHOT seq="
                            + targetEventSeq);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("lastEventSeq", snapshot.lastEventSeq());
        EventReconciliationMarkers markers = new EventReconciliationMarkers(
                contentStore, journal, clock);
        return markers.append(DomainEventTypes.TASKFLOW_BASELINE_RECONCILED,
                target, sourceFile, snapshot.turns().size(), extra);
    }

    /** fresh bootstrap 基线便捷入口。 */
    public long recordFreshBaselineReconciled(TaskflowStateSnapshot snapshot) {
        long targetSeq = ensureRecorded(snapshot);
        return recordBaselineReconciled(snapshot, "fresh-bootstrap", targetSeq);
    }

    /**
     * 启动期确保 events.db 最新 TASKFLOW_SNAPSHOT* 与当前快照完全一致。
     *
     * <p>若缺失或 stale，追加一条普通 {@code TASKFLOW_SNAPSHOT} 并返回新 seq；若已一致，
     * 返回现有事件 seq。读取/追加失败均向上抛，启动必须 fail-closed。</p>
     */
    public long ensureRecorded(TaskflowStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (contentStore == null) {
            throw new IllegalStateException(
                    "[TASKFLOW_SNAPSHOT_EVENT] ensureRecorded 需要 EventContentStore");
        }
        TaskflowSnapshotSnapshotReader reader = new TaskflowSnapshotSnapshotReader(contentStore);
        Optional<StoredEvent> latest = reader.loadLatestEvent();
        if (latest.isPresent()) {
            TaskflowStateSnapshot existing = reader.loadLatest().orElseThrow(() ->
                    new IllegalStateException(
                            "[TASKFLOW_SNAPSHOT_EVENT] 快照事件存在但内容读取为空"));
            if (snapshot.equals(existing)) {
                return latest.orElseThrow().seq();
            }
        }
        return record(snapshot);
    }

    private static TurnStateSnapshot currentTurn(TaskflowStateSnapshot snapshot) {
        String turnId = snapshot.currentTurnId();
        return turnId == null ? null : snapshot.turns().get(turnId);
    }
}
