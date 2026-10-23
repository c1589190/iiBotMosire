package io.mosire.iibot.taskflow.agent;

import io.mosire.iibot.taskflow.WorldFact;

import java.util.List;

/**
 * GM 过滤前的 brief 生成端口。
 *
 * <p>输入只有调用方已经按玩家可见性过滤过的 {@code visibleFacts}。实现方不得自行访问完整
 * 世界或其他玩家事实；输出 sourceFactIds 还必须限定在输入事实 ID 集合内。</p>
 */
@FunctionalInterface
public interface BriefingService {

    /**
     * 为指定玩家生成 brief。
     *
     * @param playerId    当前玩家 ID（只用于生成约束，不应授予实现更多世界访问）
     * @param question    玩家问题
     * @param visibleFacts 已经过领域过滤的玩家可见事实
     * @return brief 内容与来源事实 ID
     */
    BriefingResult generate(String playerId, String question, List<WorldFact> visibleFacts);
}
