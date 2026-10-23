package io.mosire.iibot.taskflow.agent;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.PlayerProfile;

import java.util.Objects;

/**
 * 由宿主配置决定的一次 QQ → 领域身份映射。
 *
 * <p>QQ 文本永远不能创建或提升该映射。{@code actor.actorId()} 必须与 profile.playerId 相等；
 * 玩家私聊只接受 role=PLAYER 的映射，推演命令只接受 OPERATOR 映射。</p>
 */
public record MappedIdentity(String qqId, Actor actor, PlayerProfile profile) {

    public MappedIdentity {
        if (qqId == null || qqId.isBlank()) {
            throw new IllegalArgumentException("qqId 不能为空");
        }
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(profile, "profile");
        if (!actor.actorId().equals(profile.playerId())) {
            throw new IllegalArgumentException("actorId 必须等于 profile.playerId");
        }
        if (!profile.qqId().equals(qqId)) {
            throw new IllegalArgumentException("qqId 必须等于 profile.qqId");
        }
    }

    public boolean isPlayer() {
        return actor.role() == io.mosire.iibot.taskflow.ActorRole.PLAYER;
    }

    public boolean isOperator() {
        return switch (actor.role()) {
            case DIRECTOR, GM, SUPERVISOR -> true;
            case PLAYER, SECRETARY -> false;
        };
    }
}
