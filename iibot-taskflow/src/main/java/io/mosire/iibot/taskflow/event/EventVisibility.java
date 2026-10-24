package io.mosire.iibot.taskflow.event;

/**
 * 事件正文的最小可见性等级。
 *
 * <p>写入时确定，读取/投影时必须按此过滤；不得由模型或调用方在读取时临时放宽。
 * 旧事件迁移默认使用最保守的 {@link #GM}。</p>
 */
public enum EventVisibility {
    PUBLIC,
    GM,
    PLAYER,
    SECRETARY
}
