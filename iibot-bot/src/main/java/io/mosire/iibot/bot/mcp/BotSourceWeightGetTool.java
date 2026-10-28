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

/** 读取一个来源的权重策略。 */
public final class BotSourceWeightGetTool implements AgentTool {

    public static final String NAME = "qq_source_weight_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址，如 qq_group/bot-1/955432692"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source")),
            "required", java.util.List.of(),
            "additionalProperties", false);

    private final SourceWeightService service;

    public BotSourceWeightGetTool(SourceWeightService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return "读取一个 QQ 来源的权重策略。"; }
    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }
    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }
    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.threadKey(context.arguments()).threadId();
            SourceWeightPolicy policy = service.findPolicy(threadId).orElseThrow(() ->
                    new BotToolSupport.InvalidArgumentsException("来源策略不存在"));
            Map<String, Object> data = new LinkedHashMap<>(
                    BotSourceWeightListTool.policyMap(policy));
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("SOURCE_WEIGHT_UNAVAILABLE",
                    "来源权重读取暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("source", "threadId")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
