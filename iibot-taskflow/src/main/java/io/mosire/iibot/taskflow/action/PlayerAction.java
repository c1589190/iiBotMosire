package io.mosire.iibot.taskflow.action;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条由玩家提交、可由 GM 选择进入裁定包的自然语言行动。
 *
 * <p>{@code text} 是行动正文；它是领域数据，只允许交给持久化快照与裁定包 items。所有日志和
 * 普通对象字符串都不得包含正文，因此 {@link #toString()} 明确排除了它。</p>
 */
public record PlayerAction(
        String actionId,
        String playerId,
        String text,
        ActionStatus status,
        String packageId,
        Instant createdAt,
        Instant updatedAt,
        String source,
        String idempotencyKey) {

    public PlayerAction {
        requireText(actionId, "actionId");
        requireText(playerId, "playerId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requireText(source, "source");
        requireText(idempotencyKey, "idempotencyKey");
    }

    /** 返回更新状态后的新值；packageId 可为 null（QUEUED/WITHDRAWN）。 */
    public PlayerAction withStatus(ActionStatus nextStatus, String nextPackageId, Instant at) {
        return new PlayerAction(actionId, playerId, text, nextStatus, nextPackageId,
                createdAt, Objects.requireNonNull(at, "at"), source, idempotencyKey);
    }

    /** 正文长度（字符数），只用于审计摘要。 */
    public int textLength() {
        return text.length();
    }

    @Override
    public String toString() {
        return "PlayerAction[actionId=" + actionId
                + ", playerId=" + playerId
                + ", status=" + status
                + ", packageId=" + packageId
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ", source=" + source
                + ", idempotencyKey=" + idempotencyKey
                + ", textLength=" + text.length()
                + ']';
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
