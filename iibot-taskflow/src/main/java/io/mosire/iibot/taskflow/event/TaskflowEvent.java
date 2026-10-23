package io.mosire.iibot.taskflow.event;

import io.mosire.iibot.taskflow.ActorRole;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 不可变领域事件。
 *
 * <p>seq 由 EventSink 追加时分配，因此同一个 idempotencyKey 对应的事件只会出现
 * 一次。payload 只保存审计所需的标识和摘要，不保存 QQ 凭据、token 或大段模型
 * 上下文。</p>
 */
public record TaskflowEvent(
        long seq,
        Instant occurredAt,
        EventType type,
        ActorRole actorRole,
        String actorId,
        String turnId,
        String idempotencyKey,
        Map<String, String> payload) {

    public TaskflowEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorRole, "actorRole");
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** 由存储端口分配 seq 时使用的副本。 */
    public TaskflowEvent withSeq(long newSeq) {
        return new TaskflowEvent(newSeq, occurredAt, type, actorRole, actorId,
                turnId, idempotencyKey, payload);
    }
}
