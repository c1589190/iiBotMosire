package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 沿 replyToMessageId 读取引用回复链。 */
public final class BotMessageThreadTool implements AgentTool {

    public static final String NAME = "qq_message_thread";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1),
                    "depth", Map.of("type", "integer", "minimum", 1, "maximum", 5,
                            "description", "最多追溯层数，默认 3")),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;

    public BotMessageThreadTool(MessageBoardStore board) {
        this.board = Objects.requireNonNull(board, "board");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "沿 replyToMessageId 查看引用回复链；缺失/循环/超深会标记，不无限递归。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageKey = BotToolSupport.requiredText(
                    context.arguments(), "messageKey", 1024);
            int depth = BotToolSupport.optionalInt(context.arguments(), "depth", 3);
            if (depth < 1 || depth > 5) {
                throw new BotToolSupport.InvalidArgumentsException("depth 必须在 1..5");
            }
            ChatMessage root = board.find(messageKey).orElseThrow(() ->
                    new BotToolSupport.InvalidArgumentsException("messageKey 不存在"));
            List<ChatMessage> branch = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            ChatMessage current = root;
            boolean truncated = false;
            String missingParent = "";
            boolean cycle = false;
            for (int level = 0; level < depth; level++) {
                if (!visited.add(current.messageKey())) {
                    cycle = true;
                    break;
                }
                branch.add(current);
                String parentId = current.replyToMessageId();
                if (parentId == null || parentId.isBlank()) {
                    break;
                }
                Optional<ChatMessage> parent = board.findByMessageId(
                        current.source().threadId(), parentId);
                if (parent.isEmpty()) {
                    missingParent = parentId;
                    break;
                }
                current = parent.orElseThrow();
                if (level == depth - 1) {
                    truncated = true;
                }
            }
            Collections.reverse(branch);
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ChatMessage message : branch) {
                messages.add(BotToolSupport.messageMap(
                        message, board.segments(message.messageKey())));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageKey", messageKey);
            data.put("source", root.source().sourceAddress().canonical());
            data.put("threadId", root.source().threadId());
            data.put("count", messages.size());
            data.put("truncated", truncated);
            data.put("cycle", cycle);
            data.put("missingParentMessageId", missingParent);
            data.put("messages", messages);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("MESSAGE_THREAD_UNAVAILABLE",
                    "引用链读取暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("messageKey", "depth")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
