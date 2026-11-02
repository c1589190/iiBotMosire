package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.group.GroupCatalogueEntry;
import io.mosire.iibot.bot.group.GroupCatalogueStore;
import io.mosire.iibot.connect.core.GroupRequestCapability;
import io.mosire.iibot.connect.core.GroupSystemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按账号列出群邀请与入群申请，并顺手把见到的群记进群目录。 */
public final class BotGroupRequestListTool implements AgentTool {

    public static final String NAME = "qq_group_request_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "最多返回条数，默认 50"),
                    "includeHandled", Map.of("type", "boolean",
                            "description", "是否包含服务端已处理的请求，默认 false")),
            "additionalProperties", false);

    private final Map<String, GroupRequestCapability> capabilities;
    private final GroupCatalogueStore catalogue;

    public BotGroupRequestListTool(Map<String, GroupRequestCapability> capabilities,
                                   GroupCatalogueStore catalogue) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities 不能为空");
        }
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        this.catalogue = catalogue;
    }

    /** 单账号便捷构造。 */
    public BotGroupRequestListTool(String accountId, GroupRequestCapability capability,
                                   GroupCatalogueStore catalogue) {
        this(single(accountId, capability), catalogue);
    }

    private static Map<String, GroupRequestCapability> single(
            String accountId, GroupRequestCapability capability) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(capability, "capability");
        return Map.of(accountId.trim(), capability);
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "列出某个 Bot 账号收到的群邀请与入群申请，返回 requestId（通知序列号）、群号、"
                + "群名与是否已处理；不返回任何票据。多账号时必须指定 accountId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = BotAccountScope.resolveCapability(capabilities,
                    context.arguments(), BotAccountToolSupport.UNSUPPORTED_GROUP_REQUEST);
            GroupRequestCapability capability = capabilities.get(accountId);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (limit < 1 || limit > 200) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..200");
            }
            boolean includeHandled =
                    BotToolSupport.optionalBoolean(context.arguments(), "includeHandled", false);
            List<GroupSystemRequest> requests =
                    BotToolSupport.await(capability.listGroupRequests(limit));
            List<GroupSystemRequest> selected = new ArrayList<>();
            for (GroupSystemRequest request : requests) {
                if (includeHandled || request.pending()) {
                    selected.add(request);
                }
            }
            remember(selected);
            List<Map<String, Object>> rows = selected.stream()
                    .map(BotGroupRequestListTool::row).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("count", rows.size());
            data.put("pendingCount", selected.stream()
                    .filter(GroupSystemRequest::pending).count());
            data.put("requests", rows);
            if (rows.isEmpty()) {
                data.put("hint", "当前没有待处理邀请或入群申请；QQ 不支持按群号主动申请加群，"
                        + "需要群内成员先邀请这个账号。");
            }
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("GROUP_REQUEST_UNAVAILABLE",
                    "群系统请求读取暂不可用（内部原因已脱敏）");
        }
    }

    private void remember(List<GroupSystemRequest> requests) {
        if (catalogue == null || requests.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<GroupCatalogueEntry> entries = new ArrayList<>();
        for (GroupSystemRequest request : requests) {
            if (request.groupId().isBlank()) {
                continue;
            }
            entries.add(new GroupCatalogueEntry(request.groupId(), request.groupName(),
                    request.kind() == GroupSystemRequest.Kind.INVITE
                            ? GroupCatalogueEntry.SOURCE_INVITE
                            : GroupCatalogueEntry.SOURCE_JOIN_REQUEST,
                    now));
        }
        try {
            catalogue.upsertAll(entries);
        } catch (RuntimeException ignored) {
            // 目录只是辅助记忆，写入失败不应让读取结果失败。
        }
    }

    private static Map<String, Object> row(GroupSystemRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("requestId", request.sequence());
        row.put("kind", request.kind().name());
        row.put("eventType", request.eventType());
        row.put("groupId", request.groupId());
        row.put("groupName", request.groupName());
        row.put("inviterId", request.inviterId());
        row.put("inviterName", request.inviterName());
        row.put("requesterId", request.requesterId());
        row.put("requesterName", request.requesterName());
        row.put("message", request.message());
        row.put("handled", request.handled());
        return row;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "limit", "includeHandled")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
