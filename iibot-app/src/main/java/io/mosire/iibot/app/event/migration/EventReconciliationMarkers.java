package io.mosire.iibot.app.event.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 旧数据迁移/fresh baseline 的持久完成标记工具。
 *
 * <p>marker content 只保存被引用事件的索引元数据，不复制正文。verify 必须确认目标事件
 * 仍存在、类型属于调用方允许集合、带 JSON 完整正文，并且 {@code content_hash} 与 marker
 * 记录完全一致；任一缺失/不一致都 fail-closed，禁止把损坏迁移误判为空领域状态。</p>
 */
public final class EventReconciliationMarkers {

    private static final String MARKER_ACTOR = "system:legacy-migration";
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;

    public EventReconciliationMarkers(EventContentStore contentStore,
                                      DomainEventJournal journal, Clock clock) {
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 追加 reconciled marker。{@code targetEvent} 必须是已成功写入的完整迁移/基线事件。
     */
    public long append(String markerEventType, StoredEvent targetEvent, String sourceFile,
                       int recordCount, Map<String, Object> additionalMetadata) {
        requireMarkerType(markerEventType);
        Objects.requireNonNull(targetEvent, "targetEvent");
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("sourceFile 不能为空白");
        }
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount 不能为负");
        }
        if (targetEvent.contentMissing() || targetEvent.content() == null
                || targetEvent.content().type() != EventContentType.JSON) {
            throw new IllegalArgumentException("reconciled marker 目标事件缺少 JSON 完整正文");
        }
        if (targetEvent.contentHash() == null || targetEvent.contentHash().isBlank()) {
            throw new IllegalArgumentException("reconciled marker 目标事件缺少 contentHash");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("migrationEventType", targetEvent.eventType());
        metadata.put("migrationEventSeq", targetEvent.seq());
        metadata.put("sourceFile", sourceFile);
        metadata.put("recordCount", recordCount);
        metadata.put("contentHash", targetEvent.contentHash());
        if (additionalMetadata != null) {
            Set<String> reserved = Set.of("migrationEventType", "migrationEventSeq",
                    "sourceFile", "recordCount", "contentHash");
            additionalMetadata.forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("additionalMetadata key 不能为空白");
                }
                if (reserved.contains(key)) {
                    throw new IllegalArgumentException(
                            "additionalMetadata 不得覆盖必需字段: " + key);
                }
                metadata.put(key, value);
            });
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("migrationEventType", targetEvent.eventType());
        summary.put("migrationEventSeq", targetEvent.seq());
        summary.put("sourceFile", sourceFile);
        summary.put("recordCount", recordCount);
        DomainEvent event = new DomainEvent(clock.instant(), markerEventType,
                ActorRole.SUPERVISOR, MARKER_ACTOR, "reconciled:" + sourceFile, null,
                EventVisibility.GM, metadata, summary);
        long seq = journal.append(event);
        if (seq <= 0) {
            throw new IllegalStateException("[LEGACY_EVENT_MIGRATION] reconciled marker 未返回有效 seq");
        }
        return seq;
    }

    /**
     * 若 exact marker 存在，则解析并校验其目标事件；不存在返回 empty。
     *
     * @param allowedMigrationTypes 允许被 marker 引用的目标事件类型
     */
    public Optional<StoredEvent> verify(String markerEventType,
                                        Set<String> allowedMigrationTypes) {
        requireMarkerType(markerEventType);
        if (allowedMigrationTypes == null || allowedMigrationTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedMigrationTypes 不能为空");
        }
        Optional<StoredEvent> marker = contentStore.latestByType(markerEventType);
        if (marker.isEmpty()) {
            return Optional.empty();
        }
        StoredEvent markerEvent = marker.orElseThrow();
        if (markerEvent.contentMissing() || markerEvent.content() == null
                || markerEvent.content().type() != EventContentType.JSON) {
            throw reconciliationFailure("marker 缺少 JSON 完整正文 type=" + markerEventType);
        }

        final JsonNode root;
        try {
            root = MAPPER.readTree(markerEvent.content().json());
        } catch (JsonProcessingException failure) {
            throw reconciliationFailure("marker JSON 无法解析 type=" + markerEventType);
        }
        if (root == null || !root.isObject()) {
            throw reconciliationFailure("marker content 不是 JSON 对象 type=" + markerEventType);
        }

        String migrationEventType = requiredText(root, "migrationEventType", markerEventType);
        long migrationEventSeq = requiredPositiveLong(root, "migrationEventSeq", markerEventType);
        String sourceFile = requiredText(root, "sourceFile", markerEventType);
        long recordCount = requiredNonNegativeLong(root, "recordCount", markerEventType);
        String contentHash = requiredText(root, "contentHash", markerEventType);
        if (!allowedMigrationTypes.contains(migrationEventType)) {
            throw reconciliationFailure("marker 目标类型不在允许集合 type=" + migrationEventType
                    + " sourceFile=" + sourceFile + " recordCount=" + recordCount);
        }

        Optional<StoredEvent> targetOpt = contentStore.read(migrationEventSeq);
        if (targetOpt.isEmpty()) {
            throw reconciliationFailure("marker 目标事件不存在 migrationEventSeq="
                    + migrationEventSeq + " type=" + migrationEventType);
        }
        StoredEvent target = targetOpt.orElseThrow();
        if (!target.eventType().equals(migrationEventType)) {
            throw reconciliationFailure("marker 目标类型不匹配 expected=" + migrationEventType
                    + " actual=" + target.eventType() + " seq=" + migrationEventSeq);
        }
        if (target.contentMissing() || target.content() == null
                || target.content().type() != EventContentType.JSON) {
            throw reconciliationFailure("marker 目标事件缺少 JSON 完整正文 seq="
                    + migrationEventSeq);
        }
        if (!target.contentHash().equals(contentHash)) {
            throw reconciliationFailure("marker 目标 contentHash 不一致 seq=" + migrationEventSeq);
        }
        return Optional.of(markerEvent);
    }

    private static String requiredText(JsonNode root, String field, String markerType) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw reconciliationFailure("marker 缺少字段 " + field + " type=" + markerType);
        }
        return node.asText();
    }

    private static long requiredPositiveLong(JsonNode root, String field, String markerType) {
        long value = requiredNonNegativeLong(root, field, markerType);
        if (value <= 0) {
            throw reconciliationFailure("marker 字段必须为正 " + field + " type=" + markerType);
        }
        return value;
    }

    private static long requiredNonNegativeLong(JsonNode root, String field, String markerType) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw reconciliationFailure("marker 缺少整数字段 " + field + " type=" + markerType);
        }
        long value = node.longValue();
        if (value < 0) {
            throw reconciliationFailure("marker 字段不能为负 " + field + " type=" + markerType);
        }
        return value;
    }

    private static void requireMarkerType(String markerEventType) {
        if (markerEventType == null || markerEventType.isBlank()) {
            throw new IllegalArgumentException("markerEventType 不能为空白");
        }
        if (markerEventType.startsWith("POST_")
                || markerEventType.startsWith("COMPLAINT_")
                || markerEventType.startsWith("TASKFLOW_SNAPSHOT")) {
            throw new IllegalArgumentException("reconciled marker 不得使用领域快照前缀: "
                    + markerEventType);
        }
    }

    private static IllegalStateException reconciliationFailure(String detail) {
        return new IllegalStateException("[LEGACY_EVENT_MIGRATION_RECONCILIATION] " + detail);
    }
}
