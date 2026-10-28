package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.inbox.InboxDigestException;
import io.mosire.iibot.bot.inbox.InboxDigestService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 主 Agent 按 snapshot 消费 Inbox 未读桶；完整消费后归档。 */
public final class BotThreadAckTool implements AgentTool {

    public static final String NAME = "bot_thread_ack";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of("type", "string", "minLength", 1),
                    "itemId", Map.of("type", "string", "minLength", 1),
                    "expectedRevision", Map.of("type", "integer", "minimum", 1,
                            "description", "inbox_view 返回的 item revision"),
                    "snapshotToSeq", Map.of("type", "integer", "minimum", 1,
                            "description", "inbox_view content.toSeq；消费本轮处理范围，部分 ack 保留剩余未读")),
            "required", List.of("threadId", "itemId", "expectedRevision"),
            "additionalProperties", false);

    private final InboxDigestService service;

    public BotThreadAckTool(InboxDigestService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按 inbox_view 的 snapshotToSeq 消费未读范围并保留 thread 游标；"
                + "部分 ack 保留同一 item 的剩余未读，完整 ack 后归档；"
                + "ITEM_STATUS_CONFLICT 时按 details 停止或由宿主处置。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String threadId = BotToolSupport.requiredText(context.arguments(), "threadId", 512);
            String itemId = BotToolSupport.requiredText(context.arguments(), "itemId", 512);
            long revision = BotToolSupport.optionalLong(
                    context.arguments(), "expectedRevision", -1L);
            if (revision <= 0L) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "expectedRevision 必须为正");
            }
            long snapshotToSeq = BotToolSupport.optionalLong(
                    context.arguments(), "snapshotToSeq", 0L);
            InboxDigestService.AckResult ack = service.markHandled(
                    threadId, itemId, revision, snapshotToSeq);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("threadId", threadId);
            data.put("itemId", itemId);
            data.put("archived", ack.archived());
            data.put("digestedThroughSeq", ack.digestedThroughSeq());
            if (ack.remainingToSeq() > 0L) {
                data.put("remaining", true);
                data.put("remainingItemId", ack.remainingItemId());
                data.put("remainingToSeq", ack.remainingToSeq());
            }
            if (snapshotToSeq > 0L) {
                data.put("snapshotToSeq", snapshotToSeq);
            }
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (InboxDigestException failure) {
            return BotToolSupport.failure(
                    failure.code(), failure.getMessage(), failure.details());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("INBOX_UNAVAILABLE",
                    "Inbox digest 归档暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("threadId", "itemId", "expectedRevision",
                "snapshotToSeq")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
