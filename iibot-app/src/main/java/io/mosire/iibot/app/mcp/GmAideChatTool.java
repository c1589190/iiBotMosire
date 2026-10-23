package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.agent.GmAideAgent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 外部 supervisor 与 GM 推演秘书对话的只读 MCP 工具。
 *
 * <p>工具只把入参 message 交给组合根装配好的 {@link GmAideAgent}，返回其整理文本。
 * 不暴露 Agent prompt、内部工具审计、LLM 异常或密钥；工具自身没有写操作。</p>
 */
public final class GmAideChatTool implements AgentTool {

    public static final String NAME = "tf_gm_aide_chat";

    private static final String NAMESPACE = "taskflow";
    private static final String SUPERVISOR_ROLE = "supervisor";
    private static final Set<String> ROLES = Set.of(SUPERVISOR_ROLE);
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(NAMESPACE, ResourcePolicy.DENY);
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "向只读 GM 推演秘书发送一条消息并返回整理结果。",
            "properties", Map.of(
                    "message", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "给 GM 推演秘书的消息；不会写入玩家上下文")),
            "required", List.of("message"),
            "additionalProperties", false);

    private final GmAideAgent gmAideAgent;

    public GmAideChatTool(GmAideAgent gmAideAgent) {
        this.gmAideAgent = Objects.requireNonNull(gmAideAgent, "gmAideAgent");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "与只读 GM 推演秘书对话：可整理全量资料、推演动向并回答 GM，"
                + "不发送 QQ、不写世界、不确认决策，也不返回内部 prompt 或异常。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.DEFAULT;
    }

    @Override
    public Set<String> roles() {
        return ROLES;
    }

    @Override
    public ResourceManifest resources() {
        return RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            context.resources().require(Operation.READ, ResourceId.of(NAMESPACE, NAME));
            Object raw = context.arguments() == null ? null : context.arguments().get("message");
            if (!(raw instanceof String message) || message.isBlank()) {
                return ToolResult.error("INVALID_ARGUMENTS", "参数 message 必须是非空白字符串");
            }
            String summary = gmAideAgent.chat(message);
            return ToolResult.ok(summary);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (RuntimeException failure) {
            // 异常类型/消息可能含 provider 细节；对 MCP 调用者只返回稳定摘要。
            return ToolResult.error("GM_AIDE_FAILED", "GM 推演秘书暂时不可用");
        }
    }
}
