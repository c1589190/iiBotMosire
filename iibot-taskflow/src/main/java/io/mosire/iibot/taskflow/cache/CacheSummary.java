package io.mosire.iibot.taskflow.cache;

import java.util.Objects;

/**
 * 一个秘书会话 cache 的只读摘要。
 *
 * @param cacheId     宿主生成的稳定 cache ID
 * @param playerId    cache owner
 * @param title       展示标题
 * @param source      来源：APP 或后续预留的 NAPCAT_IMPORT
 * @param createdAt   创建时间（ISO-8601 字符串）
 * @param lastUsedAt  最近使用时间（ISO-8601 字符串）
 * @param messageCount 当前消息条数
 * @param active      是否为该玩家当前 active cache
 */
public record CacheSummary(
        String cacheId,
        String playerId,
        String title,
        String source,
        String createdAt,
        String lastUsedAt,
        int messageCount,
        boolean active) {

    public CacheSummary {
        cacheId = requireText(cacheId, "cacheId");
        playerId = requireText(playerId, "playerId");
        title = title == null || title.isBlank() ? "未命名对话" : title;
        source = source == null || source.isBlank() ? "APP" : source;
        createdAt = createdAt == null ? "" : createdAt;
        lastUsedAt = lastUsedAt == null ? "" : lastUsedAt;
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount 不能为负");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
