package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.GroupRequestCapability;
import io.mosire.iibot.connect.core.SendReceipt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 处理一条群系统请求：接受或拒绝群邀请、同意或拒绝入群申请。 */
public final class BotGroupRequestDecideTool implements AgentTool {

    public static final String NAME = "qq_group_request_decide";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "requestId", Map.of("type", "integer",
                            "description", "qq_group_request_list 返回的 requestId（通知序列号）"),
                    "approve", Map.of("type", "boolean",
                            "description", "true 接受，false 拒绝"),
                    "reason", Map.of("type", "string",
                            "description", "拒绝理由，可选，最多 200 字")),
            "required", List.of("requestId", "approve"),
            "additionalProperties", false);

    private final Map<String, GroupRequestCapability> capabilities;

    public BotGroupRequestDecideTool(Map<String, GroupRequestCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities 不能为空");
        }
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }

    /** 单账号便捷构造。 */
    public BotGroupRequestDecideTool(String accountId, GroupRequestCapability capability) {
        this(single(accountId, capability));
    }

    private static Map<String, GroupRequestCapability> single(
            String accountId, GroupRequestCapability capability) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(capability, "capability");
        return Map.of(accountId.trim(), capability);
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "处理一条群系统请求：approve=true 接受群邀请或同意入群申请，false 拒绝。"
                + "requestId 来自 qq_group_request_list。结果不确定时不会自动重试。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = BotAccountScope.resolveCapability(capabilities,
                    context.arguments(), BotAccountToolSupport.UNSUPPORTED_GROUP_REQUEST);
            GroupRequestCapability capability = capabilities.get(accountId);
            long requestId = BotToolSupport.optionalLong(context.arguments(), "requestId", 0L);
            if (requestId <= 0L) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "requestId 必须是正的通知序列号");
            }
            boolean approve = BotToolSupport.requiredBoolean(context.arguments(), "approve");
            String reason = BotToolSupport.optionalText(context.arguments(), "reason", 200);
            SendReceipt receipt = BotToolSupport.await(
                    capability.decideGroupRequest(requestId, approve, reason));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("requestId", requestId);
            data.put("decision", approve ? "APPROVE" : "REJECT");
            data.put("state", receipt.state().name());
            data.put("errorCode", receipt.errorCode());
            data.put("detail", receipt.detail());
            if (receipt.state() == SendReceipt.State.UNKNOWN) {
                return BotToolSupport.failure("GROUP_REQUEST_UNKNOWN",
                        "群系统请求处理结果未知，禁止自动重试", data);
            }
            if (!receipt.isSent()) {
                return BotToolSupport.failure(
                        receipt.errorCode() == null ? "GROUP_REQUEST_REJECTED"
                                : receipt.errorCode(),
                        "服务端没有接受这次处理", data);
            }
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("GROUP_REQUEST_UNAVAILABLE",
                    "群系统请求处理暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "requestId", "approve")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
