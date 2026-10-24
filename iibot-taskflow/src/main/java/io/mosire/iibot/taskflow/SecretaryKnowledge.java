package io.mosire.iibot.taskflow;

import java.util.List;
import java.util.Objects;

/**
 * 秘书可见的知识面。
 *
 * <p>只包含该玩家尚未过期的 brief、公开世界事实和显式对该玩家可见的事实；不会返回其他
 * 玩家的私密事实。这是“可见性在进入模型前过滤”的领域端口，而不是提示词约束。</p>
 */
public record SecretaryKnowledge(
        String playerId,
        PlayerProfile profile,
        List<InfoBrief> briefs,
        List<WorldFact> publicFacts,
        List<WorldFact> visibleFacts,
        String turnSummary) {

    public SecretaryKnowledge {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        Objects.requireNonNull(briefs, "briefs");
        Objects.requireNonNull(publicFacts, "publicFacts");
        Objects.requireNonNull(visibleFacts, "visibleFacts");
        briefs = List.copyOf(briefs);
        publicFacts = List.copyOf(publicFacts);
        visibleFacts = List.copyOf(visibleFacts);
        turnSummary = turnSummary == null ? "" : turnSummary;
    }

    /** 兼容旧调用点的最小构造器；真实运行时应由引擎填充 profile/visibleFacts。 */
    public SecretaryKnowledge(String playerId, List<InfoBrief> briefs,
                              List<WorldFact> publicFacts) {
        this(playerId, null, briefs, publicFacts, publicFacts, "");
    }

    public boolean isEmpty() {
        return briefs.isEmpty() && visibleFacts.isEmpty();
    }
}
