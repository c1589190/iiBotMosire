package io.mosire.iibot.taskflow.snapshot;

/** 快照中使用的稳定可见性种类，避免持久化层直接依赖领域 sealed interface。 */
public enum VisibilityKind {
    PUBLIC,
    DIRECTOR_ONLY,
    PLAYERS
}
