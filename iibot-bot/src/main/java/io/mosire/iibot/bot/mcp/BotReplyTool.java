package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.reply.BotReplyException;
import io.mosire.iibot.bot.reply.BotReplyResult;
import io.mosire.iibot.bot.reply.BotReplyService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 按 thread 回复，目标由宿主反解。 */
public final class BotReplyTool implements AgentTool {

    public static final String NAME = "qq_conversation_send";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址，如 qq_group/bot-1/955432692"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source"),
                    "text", Map.of("type", "string", "minLength", 1, "maxLength", 4000),
                    "replyToMessageId", Map.of("type", "string"),
                    "mentionSender", Map.of("type", "boolean",
                            "description", "群聊中回复时 @ 被回复消息的发送者"),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1,
                            "description", "可选；仅在调用方需要重试时显式传入。省略时服务端生成 UUID。")),
            "required", List.of("text"),
            "additionalProperties", false);

    private final BotReplyService service;

    public BotReplyTool(BotReplyService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "回复一个来源 thread；目标由宿主从 threadId 解析，不接受任意 QQ/群号。"
                + "mentionSender=true 时可 @ 被回复消息的发送者。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String idempotencyKey = context.arguments().containsKey("idempotencyKey")
                    ? BotToolSupport.requiredText(
                            context.arguments(), "idempotencyKey", 200)
                    : UUID.randomUUID().toString();
            String threadId = BotToolSupport.threadKey(context.arguments()).threadId();
            BotReplyResult result = service.reply(
                    threadId,
                    BotToolSupport.requiredText(context.arguments(), "text", 4000),
                    BotToolSupport.optionalText(context.arguments(), "replyToMessageId", 256),
                    BotToolSupport.optionalBoolean(context.arguments(), "mentionSender", false),
                    idempotencyKey);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageKey", result.message().messageKey());
            data.put("source", result.message().source().sourceAddress().canonical());
            data.put("threadId", result.message().source().threadId());
            data.put("idempotencyKey", result.message().idempotencyKey());
            data.put("deliveryState", result.deliveryState().name());
            data.put("messageIds", result.messageIds());
            data.put("errorCode", result.errorCode());
            data.put("alreadyAttempted", result.alreadyAttempted());
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (BotReplyException failure) {
            return BotToolSupport.failure(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("REPLY_UNAVAILABLE",
                    "回复暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("source", "threadId", "replyToMessageId",
                "idempotencyKey")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
