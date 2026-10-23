package io.mosire.iibot.taskflow.agent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 brief 生成结果。
 *
 * @param content       经审查、可交给玩家的 brief 内容
 * @param sourceFactIds 实际引用的来源事实 ID；必须是输入 visibleFacts 的子集
 */
public record BriefingResult(String content, Set<String> sourceFactIds) {

    public BriefingResult {
        content = content == null ? "" : content;
        Objects.requireNonNull(sourceFactIds, "sourceFactIds");
        sourceFactIds = Set.copyOf(new LinkedHashSet<>(sourceFactIds));
    }
}
