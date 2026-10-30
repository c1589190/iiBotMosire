package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.reply.BotReplyException;
import io.mosire.iibot.bot.reply.BotReplyResult;
import io.mosire.iibot.bot.reply.BotReplyService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 主动私聊任意 QQ；D-031 明确不设白名单。 */
public final class BotQqPrivateSendTool implements AgentTool {

    public static final String NAME = "qq_private_send";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "targetQqId", Map.of("type", "string", "minLength", 1,
                            "description", "目标 QQ 号"),
                    "text", Map.of("type", "string", "minLength", 1, "maxLength", 4000),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1,
                            "description", "可选；省略时服务端生成 UUID")),
            "required", List.of("targetQqId", "text"),
            "additionalProperties", false);

    private final BotReplyService replies;
    private final BotAccountScope accounts;

    public BotQqPrivateSendTool(BotReplyService replies, Collection<String> accountIds) {
        this.replies = Objects.requireNonNull(replies, "replies");
        Objects.requireNonNull(accountIds, "accountIds");
        this.accounts = BotAccountScope.of(accountIds);
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "主动向任意 QQ 发起私聊；先写消息板 OUT/PENDING，再按账号发送并回写三态。"
                + "不设好友白名单；UNKNOWN 不自动重试。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = accounts.resolve(context.arguments());
            String targetQqId = BotToolSupport.requiredText(
                    context.arguments(), "targetQqId", 64);
            String idempotencyKey = context.arguments().containsKey("idempotencyKey")
                    ? BotToolSupport.requiredText(
                            context.arguments(), "idempotencyKey", 200)
                    : UUID.randomUUID().toString();
            BotReplyResult result = replies.sendPrivate(accountId, targetQqId,
                    BotToolSupport.requiredText(context.arguments(), "text", 4000),
                    idempotencyKey);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("targetQqId", targetQqId);
            data.put("source", result.message().source().sourceAddress().canonical());
            data.put("messageKey", result.message().messageKey());
            data.put("idempotencyKey", result.message().idempotencyKey());
            data.put("deliveryState", result.deliveryState().name());
            data.put("messageIds", result.messageIds());
            data.put("errorCode", result.errorCode());
            data.put("alreadyAttempted", result.alreadyAttempted());
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (BotReplyException failure) {
            return BotToolSupport.failure(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("PRIVATE_SEND_UNAVAILABLE",
                    "主动私聊暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "targetQqId", "idempotencyKey")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
