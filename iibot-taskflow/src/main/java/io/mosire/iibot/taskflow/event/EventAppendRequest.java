package io.mosire.iibot.taskflow.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 完整事件追加请求。
 *
 * <p>{@code payloadJson} 是检索/索引元数据（JSON 对象）；大段正文放 {@link #content()}。
 * 生产存储必须在同一事务中写入 headers 与正文，失败时不得留下半条事件。</p>
 */
public record EventAppendRequest(
        Instant occurredAt,
        String eventType,
        String agent,
        String correlationId,
        String turnId,
        long eventVersion,
        EventVisibility visibility,
        EventContent content,
        String payloadJson) {

    public EventAppendRequest {
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
        Objects.requireNonNull(content, "content");
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson 不能为空");
        }
    }
}
