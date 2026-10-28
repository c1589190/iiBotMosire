package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按 messageKey 读取一条完整消息。 */
public final class BotMessageGetTool implements AgentTool {

    public static final String NAME = "qq_message_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1)),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;

    public BotMessageGetTool(MessageBoardStore board) {
        this.board = Objects.requireNonNull(board, "board");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按稳定 messageKey 读取一条完整消息；摘要不足时用于恢复原文。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageKey = BotToolSupport.requiredText(
                    context.arguments(), "messageKey", 1024);
            ChatMessage message = board.find(messageKey)
                    .orElseThrow(() -> new BotToolSupport.InvalidArgumentsException(
                            "messageKey 不存在"));
            return BotToolSupport.ok(NAME, BotToolSupport.messageMap(
                    message, board.segments(message.messageKey())));
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("MESSAGE_NOT_FOUND", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("MESSAGE_READ_UNAVAILABLE",
                    "消息读取暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (context.arguments().containsKey("messageKey")) {
            safe.put("messageKey", context.arguments().get("messageKey"));
        }
        return Map.copyOf(safe);
    }
}
