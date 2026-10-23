package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 经过 GM 审查并组合后的玩家级知识。
 *
 * <p>sourceFactIds 仅记录来源以便审计；contentHash 用于确认秘书拿到的是同一份内容。
 * 过期 brief 不再进入秘书上下文。</p>
 */
public record InfoBrief(
        Set<String> sourceFactIds,
        String content,
        String playerId,
        String turnId,
        Instant expiresAt,
        String contentHash) {

    public InfoBrief {
        Objects.requireNonNull(sourceFactIds, "sourceFactIds");
        if (sourceFactIds.isEmpty() || sourceFactIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("sourceFactIds 不能为空且不能含空值");
        }
        sourceFactIds = Set.copyOf(new LinkedHashSet<>(sourceFactIds));
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash 不能为空");
        }
    }
}
