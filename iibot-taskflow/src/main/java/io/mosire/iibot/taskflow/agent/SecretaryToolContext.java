package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnView;

import java.time.Duration;
import java.util.Objects;

/**
 * 秘书工具唯一认的身份/config 注入点。
 *
 * <p>模型参数里永远不出现 playerId/turnId/actor。宿主在构建 {@link ToolContext} 时通过
 * {@code config} 绑定这些事实；工具只读 config，不读模型自报身份。该类集中做校验，避免每个
 * 工具重复解释绑定键。</p>
 */
public final class SecretaryToolContext {

    /** 当前已认证玩家 ID。 */
    public static final String PLAYER_ID = "playerId";
    /** 已认证 {@link Actor} 对象；必须是 PLAYER 且 actorId == playerId。 */
    public static final String ACTOR = "actor";
    /** 当前私聊会话绑定的回合 ID。 */
    public static final String TURN_ID = "turnId";
    /** 草稿有效期，类型 {@link Duration}。 */
    public static final String DRAFT_TTL = "draftTtl";

    private SecretaryToolContext() {
    }

    /** 取出并校验玩家绑定。 */
    public static BoundPlayer requirePlayer(ToolContext context) {
        Objects.requireNonNull(context, "context");
        Object actorValue = context.config().get(ACTOR);
        Object playerIdValue = context.config().get(PLAYER_ID);
        if (!(actorValue instanceof Actor actor)) {
            throw new IllegalArgumentException("宿主未绑定合法 actor");
        }
        if (!(playerIdValue instanceof String playerId) || playerId.isBlank()) {
            throw new IllegalArgumentException("宿主未绑定合法 playerId");
        }
        if (actor.role() != ActorRole.PLAYER || !actor.actorId().equals(playerId)) {
            throw new IllegalArgumentException("绑定的 actor 与 playerId 不一致或不是玩家");
        }
        Object turnIdValue = context.config().get(TURN_ID);
        if (!(turnIdValue instanceof String turnId) || turnId.isBlank()) {
            throw new IllegalArgumentException("宿主未绑定合法 turnId");
        }
        return new BoundPlayer(playerId, actor, turnId);
    }

    /** 取草稿有效期；缺省 15 分钟，非法值响亮失败。 */
    public static Duration draftTtl(ToolContext context) {
        Object value = context.config().get(DRAFT_TTL);
        if (value == null) {
            return Duration.ofMinutes(15);
        }
        if (!(value instanceof Duration duration) || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("draftTtl 必须是正 Duration");
        }
        return duration;
    }

    /** 要求当前回合存在且与宿主绑定回合一致。 */
    public static TurnView requireBoundTurn(TaskflowEngine engine, BoundPlayer bound) {
        TurnView turn = engine.turnView().orElseThrow(
                () -> new IllegalArgumentException("当前没有进行中的回合"));
        if (!turn.turnId().equals(bound.turnId())) {
            throw new IllegalArgumentException("私聊会话绑定的回合已变化，请重新开始回合内操作");
        }
        return turn;
    }

    /** 已校验的玩家绑定。 */
    public record BoundPlayer(String playerId, Actor actor, String turnId) {
        public BoundPlayer {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(turnId, "turnId");
        }
    }
}
