package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.weight.SourceWeightPolicy;
import io.mosire.iibot.bot.weight.SourceWeightService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 列出来源权重策略。 */
public final class BotSourceWeightListTool implements AgentTool {

    public static final String NAME = "qq_source_weight_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 1000,
                            "description", "最多返回来源数，默认 50")),
            "additionalProperties", false);

    private final SourceWeightService service;

    public BotSourceWeightListTool(SourceWeightService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "列出 QQ 来源权重、通知阈值、信号权重和是否已完成首次引导。";
    }
    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }
    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }
    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (limit < 1 || limit > 1000) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..1000");
            }
            List<Map<String, Object>> rows = service.listPolicies(limit).stream()
                    .map(BotSourceWeightListTool::policyMap).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", rows.size());
            data.put("sources", rows);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("SOURCE_WEIGHT_UNAVAILABLE",
                    "来源权重读取暂不可用（内部原因已脱敏）");
        }
    }

    static Map<String, Object> policyMap(SourceWeightPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", BotToolSupport.sourceOf(policy.threadId()));
        value.put("threadId", policy.threadId());
        value.put("chatType", policy.chatType().wire());
        value.put("targetId", policy.targetId());
        value.put("sourceWeight", policy.sourceWeight());
        value.put("notifyThreshold", policy.notifyThreshold());
        value.put("signalWeights", policy.signalWeights().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().name(), Map.Entry::getValue)));
        value.put("keywordWeights", policy.keywordWeights());
        value.put("maxMessages", policy.maxMessages());
        value.put("quietSeconds", policy.quietMillis() / 1000L);
        value.put("cooldownSeconds", policy.cooldownMillis() / 1000L);
        value.put("bootstrapDelivered", policy.bootstrapDelivered());
        value.put("revision", policy.revision());
        value.put("updatedAt", policy.updatedAt().toString());
        return value;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        return context.arguments().containsKey("limit")
                ? Map.of("limit", context.arguments().get("limit")) : Map.of();
    }
}
