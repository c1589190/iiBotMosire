package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.group.GroupCatalogueEntry;
import io.mosire.iibot.bot.group.GroupCatalogueStore;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.GroupRequestCapability;
import io.mosire.iibot.connect.core.GroupSystemRequest;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按关键词检索 bot 能看到的群：已加入的群、待处理邀请、以及历史群目录。
 *
 * <p>QQ 官方群搜索没有可用接口（网页端接口带验证码风控，OneBot 实现也没有搜群能力），
 * 因此这里的「搜」是检索 bot 自己的关系面与历史记忆。要发现全新群，需要用联网搜索找到
 * 群号，再让群内成员邀请这个账号。</p>
 */
public final class BotGroupSearchTool implements AgentTool {

    public static final String NAME = "qq_group_search";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "keyword", Map.of("type", "string",
                            "description", "群号或群名关键词；省略表示列出最近的群"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "最多返回条数，默认 20")),
            "additionalProperties", false);

    private final Map<String, GroupRequestCapability> capabilities;
    private final GroupCatalogueStore catalogue;

    public BotGroupSearchTool(Map<String, GroupRequestCapability> capabilities,
                              GroupCatalogueStore catalogue) {
        this.capabilities = capabilities == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        this.catalogue = catalogue;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按群号或群名检索 Bot 知道的群：已加入的群、待处理邀请、历史群目录。"
                + "QQ 官方搜群接口不可用，要发现全新群请先联网搜到群号，再让群内成员邀请这个账号。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String keyword = BotToolSupport.optionalText(context.arguments(), "keyword", 64);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 20);
            if (limit < 1 || limit > 100) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..100");
            }
            Accounts accounts = resolveAccounts(context.arguments());
            Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
            if (catalogue != null) {
                for (GroupCatalogueEntry entry : catalogue.search(keyword, limit)) {
                    row(rows, entry.groupId()).put("groupName", entry.groupName());
                    row(rows, entry.groupId()).put("lastSeenAt", entry.lastSeenAt().toString());
                }
            }
            boolean liveChecked = accounts.accountId() != null;
            if (liveChecked) {
                GroupRequestCapability capability = capabilities.get(accounts.accountId());
                rememberJoined(rows, capability, keyword);
                rememberRequests(rows, capability, keyword);
            }
            List<Map<String, Object>> matches = rows.values().stream().limit(limit).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accounts.accountId());
            data.put("keyword", keyword);
            data.put("count", matches.size());
            data.put("liveChecked", liveChecked);
            data.put("matches", matches);
            if (matches.isEmpty()) {
                data.put("hint", "没有匹配到的群。用联网搜索找到目标群号后，让群内成员邀请这个账号"
                        + "（qq_group_invite_accept）；QQ 不支持按群号主动申请加群。");
                return BotToolSupport.failure("NO_MATCH",
                        keyword.isBlank() ? "群目录里还没有记录" : "没有匹配的群", data);
            }
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("GROUP_SEARCH_UNAVAILABLE",
                    "群检索暂不可用（内部原因已脱敏）");
        }
    }

    /**
     * 解析可选账号。
     *
     * <p>纯目录检索不需要账号；账号选择失败时退化为只查目录，而不是整体失败。</p>
     */
    private Accounts resolveAccounts(Map<String, Object> arguments) {
        if (capabilities.isEmpty()) {
            return new Accounts(null);
        }
        try {
            return new Accounts(BotAccountScope.resolveCapability(capabilities, arguments,
                    BotAccountToolSupport.UNSUPPORTED_GROUP_REQUEST));
        } catch (BotAccountToolSupport.AccountToolException ignored) {
            return new Accounts(null);
        }
    }

    private void rememberJoined(Map<String, Map<String, Object>> rows,
                                GroupRequestCapability capability, String keyword) {
        List<ChatConversation> groups;
        try {
            groups = BotToolSupport.await(capability.joinedGroups());
        } catch (RuntimeException unavailable) {
            return;
        }
        Instant now = Instant.now();
        for (ChatConversation group : groups) {
            if (group.sessionType() != SessionType.GROUP) {
                continue;
            }
            if (!matches(keyword, group.targetId(), group.displayName())) {
                continue;
            }
            Map<String, Object> row = row(rows, group.targetId());
            row.put("groupName", group.displayName());
            row.put("joined", true);
            upsert(new GroupCatalogueEntry(group.targetId(), group.displayName(),
                    GroupCatalogueEntry.SOURCE_JOINED, now));
        }
    }

    private void rememberRequests(Map<String, Map<String, Object>> rows,
                                  GroupRequestCapability capability, String keyword) {
        List<GroupSystemRequest> requests;
        try {
            requests = BotToolSupport.await(capability.listGroupRequests(50));
        } catch (RuntimeException unavailable) {
            return;
        }
        Instant now = Instant.now();
        for (GroupSystemRequest request : requests) {
            if (request.groupId().isBlank()
                    || !matches(keyword, request.groupId(), request.groupName())) {
                continue;
            }
            Map<String, Object> row = row(rows, request.groupId());
            if (row.get("groupName") == null || String.valueOf(row.get("groupName")).isBlank()) {
                row.put("groupName", request.groupName());
            }
            if (request.kind() == GroupSystemRequest.Kind.INVITE) {
                row.put("invited", true);
                row.put("requestId", request.sequence());
            }
            upsert(new GroupCatalogueEntry(request.groupId(), request.groupName(),
                    request.kind() == GroupSystemRequest.Kind.INVITE
                            ? GroupCatalogueEntry.SOURCE_INVITE
                            : GroupCatalogueEntry.SOURCE_JOIN_REQUEST,
                    now));
        }
    }

    private void upsert(GroupCatalogueEntry entry) {
        if (catalogue == null) {
            return;
        }
        try {
            catalogue.upsert(entry);
        } catch (RuntimeException ignored) {
            // 目录只是辅助记忆。
        }
    }

    private static boolean matches(String keyword, String groupId, String groupName) {
        return new GroupCatalogueEntry(groupId, groupName,
                GroupCatalogueEntry.SOURCE_JOINED, Instant.EPOCH).matches(keyword);
    }

    private static Map<String, Object> row(Map<String, Map<String, Object>> rows, String groupId) {
        return rows.computeIfAbsent(groupId, key -> {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("groupId", key);
            created.put("groupName", "");
            created.put("joined", false);
            created.put("invited", false);
            return created;
        });
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "keyword", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }

    private record Accounts(String accountId) {
    }
}
