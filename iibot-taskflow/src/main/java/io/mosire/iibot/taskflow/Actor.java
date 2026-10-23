package io.mosire.iibot.taskflow;

import java.util.Objects;

/**
 * 已由宿主认证的操作者快照。
 *
 * <p>引擎把 actor 当作授权事实，不接受聊天文本里自称的角色。actorId 在玩家操作中
 * 必须与 playerId 相等，从而防止“替别人确认”。</p>
 */
public record Actor(String actorId, ActorRole role) {
    public Actor {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        Objects.requireNonNull(role, "role");
    }
}
