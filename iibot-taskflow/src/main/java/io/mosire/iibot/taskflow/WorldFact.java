package io.mosire.iibot.taskflow;

import java.util.Objects;

/**
 * 带版本与可见性的世界事实。
 *
 * <p>事实进入秘书/GM 上下文前必须经过 {@link Visibility} 过滤。worldVersion 用于
 * 保证推演提案基于同一个冻结世界版本，避免过期提案悄悄落盘。</p>
 */
public record WorldFact(String factId, long worldVersion, String content, Visibility visibility) {
    public WorldFact {
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
}
