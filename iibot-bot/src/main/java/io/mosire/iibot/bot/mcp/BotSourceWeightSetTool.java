package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.weight.SourceWeightConflictException;
import io.mosire.iibot.bot.weight.SourceWeightPolicy;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.bot.weight.WeightSignal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 调整来源权重；首次接触后 Agent 用它写规则。 */
public final class BotSourceWeightSetTool implements AgentTool {

    public static final String NAME = "qq_source_weight_set";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址，如 qq_group/bot-1/955432692"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source"),
                    "sourceWeight", Map.of("type", "integer", "minimum", -1000,
                            "maximum", 1000),
                    "notifyThreshold", Map.of("type", "integer", "minimum", -1000,
                            "maximum", 1000,
                            "description", "排序参考阈值；当前全唤醒策略下不 gate 唤醒"),
                    "signalWeights", Map.of("type", "object",
                            "description", "结构信号到整数的映射；键为 DM/MENTION/REPLY_TO_BOT/NAME/COMMAND"),
                    "keywordWeights", Map.of("type", "object",
                            "description", "关键词到整数的映射，支持负权重"),
                    "maxMessages", Map.of("type", "integer", "minimum", 1, "maximum", 1000),
                    "quietSeconds", Map.of("type", "integer", "minimum", 0, "maximum", 86400,
                            "description", "保留字段；当前全唤醒下不 gate 唤醒"),
                    "cooldownSeconds", Map.of("type", "integer", "minimum", 0, "maximum", 86400,
                            "description", "保留字段；当前全唤醒下不 gate 唤醒"),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0)),
            "required", List.of("sourceWeight"),
            "additionalProperties", false);

    private final SourceWeightService service;
    private final InboxDigestService digests;

    public BotSourceWeightSetTool(SourceWeightService service) {
        this(service, null);
    }

    public BotSourceWeightSetTool(SourceWeightService service,
                                  InboxDigestService digests) {
        this.service = Objects.requireNonNull(service, "service");
        this.digests = digests;
    }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "设置来源基础权重、排序阈值、@/回复/名称/命令结构权重和关键词权重；"
                + "权重只用于 Inbox priority 排序/诊断，当前所有入站消息仍全唤醒。";
    }
    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }
    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }
    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.threadKey(context.arguments()).threadId();
            Integer sourceWeight = optionalInteger(context.arguments(), "sourceWeight");
            if (sourceWeight == null) {
                throw new BotToolSupport.InvalidArgumentsException("sourceWeight 必须提供");
            }
            Integer threshold = optionalInteger(context.arguments(), "notifyThreshold");
            Map<WeightSignal, Integer> signals = signalWeights(
                    context.arguments().get("signalWeights"));
            Map<String, Integer> keywords = keywordWeights(
                    context.arguments().get("keywordWeights"));
            Integer maxMessages = optionalInteger(context.arguments(), "maxMessages");
            Long quietSeconds = optionalLong(context.arguments(), "quietSeconds");
            Long cooldownSeconds = optionalLong(context.arguments(), "cooldownSeconds");
            Long expectedRevision = optionalLong(context.arguments(), "expectedRevision");
            int previousSourceWeight = service.findPolicy(threadId)
                    .map(SourceWeightPolicy::sourceWeight).orElse(0);
            SourceWeightPolicy policy = service.setPolicy(
                    threadId, sourceWeight, threshold, signals, keywords,
                    maxMessages,
                    quietSeconds == null ? null : quietSeconds * 1000L,
                    cooldownSeconds == null ? null : cooldownSeconds * 1000L,
                    expectedRevision);
            if (digests != null && policy.sourceWeight() != previousSourceWeight) {
                digests.adjustActivePriority(threadId,
                        policy.sourceWeight() - previousSourceWeight);
            }
            Map<String, Object> data = new LinkedHashMap<>(
                    BotSourceWeightListTool.policyMap(policy));
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (SourceWeightConflictException conflict) {
            return BotToolSupport.failure("SOURCE_WEIGHT_REVISION_CONFLICT",
                    "来源权重策略 revision 已变化",
                    Map.of("expectedRevision", conflict.expectedRevision(),
                            "actualRevision", conflict.actualRevision()));
        } catch (IllegalArgumentException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("SOURCE_WEIGHT_UNAVAILABLE",
                    "来源权重写入暂不可用（内部原因已脱敏）");
        }
    }

    private static Integer optionalInteger(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new BotToolSupport.InvalidArgumentsException(name + " 必须是整数");
        }
        int result = number.intValue();
        if (result < -1000 || result > 1000) {
            throw new BotToolSupport.InvalidArgumentsException(name + " 必须在 -1000..1000");
        }
        return result;
    }

    private static Long optionalLong(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new BotToolSupport.InvalidArgumentsException(name + " 必须是整数");
        }
        long result = number.longValue();
        if (result < 0L || result > 86_400L) {
            throw new BotToolSupport.InvalidArgumentsException(name + " 必须在 0..86400");
        }
        return result;
    }

    private static Map<WeightSignal, Integer> signalWeights(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new BotToolSupport.InvalidArgumentsException("signalWeights 必须是对象");
        }
        Map<WeightSignal, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new BotToolSupport.InvalidArgumentsException("signalWeights 键必须是字符串");
            }
            WeightSignal signal;
            try {
                signal = WeightSignal.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "未知 signal: " + key);
            }
            result.put(signal, weight(entry.getValue(), "signalWeights." + key));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> keywordWeights(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new BotToolSupport.InvalidArgumentsException("keywordWeights 必须是对象");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "keywordWeights 键不能为空");
            }
            result.put(key.trim(), weight(entry.getValue(), "keywordWeights." + key));
        }
        return Map.copyOf(result);
    }

    private static int weight(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new BotToolSupport.InvalidArgumentsException(name + " 必须是整数");
        }
        int result = number.intValue();
        if (result < -1000 || result > 1000) {
            throw new BotToolSupport.InvalidArgumentsException(name + " 必须在 -1000..1000");
        }
        return result;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("source", "threadId", "sourceWeight", "notifyThreshold",
                "signalWeights", "keywordWeights", "maxMessages", "quietSeconds",
                "cooldownSeconds", "expectedRevision")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
