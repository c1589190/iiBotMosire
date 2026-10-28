package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.ops.OpsLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 给 Bot MCP 工具调用加统一运维日志。
 *
 * <p>只记录工具名、结果、稳定错误码、耗时和参数 key；不记录参数值，避免聊天正文、
 * 备注、keyword 或凭据进入日志。日志异常不改变原工具返回或抛出的异常。</p>
 */
final class BotToolCallLoggingTool implements AgentTool {

    private final AgentTool delegate;

    BotToolCallLoggingTool(AgentTool delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return delegate.jsonSchema();
    }

    @Override
    public ToolSpec spec() {
        return delegate.spec();
    }

    @Override
    public Set<String> roles() {
        return delegate.roles();
    }

    @Override
    public ToolGate gate(ToolContext context) {
        return delegate.gate(context);
    }

    @Override
    public ResourceManifest resources() {
        return delegate.resources();
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return delegate.ledgerArgs(context);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        long started = System.nanoTime();
        String argKeys = argumentKeys(context);
        try {
            ToolResult result = delegate.execute(context);
            long elapsedMs = elapsedMillis(started);
            if (result != null && result.success()) {
                OpsLog.info("event=mcp_tool_call tool={} outcome=ok elapsedMs={} argKeys={}",
                        name(), elapsedMs, argKeys);
            } else {
                String code = result == null ? "NULL_RESULT" : result.code();
                OpsLog.warn("event=mcp_tool_call tool={} outcome=error code={} "
                                + "elapsedMs={} argKeys={}",
                        name(), safeCode(code), elapsedMs, argKeys);
            }
            return result;
        } catch (RuntimeException failure) {
            OpsLog.error("event=mcp_tool_call tool={} outcome=exception "
                            + "errorType={} elapsedMs={} argKeys={}",
                    name(), failure.getClass().getSimpleName(), elapsedMillis(started), argKeys);
            throw failure;
        }
    }

    private static String argumentKeys(ToolContext context) {
        if (context == null || context.arguments() == null || context.arguments().isEmpty()) {
            return "[]";
        }
        List<String> keys = new ArrayList<>(context.arguments().keySet());
        Collections.sort(keys);
        return keys.toString();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String safeCode(String code) {
        if (code == null || code.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = code.trim().replaceAll("[\\r\\n\\t]", "_");
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }
}
