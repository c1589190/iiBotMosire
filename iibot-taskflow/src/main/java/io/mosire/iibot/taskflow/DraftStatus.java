package io.mosire.iibot.taskflow;

/** 决策草稿状态；只有 DRAFT 能被确认，确认后不可再修改。 */
public enum DraftStatus {
    DRAFT,
    CONFIRMED,
    SUPERSEDED,
    EXPIRED
}
