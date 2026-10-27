package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.ForwardMessage;
import io.mosire.iibot.connect.core.ForwardPage;
import io.mosire.iibot.connect.core.MessageSegment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 按消息中的 forward 段读取有界合并转发树。 */
public final class BotForwardReadTool implements AgentTool {

    public static final String NAME = "bot_forward_read";
    private static final int MAX_NODES = 200;
    private static final long TIMEOUT_SECONDS = 20L;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1),
                    "segmentIndex", Map.of("type", "integer", "minimum", 0,
                            "description", "消息中的 forward 段下标；省略取第一个"),
                    "depth", Map.of("type", "integer", "minimum", 0, "maximum", 3,
                            "description", "递归展开层数，默认 2"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "每层最多节点数，默认 50")),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final ForwardCapability forwardProvider;

    public BotForwardReadTool(MessageBoardStore board, ForwardCapability forwardProvider) {
        this.board = Objects.requireNonNull(board, "board");
        this.forwardProvider = forwardProvider;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取合并转发树；只返回有界文本/安全 segment 摘要，嵌套层数和节点数受限。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        if (forwardProvider == null) {
            return BotToolSupport.failure("FORWARD_UNAVAILABLE", "当前适配器未提供合并转发能力");
        }
        try {
            String messageKey = BotToolSupport.requiredText(
                    context.arguments(), "messageKey", 1024);
            ChatMessage message = board.find(messageKey).orElseThrow(() ->
                    new BotToolSupport.InvalidArgumentsException("messageKey 不存在"));
            List<MessageSegment> segments = board.segments(messageKey);
            MessageSegment forward = selectForward(segments,
                    BotToolSupport.optionalInt(context.arguments(), "segmentIndex", -1));
            if (forward == null) {
                return BotToolSupport.failure("FORWARD_NOT_FOUND",
                        "消息中没有可读取的 forward 段");
            }
            int depth = BotToolSupport.optionalInt(context.arguments(), "depth", 2);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (depth < 0 || depth > 3) {
                throw new BotToolSupport.InvalidArgumentsException("depth 必须在 0..3");
            }
            if (limit < 1 || limit > 100) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..100");
            }
            Map<String, Object> tree = readForward(
                    forward.forwardId(), depth, limit, new HashSet<>(), new AtomicInteger());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageKey", messageKey);
            data.put("segmentIndex", forward.index());
            data.put("depth", depth);
            data.put("limit", limit);
            data.put("messages", tree.get("messages"));
            data.put("truncated", tree.get("truncated"));
            data.put("cycle", tree.get("cycle"));
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("FORWARD_UNAVAILABLE",
                    "合并转发读取暂不可用（内部原因已脱敏）");
        }
    }

    private Map<String, Object> readForward(
            String forwardId, int remainingDepth, int limit,
            Set<String> activeIds, AtomicInteger budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> messages = new ArrayList<>();
        boolean truncated = false;
        boolean cycle = activeIds.contains(forwardId);
        if (!cycle) {
            activeIds.add(forwardId);
            try {
                ForwardPage page = forwardProvider.forward(forwardId, limit)
                        .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                truncated = page.truncated();
                for (ForwardMessage message : page.messages()) {
                    if (budget.incrementAndGet() > MAX_NODES) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("senderId", message.senderId());
                    value.put("senderName", message.senderName());
                    value.put("sentAt", message.sentAt() == null
                            ? null : message.sentAt().toString());
                    value.put("text", message.text());
                    value.put("segments", message.segments().stream()
                            .map(BotToolSupport::segmentMap).toList());
                    List<Map<String, Object>> nested = new ArrayList<>();
                    if (remainingDepth > 0 && budget.get() < MAX_NODES) {
                        for (MessageSegment segment : message.segments()) {
                            if (segment.type() != MessageSegment.Type.FORWARD
                                    || segment.forwardId().isBlank()
                                    || budget.get() >= MAX_NODES) {
                                continue;
                            }
                            nested.add(readForward(segment.forwardId(), remainingDepth - 1,
                                    limit, activeIds, budget));
                        }
                    }
                    value.put("forwards", nested);
                    messages.add(value);
                }
            } catch (Exception failure) {
                throw new IllegalStateException("forward 读取失败", failure);
            } finally {
                activeIds.remove(forwardId);
            }
        }
        result.put("messages", messages);
        result.put("truncated", truncated);
        result.put("cycle", cycle);
        return result;
    }

    private static MessageSegment selectForward(List<MessageSegment> segments, int index) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        if (index >= 0) {
            return segments.stream()
                    .filter(segment -> segment.index() == index)
                    .filter(segment -> segment.type() == MessageSegment.Type.FORWARD)
                    .filter(segment -> !segment.forwardId().isBlank())
                    .findFirst().orElse(null);
        }
        return segments.stream()
                .filter(segment -> segment.type() == MessageSegment.Type.FORWARD)
                .filter(segment -> !segment.forwardId().isBlank())
                .findFirst().orElse(null);
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("messageKey", "segmentIndex", "depth", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
