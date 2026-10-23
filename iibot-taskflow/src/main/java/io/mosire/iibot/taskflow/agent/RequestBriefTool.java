package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 秘书工具：请求 GM 过滤后的 brief。
 *
 * <p>问题来自模型参数，但玩家身份、回合与签发逻辑由宿主绑定的会话回调决定。回调内部只把
 * {@code engine.visibleFactsFor(playerId)} 交给 brief 生成器，并再次校验来源 ID 子集后才调用
 * {@code issueBrief}。</p>
 */
public final class RequestBriefTool implements AgentTool {

    public static final String NAME = "request_brief";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "question", Map.of("type", "string",
                            "description", "玩家想向秘书询问的信息问题")),
            "required", List.of("question"),
            "additionalProperties", false);

    private final BriefRequester requester;

    public RequestBriefTool(BriefRequester requester) {
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向 GM 请求当前玩家可见的信息 brief。只返回审查后的内容与来源 ID，"
                + "不会暴露其他玩家私密事实或完整世界。";
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
    public ToolResult execute(ToolContext context) {
        try {
            SecretaryToolContext.requirePlayer(context);
            String question = SecretaryToolSupport.requireString(context.arguments(), "question");
            BriefReply reply = requester.requestBrief(question);
            return ToolResult.ok("线索简报已签发。\nrequestId: " + reply.requestId()
                    + "\nsourceFactIds: " + String.join(", ", reply.sourceFactIds())
                    + "\n内容：\n" + reply.content());
        } catch (BriefingFailedException failure) {
            return ToolResult.error(failure.code(), failure.getMessage());
        } catch (IllegalArgumentException invalid) {
            return SecretaryToolSupport.invalid(invalid.getMessage());
        } catch (RuntimeException unexpected) {
            return SecretaryToolSupport.internal();
        }
    }
}
