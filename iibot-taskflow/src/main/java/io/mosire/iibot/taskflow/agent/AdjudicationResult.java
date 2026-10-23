package io.mosire.iibot.taskflow.agent;

import io.mosire.iibot.taskflow.TurnState;

import java.util.List;
import java.util.Objects;

/**
 * 一次中央推演运行结果。
 *
 * @param committed      引擎是否已进入 COMMITTED
 * @param turnId         被推演的回合
 * @param turnState      运行结束后的实际回合状态
 * @param text           Agent 最终文本（可能为空）
 * @param failureReason  未提交时的明确失败原因；提交成功时为空串
 * @param toolInvocations 经授权器实际执行的工具调用
 */
public record AdjudicationResult(
        boolean committed,
        String turnId,
        TurnState turnState,
        String text,
        String failureReason,
        List<ToolInvocation> toolInvocations) {

    public AdjudicationResult {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        Objects.requireNonNull(turnState, "turnState");
        text = text == null ? "" : text;
        failureReason = failureReason == null ? "" : failureReason;
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        if (committed && !failureReason.isEmpty()) {
            throw new IllegalArgumentException("提交成功时不应带失败原因");
        }
    }
}
