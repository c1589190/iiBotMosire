package io.mosire.iibot.taskflow.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 从完整事件库读取的一条事件。
 *
 * <p>{@code contentMissing=true} 只用于旧审计事件迁移兼容：这类事件没有正文，
 * 不能据此回放或对外投影。新写入事件必须带正文且 hash 可在读取时重新校验。</p>
 */
public record StoredEvent(
        long seq,
        Instant occurredAt,
        String eventType,
        String agent,
        String correlationId,
        String turnId,
        long eventVersion,
        EventVisibility visibility,
        String contentHash,
        boolean contentMissing,
        String payloadJson,
        EventContent content) {

    public StoredEvent {
        if (seq <= 0) {
            throw new IllegalArgumentException("seq 必须为正");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 不能为空");
        }
        if (agent == null || agent.isBlank()) {
            throw new IllegalArgumentException("agent 不能为空");
        }
        correlationId = correlationId == null ? "" : correlationId;
        turnId = turnId == null || turnId.isBlank() ? null : turnId;
        if (eventVersion < 0) {
            throw new IllegalArgumentException("eventVersion 不能为负");
        }
        Objects.requireNonNull(visibility, "visibility");
        contentHash = contentHash == null ? "" : contentHash;
        payloadJson = payloadJson == null ? "" : payloadJson;
        if (contentMissing && content != null) {
            throw new IllegalArgumentException("contentMissing 事件不能携带正文");
        }
        if (!contentMissing && content == null) {
            throw new IllegalArgumentException("完整事件必须携带正文");
        }
    }
}
