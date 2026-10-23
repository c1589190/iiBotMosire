package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次经 {@code ToolCallAuthorizer} 实际执行的工具调用审计快照。
 *
 * <p>该类型只用于宿主观察和测试断言，不会进入秘书/GM 的 system prompt。参数是模型给出的
 * JSON 对象快照；结果只含稳定的工具回执文本与错误码，不承载 QQ 凭据、API key 或未过滤的
 * 世界事实。</p>
 *
 * @param toolCallId 模型给出的工具调用 ID
 * @param toolName   工具名
 * @param arguments  模型参数（不可变快照）
 * @param result     授权器返回的结果
 */
public record ToolInvocation(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        ToolResult result) {

    public ToolInvocation {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        Objects.requireNonNull(result, "result");
    }

    /** 是否成功执行（权限拒绝、工具错误均为 false）。 */
    public boolean success() {
        return result.success();
    }
}
