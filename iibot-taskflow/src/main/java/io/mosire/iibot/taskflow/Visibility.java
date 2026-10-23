package io.mosire.iibot.taskflow;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 世界事实的可见性边界。
 *
 * <p>可见性必须在事实进入秘书/GM 上下文之前完成过滤，不能依赖提示词保密。
 * 该类型只做纯领域判断，不包含任何聊天平台或 LLM 类型。</p>
 */
public sealed interface Visibility permits Visibility.PublicVisibility, Visibility.PlayerSetVisibility, Visibility.DirectorOnlyVisibility {

    /** 所有人可见。 */
    PublicVisibility PUBLIC = new PublicVisibility();

    /** 仅推演可见的单例。 */
    DirectorOnlyVisibility DIRECTOR_ONLY = DirectorOnlyVisibility.INSTANCE;

    /** 仅指定玩家可见。 */
    static Visibility forPlayers(Set<String> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个可见玩家");
        }
        return new PlayerSetVisibility(playerIds);
    }

    /** 仅推演角色可见。 */
    static DirectorOnlyVisibility directorOnly() {
        return DIRECTOR_ONLY;
    }

    /**
     * 判断某身份能否看到该事实。
     *
     * @param playerId 观察者 ID，可为空；为空时永远不会命中玩家集合
     * @param role     观察者角色；DIRECTOR_ONLY 只对 DIRECTOR 放行
     */
    boolean canSee(String playerId, ActorRole role);

    /** 是否对指定玩家可见（秘书只能以玩家身份取知识）。 */
    default boolean canSeeAsPlayer(String playerId) {
        return canSee(playerId, ActorRole.PLAYER);
    }

    record PublicVisibility() implements Visibility {
        @Override
        public boolean canSee(String playerId, ActorRole role) {
            return true;
        }
    }

    record PlayerSetVisibility(Set<String> playerIds) implements Visibility {
        public PlayerSetVisibility {
            Objects.requireNonNull(playerIds, "playerIds");
            if (playerIds.isEmpty()) {
                throw new IllegalArgumentException("至少需要一个可见玩家");
            }
            playerIds = Set.copyOf(new LinkedHashSet<>(playerIds));
        }

        @Override
        public boolean canSee(String playerId, ActorRole role) {
            return playerId != null && playerIds.contains(playerId);
        }
    }

    record DirectorOnlyVisibility() implements Visibility {
        private static final DirectorOnlyVisibility INSTANCE = new DirectorOnlyVisibility();

        @Override
        public boolean canSee(String playerId, ActorRole role) {
            return role == ActorRole.DIRECTOR;
        }
    }
}
