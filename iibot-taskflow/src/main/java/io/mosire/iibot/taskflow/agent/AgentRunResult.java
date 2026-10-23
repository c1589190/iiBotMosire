package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.runtime.TurnResult;

import java.util.List;
import java.util.Objects;

/**
 * 一次 {@link AgentRunner#runTurn} 的结果。
 *
 * @param turnResult     AgentLib 的终止原因、轮数与最终文本
 * @param toolInvocations 按模型给出顺序记录的实际分派结果
 */
public record AgentRunResult(TurnResult turnResult, List<ToolInvocation> toolInvocations) {

    public AgentRunResult {
        Objects.requireNonNull(turnResult, "turnResult");
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
    }

    /** 最终文本；没有文本时为空串（绝不返回 null）。 */
    public String text() {
        return turnResult.text() == null ? "" : turnResult.text();
    }

    /** 是否被轮数/时间/工具调用上限等策略提前终止。 */
    public boolean stoppedByPolicy() {
        return switch (turnResult.stopReason()) {
            case TURN_LIMIT, TOOL_CALL_LIMIT, TIME_BUDGET, QUOTA, CANCELLED -> true;
            case FINISHED, LLM_ERROR -> false;
        };
    }
}
