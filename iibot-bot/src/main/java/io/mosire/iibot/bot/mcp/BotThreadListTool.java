package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.bot.weight.SourceWeightPolicy;
import io.mosire.iibot.bot.weight.SourceWeightService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 列出消息板来源 thread。 */
public final class BotThreadListTool implements AgentTool {

    public static final String NAME = "bot_thread_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 1000,
                            "description", "最多返回 thread 数，默认 50")),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final SourceWeightService weights;

    public BotThreadListTool(MessageBoardStore board) {
        this(board, null);
    }

    public BotThreadListTool(MessageBoardStore board, SourceWeightService weights) {
        this.board = Objects.requireNonNull(board, "board");
        this.weights = weights;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "列出 Bot 消息板的来源 thread（私聊/群聊）；只返回元数据，不返回全部正文。";
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
            List<Map<String, Object>> threads = new ArrayList<>();
            board.threads(limit).forEach(thread -> {
                Map<String, Object> value = new LinkedHashMap<>(
                        BotToolSupport.threadMap(thread));
                if (weights != null) {
                    SourceWeightPolicy policy = weights.findPolicy(thread.threadId()).orElse(null);
                    value.put("sourceWeight", policy == null ? null : policy.sourceWeight());
                    value.put("notifyThreshold", policy == null ? null : policy.notifyThreshold());
                    value.put("bootstrapDelivered", policy != null && policy.bootstrapDelivered());
                    value.put("weightRevision", policy == null ? null : policy.revision());
                }
                threads.add(value);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", threads.size());
            data.put("threads", threads);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("THREAD_LIST_UNAVAILABLE",
                    "消息板 thread 列表暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        copy(context, safe, "limit");
        return Map.copyOf(safe);
    }

    private static void copy(ToolContext context, Map<String, Object> target, String name) {
        if (context.arguments().containsKey(name)) {
            target.put(name, context.arguments().get(name));
        }
    }
}
