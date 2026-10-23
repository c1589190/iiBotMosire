package io.mosire.iibot.taskflow.snapshot;

import io.mosire.iibot.taskflow.WorldFact;

import java.util.Objects;

/** 世界事实的纯数据快照，可见性以 {@link VisibilitySnapshot} 表示。 */
public record FactSnapshot(
        String factId,
        long worldVersion,
        String content,
        VisibilitySnapshot visibility) {

    public FactSnapshot {
        if (factId == null || factId.isBlank()) {
            throw new IllegalArgumentException("factId 不能为空");
        }
        if (worldVersion < 0) {
            throw new IllegalArgumentException("worldVersion 不能为负");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        Objects.requireNonNull(visibility, "visibility");
    }

    public static FactSnapshot fromDomain(WorldFact fact) {
        Objects.requireNonNull(fact, "fact");
        return new FactSnapshot(fact.factId(), fact.worldVersion(), fact.content(),
                VisibilitySnapshot.fromDomain(fact.visibility()));
    }

    public WorldFact toDomain() {
        return new WorldFact(factId, worldVersion, content, visibility.toDomain());
    }
}
