package io.mosire.iibot.taskflow.event;

import io.mosire.iibot.taskflow.ActorRole;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 完整领域事件。
 *
 * <p>{@code content} 是事件对应的完整领域对象/快照，由持久化层写入
 * {@code event_contents.content_json}；{@code summary} 只允许 ID、状态、revision、
 * visibility、计数、idempotencyKey 等索引字段，禁止复制正文。summary 序列化后进入
 * {@code events.payload_json}，供查询和兼容读取使用。</p>
 *
 * <p>事件中的 actor 必须来自宿主已认证命令或固定的系统投递身份；不得从消息正文提升
 * 权限。{@link #toString()} 刻意不输出 content/summary，避免正文进入日志。</p>
 */
public record DomainEvent(
        Instant occurredAt,
        String eventType,
        ActorRole actorRole,
        String actorId,
        String correlationId,
        String turnId,
        EventVisibility visibility,
        Object content,
        Map<String, Object> summary) {

    public DomainEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 不能为空");
        }
        Objects.requireNonNull(actorRole, "actorRole");
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        correlationId = correlationId == null ? "" : correlationId;
        turnId = turnId == null || turnId.isBlank() ? null : turnId;
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(content, "content");
        summary = summary == null || summary.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    /**
     * 兼容以字符串传入 actor role 的调用方；存储 header 仍统一为枚举名。
     */
    public DomainEvent(Instant occurredAt, String eventType, String actorRole, String actorId,
                       String correlationId, String turnId, EventVisibility visibility,
                       Object content, Map<String, Object> summary) {
        this(occurredAt, eventType, parseActorRole(actorRole), actorId, correlationId, turnId,
                visibility, content, summary);
    }

    private static ActorRole parseActorRole(String actorRole) {
        if (actorRole == null || actorRole.isBlank()) {
            throw new IllegalArgumentException("actorRole 不能为空");
        }
        try {
            return ActorRole.valueOf(actorRole);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("未知 actorRole: " + actorRole);
        }
    }

    /** 正文不进入默认日志/异常字符串；summary 也只暴露键名。 */
    @Override
    public String toString() {
        return "DomainEvent[occurredAt=" + occurredAt
                + ", eventType=" + eventType
                + ", actorRole=" + actorRole
                + ", actorId=" + actorId
                + ", correlationId=" + correlationId
                + ", turnId=" + turnId
                + ", visibility=" + visibility
                + ", contentClass=" + content.getClass().getSimpleName()
                + ", summaryKeys=" + summary.keySet() + "]";
    }
}
