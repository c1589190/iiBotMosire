package io.mosire.iibot.app.decision;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchExecutor;
import io.mosire.iibot.taskflow.decision.ActionBatchStatus;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 宿主侧行动批次执行器：把一个裁定包里的已批自然语言行动一次性交给中央推演 LLM。
 *
 * <p>本类刻意不装配任何工具，也不直接修改世界：LLM 只根据调用时刻的完整世界事实与批次
 * 行动生成可读的推演结果文本，是否落盘仍由宿主决定。运行时快照（worldVersion）会写入
 * prompt，便于回执对账。失败、空结果或输入超出上下文预算时返回
 * {@link ActionBatchStatus#REJECTED}，绝不伪造 COMMITTED。</p>
 *
 * <p><b>敏感边界</b>：行动正文只进入模型上下文，不进入日志或异常消息。异常在边界处被
 * 收敛成稳定摘要。prompt 与输出均有硬上限，避免单个行动正文或世界事实无限放大。</p>
 */
public final class LlmActionBatchExecutor implements ActionBatchExecutor {

    /** prompt 字符上限；留出供应商 token 化与消息封装的余量。 */
    static final int MAX_PROMPT_CHARS = 48_000;
    /** 世界事实段落上限。 */
    static final int MAX_FACTS_CHARS = 24_000;
    /** 单条世界事实正文上限。 */
    static final int MAX_FACT_CHARS = 2_000;
    /** 模型输出 token 上限。 */
    static final int MAX_OUTPUT_TOKENS = 2_048;
    /** 落库结果字符上限，防止异常供应商返回超大文本。 */
    static final int MAX_RESULT_CHARS = 12_000;

    static final String REJECT_INPUT_TOO_LARGE = "批次输入超过上下文上限，未调用模型";
    static final String REJECT_LLM_FAILED = "推演失败：模型调用未返回可用结果（内部原因已脱敏）";
    static final String REJECT_EMPTY_RESULT = "推演失败：模型返回空结果（未记为 COMMITTED）";

    private final TaskflowEngine engine;
    private final LlmClient adjudicatorLlm;
    private final Clock clock;

    public LlmActionBatchExecutor(TaskflowEngine engine, LlmClient adjudicatorLlm,
                                  Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.adjudicatorLlm = Objects.requireNonNull(adjudicatorLlm, "adjudicatorLlm");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ActionBatch execute(ActionBatch batch) {
        Objects.requireNonNull(batch, "batch");
        // 只绑定执行时仍未终态的 engine 当前回合；无当前回合或已 PUBLISHED/CANCELLED 写 null。
        String turnId = engine.turnView()
                .filter(turn -> !turn.state().isTerminal())
                .map(TurnView::turnId)
                .orElse(null);
        ActionBatch correlated = batch.withTurnId(turnId);
        String prompt;
        try {
            prompt = buildPrompt(correlated);
        } catch (RuntimeException failure) {
            return correlated.withStatus(ActionBatchStatus.REJECTED, REJECT_LLM_FAILED);
        }
        if (prompt == null) {
            return correlated.withStatus(ActionBatchStatus.REJECTED, REJECT_INPUT_TOO_LARGE);
        }

        try {
            LlmRequest request = new LlmRequest(List.of(
                    LlmMessage.system(systemPrompt()),
                    LlmMessage.user(prompt)))
                    .withMaxTokens(MAX_OUTPUT_TOKENS)
                    .withTemperature(0.1);
            LlmResponse response = adjudicatorLlm.chat(request);
            String result = response.textPart().orElse("");
            if (result.isBlank()) {
                return correlated.withStatus(ActionBatchStatus.REJECTED, REJECT_EMPTY_RESULT);
            }
            return correlated.committed(boundResult(result));
        } catch (RuntimeException failure) {
            // 不记录也不回显异常消息：供应商错误可能包含 prompt/正文/凭据片段。
            return correlated.withStatus(ActionBatchStatus.REJECTED, REJECT_LLM_FAILED);
        }
    }

    /**
     * 构造有界 prompt；超出上限时返回 null，由调用方明确拒绝。
     */
    private String buildPrompt(ActionBatch batch) {
        EngineStatus status = engine.status();
        StringBuilder facts = new StringBuilder();
        int includedFacts = 0;
        for (WorldFact fact : engine.allWorldFacts()) {
            if (facts.length() >= MAX_FACTS_CHARS) {
                break;
            }
            String content = fact.content();
            if (content.length() > MAX_FACT_CHARS) {
                content = content.substring(0, MAX_FACT_CHARS) + "…[事实已截断]";
            }
            String line = "- [" + visibilityLabel(fact.visibility()) + "] " + fact.factId()
                    + ": " + content + "\n";
            int remaining = MAX_FACTS_CHARS - facts.length();
            if (line.length() > remaining) {
                facts.append(line, 0, remaining);
                break;
            }
            facts.append(line);
            includedFacts++;
        }

        StringBuilder actions = new StringBuilder();
        for (int i = 0; i < batch.actionIds().size(); i++) {
            actions.append("#").append(i)
                    .append(" actionId=").append(batch.actionIds().get(i))
                    .append(" playerId=").append(batch.playerIds().get(i))
                    .append("\n行动正文：").append(batch.texts().get(i)).append("\n\n");
        }

        String prompt = "批次生成时间: " + clock.instant() + "\n"
                + "世界版本: " + status.worldVersion() + "\n"
                + "引擎 revision: " + status.engineRevision() + "\n"
                + "行动数量: " + batch.actionIds().size() + "\n"
                + "世界事实（共 " + includedFacts + " 条进入上下文）:\n"
                + facts
                + "\n本批次按包内顺序的行动:\n"
                + actions
                + "\n请一次性推演以上全部行动，输出可直接阅读的多行动结果："
                + "逐条说明结果、玩家可见变化、世界变化与后续钩子；"
                + "不要调用工具，不要编造未提供的事实。";
        return prompt.length() <= MAX_PROMPT_CHARS ? prompt : null;
    }

    private static String visibilityLabel(Visibility visibility) {
        if (visibility instanceof Visibility.PublicVisibility) {
            return "PUBLIC";
        }
        if (visibility instanceof Visibility.DirectorOnlyVisibility) {
            return "DIRECTOR_ONLY";
        }
        return "PLAYER_SET";
    }

    private static String systemPrompt() {
        return "你是 iiBotMosire 的中央推演者。只基于给定世界版本、世界事实和本批次行动进行"
                + "一次只读推演，输出结构化或清晰可读的中文结果。你没有工具，不得修改世界，"
                + "不得把未提供的行动或事实当作已发生。";
    }

    private static String boundResult(String result) {
        String trimmed = result.trim();
        if (trimmed.length() <= MAX_RESULT_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_RESULT_CHARS) + "\n…[推演结果已截断]";
    }
}
