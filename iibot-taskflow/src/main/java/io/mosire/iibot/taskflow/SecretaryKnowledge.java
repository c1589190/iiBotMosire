package io.mosire.iibot.taskflow;

import java.util.List;
import java.util.Objects;

/**
 * 秘书可见的知识面。
 *
 * <p>只包含该玩家尚未过期的 brief 和公共世界摘要；不会返回其他玩家的私密事实。
 * 这是“可见性在进入模型前过滤”的领域端口，而不是提示词约束。</p>
 */
public record SecretaryKnowledge(
        String playerId,
        List<InfoBrief> briefs,
        List<WorldFact> publicFacts) {

    public SecretaryKnowledge {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        Objects.requireNonNull(briefs, "briefs");
        Objects.requireNonNull(publicFacts, "publicFacts");
        briefs = List.copyOf(briefs);
        publicFacts = List.copyOf(publicFacts);
    }

    public boolean isEmpty() {
        return briefs.isEmpty() && publicFacts.isEmpty();
    }
}
