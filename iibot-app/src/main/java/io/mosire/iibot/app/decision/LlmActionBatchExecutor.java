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
    static final String REJECT_OUTPUT_INVALID = "推演失败：模型未返回严格结构化结果（未记为 COMMITTED）";

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
            AdjudicationOutput output;
            try {
                output = AdjudicationOutput.parse(result);
                output.validatePlayerScope(new java.util.LinkedHashSet<>(correlated.playerIds()));
            } catch (RuntimeException invalid) {
                return correlated.withStatus(ActionBatchStatus.REJECTED, REJECT_OUTPUT_INVALID);
            }
            String canonical = boundResult(output.canonicalJson());
            if (output.isNoOp()) {
                return correlated.withStatus(ActionBatchStatus.NO_OP, canonical);
            }
            return correlated.committed(canonical);
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
                + "\n只输出一个严格 JSON object，不要输出 Markdown、解释、前后缀或推理过程。\n"
                + "JSON schema: {\"publicSummary\":\"可公开的世界级结果\","
                + "\"privateOutcomes\":{\"<批次内 playerId>\":\"该玩家可见结果\"},"
                + "\"worldChanged\":true,\"noOpReason\":\"\"}\n"
                + "规则：publicSummary 不得包含任何玩家私密行动或 playerId；"
                + "privateOutcomes 只能使用本批次 playerIds；没有公共内容时 publicSummary 用空串；"
                + "如果没有产生任何世界变化，worldChanged=false 且 noOpReason 必须写明原因；"
                + "玩家可见文本必须是自然中文，不得包含 ID、状态枚举、文件路径、Markdown 或工具信息。"
                + "不要调用工具。";
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
        return "你是历史政治推演的中央推演 GM。只基于给定世界版本、世界事实和本批次行动做一次"
                + "只读推演。你没有工具，不得修改世界，不得把未提供的行动或事实当作已发生。"
                + "只输出严格 JSON object，不输出推理过程。publicSummary/privateOutcomes 必须是"
                + "玩家可读的世界内中文：不得出现 draftId/ticketId/postId/MCP/schema/路径/"
                + "状态枚举/Markdown/代码块。公共文本不得泄露任何玩家私密信息。"
                + "没有造成任何世界变化时 worldChanged=false 并写清 noOpReason。";
    }

    private static String boundResult(String result) {
        String trimmed = result.trim();
        if (trimmed.length() <= MAX_RESULT_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_RESULT_CHARS) + "\n…[推演结果已截断]";
    }
}
