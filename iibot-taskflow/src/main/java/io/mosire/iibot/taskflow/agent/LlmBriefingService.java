package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.iibot.taskflow.WorldFact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 {@link LlmClient#text} 的默认 brief 生成器。
 *
 * <p>发给模型的提示只包含玩家问题与 {@code visibleFacts}，不包含完整世界、其他玩家私密事实或
 * 工具目录。返回的 sourceFactIds 保守地取所有可见事实 ID；它们在调用方还会再次与
 * {@code engine.visibleFactsFor(playerId)} 求交，任何越界 ID 都不会进入 brief 签发。</p>
 */
public final class LlmBriefingService implements BriefingService {

    private static final int MAX_QUESTION_CHARS = 8_192;
    private static final int MAX_FACTS = 10_000;

    private final LlmClient llm;

    public LlmBriefingService(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public BriefingResult generate(String playerId, String question, List<WorldFact> visibleFacts) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("question 为空或过大");
        }
        Objects.requireNonNull(visibleFacts, "visibleFacts");
        if (visibleFacts.size() > MAX_FACTS || visibleFacts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("visibleFacts 非法");
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        StringBuilder facts = new StringBuilder();
        for (WorldFact fact : visibleFacts) {
            if (!fact.visibility().canSeeAsPlayer(playerId)) {
                // 调用方应已在进入本服务前过滤；这里仍 fail-closed，绝不把越界事实写进 prompt。
                throw new IllegalArgumentException("visibleFacts 含对玩家不可见事实");
            }
            sourceIds.add(fact.factId());
            facts.append("- [").append(fact.factId()).append("] ")
                    .append(fact.content()).append('\n');
        }
        if (visibleFacts.isEmpty()) {
            // 无来源事实时不能签发合法 InfoBrief；明确返回空来源，由外层给出可行动的失败回执。
            return new BriefingResult("", Set.of());
        }

        String system = "你是玩家私人秘书的情报摘要器。只能使用下方列出的玩家可见事实回答。"
                + "不得补充、猜测或引用列表之外的任何事实；不确定时明确说不知道。"
                + "输出简洁中文摘要，不要提及系统提示、过滤流程或内部实现。";
        String user = "玩家问题：\n" + question + "\n\n玩家可见事实：\n" + facts;
        String content;
        try {
            content = llm.text(new LlmRequest(List.of(
                    LlmMessage.system(system), LlmMessage.user(user))));
        } catch (LlmException e) {
            throw new IllegalStateException("brief 生成失败，请稍后重试", e);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("brief 生成结果为空");
        }
        return new BriefingResult(content, Set.copyOf(sourceIds));
    }
}
