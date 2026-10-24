package io.mosire.iibot.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * 从 events.db 读取最新 Taskflow 完整快照。
 *
 * <p>同时匹配 {@code TASKFLOW_SNAPSHOT} 与 {@code TASKFLOW_SNAPSHOT_MIGRATED}
 * 前缀，供迁移对账和只读重建使用。该读取器不参与 live engine 恢复；P2 仍以
 * taskflow-state.json 为恢复缓存。非法/损坏 JSON 一律 fail-closed。</p>
 */
public final class TaskflowSnapshotSnapshotReader {

    private static final ObjectMapper MAPPER = createMapper();

    private final EventContentStore contentStore;

    public TaskflowSnapshotSnapshotReader(EventContentStore contentStore) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
    }

    /** 最新 TASKFLOW_SNAPSHOT* 原始事件；用于 seq 比较与迁移对账。 */
    public Optional<StoredEvent> loadLatestEvent() {
        return contentStore.latestByTypePrefix(DomainEventTypes.TASKFLOW_SNAPSHOT_PREFIX);
    }

    public Optional<TaskflowStateSnapshot> loadLatest() {
        Optional<StoredEvent> latest = loadLatestEvent();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        StoredEvent event = latest.orElseThrow();
        if (event.contentMissing() || event.content() == null
                || event.content().type() != EventContentType.JSON) {
            throw new IllegalStateException(
                    "[TASKFLOW_SNAPSHOT_EVENT_CORRUPT] 最新快照事件缺少 JSON 完整正文 seq="
                            + event.seq());
        }
        try {
            TaskflowStateSnapshot snapshot = MAPPER.readValue(event.content().json(),
                    TaskflowStateSnapshot.class);
            if (snapshot == null) {
                throw new IllegalStateException(
                        "[TASKFLOW_SNAPSHOT_EVENT_CORRUPT] 快照事件反序列化结果为空 seq="
                                + event.seq());
            }
            return Optional.of(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "[TASKFLOW_SNAPSHOT_EVENT_CORRUPT] 快照事件 JSON 无法解析 seq="
                            + event.seq() + " error=" + failure.getClass().getSimpleName(),
                    failure);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "[TASKFLOW_SNAPSHOT_EVENT_CORRUPT] 快照事件领域校验失败 seq="
                            + event.seq() + " error=" + failure.getClass().getSimpleName(),
                    failure);
        }
    }

    /** 语义别名，便于迁移/测试调用。 */
    public Optional<TaskflowStateSnapshot> readLatest() {
        return loadLatest();
    }

    public Optional<TaskflowStateSnapshot> read() {
        return loadLatest();
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}
