package io.mosire.iibot.taskflow.snapshot;

import io.mosire.iibot.taskflow.Visibility;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 纯数据可见性快照。
 *
 * <p>PUBLIC/DIRECTOR_ONLY 的 playerIds 必须为空；PLAYERS 必须至少包含一个非空玩家 ID。
 * 该类型只依赖字符串与枚举，可被 Jackson 默认反序列化后再转换为领域
 * {@link Visibility}。</p>
 */
public record VisibilitySnapshot(VisibilityKind kind, Set<String> playerIds) {

    public VisibilitySnapshot {
        Objects.requireNonNull(kind, "kind");
        playerIds = playerIds == null ? Set.of() : immutableSet(playerIds);
        if (playerIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("playerIds 不能含空值");
        }
        switch (kind) {
            case PUBLIC, DIRECTOR_ONLY -> {
                if (!playerIds.isEmpty()) {
                    throw new IllegalArgumentException(kind + " 不能携带 playerIds");
                }
            }
            case PLAYERS -> {
                if (playerIds.isEmpty()) {
                    throw new IllegalArgumentException("PLAYERS 可见性至少需要一个玩家");
                }
            }
        }
    }

    public static VisibilitySnapshot fromDomain(Visibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        if (visibility instanceof Visibility.PublicVisibility) {
            return new VisibilitySnapshot(VisibilityKind.PUBLIC, Set.of());
        }
        if (visibility instanceof Visibility.DirectorOnlyVisibility) {
            return new VisibilitySnapshot(VisibilityKind.DIRECTOR_ONLY, Set.of());
        }
        if (visibility instanceof Visibility.PlayerSetVisibility playersVisibility) {
            return new VisibilitySnapshot(VisibilityKind.PLAYERS, playersVisibility.playerIds());
        }
        throw new IllegalArgumentException("未知可见性类型: " + visibility.getClass().getName());
    }

    public Visibility toDomain() {
        return switch (kind) {
            case PUBLIC -> Visibility.PUBLIC;
            case DIRECTOR_ONLY -> Visibility.DIRECTOR_ONLY;
            case PLAYERS -> Visibility.forPlayers(playerIds);
        };
    }

    private static Set<String> immutableSet(Set<String> values) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
