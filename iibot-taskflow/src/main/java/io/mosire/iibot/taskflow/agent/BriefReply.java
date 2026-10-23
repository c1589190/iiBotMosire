package io.mosire.iibot.taskflow.agent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 已由引擎签发、可交给工具回灌给模型的 brief 回执。
 *
 * @param requestId     引擎 brief 请求 ID
 * @param content       GM 过滤后的内容
 * @param sourceFactIds 引擎校验通过的来源事实 ID
 */
public record BriefReply(String requestId, String content, Set<String> sourceFactIds) {

    public BriefReply {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        Objects.requireNonNull(sourceFactIds, "sourceFactIds");
        sourceFactIds = Set.copyOf(new LinkedHashSet<>(sourceFactIds));
    }
}
