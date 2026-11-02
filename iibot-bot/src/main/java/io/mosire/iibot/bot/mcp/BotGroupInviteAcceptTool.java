package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.group.GroupCatalogueEntry;
import io.mosire.iibot.bot.group.GroupCatalogueStore;
import io.mosire.iibot.connect.core.GroupRequestCapability;
import io.mosire.iibot.connect.core.GroupSystemRequest;
import io.mosire.iibot.connect.core.SendReceipt;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 接受一条群邀请，把 bot 加进群。
 *
 * <p>QQ 不支持按群号主动申请入群，所以能「自己加群」的唯一途径就是接受邀请。群号必填，
 * 序列号可省略：省略时从待处理邀请里按群号找；卡片里带着 msgseq 时直接传更稳。</p>
 */
public final class BotGroupInviteAcceptTool implements AgentTool {

    public static final String NAME = "qq_group_invite_accept";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "groupId", Map.of("type", "string",
                            "description", "要加入的群号"),
                    "sequence", Map.of("type", "integer",
                            "description", "邀请卡片里的 msgseq 或 requestId；省略时按群号查找待处理邀请"),
                    "eventType", Map.of("type", "integer",
                            "description", "QQ 通知类型，群邀请默认 2，一般不用改")),
            "required", List.of("groupId"),
            "additionalProperties", false);

    private final Map<String, GroupRequestCapability> capabilities;
    private final GroupCatalogueStore catalogue;

    public BotGroupInviteAcceptTool(Map<String, GroupRequestCapability> capabilities,
                                    GroupCatalogueStore catalogue) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities 不能为空");
        }
        this.capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        this.catalogue = catalogue;
    }

    /** 单账号便捷构造。 */
    public BotGroupInviteAcceptTool(String accountId, GroupRequestCapability capability,
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
        return "接受一条群邀请并把 Bot 加进该群。groupId 必填；sequence 可省略，省略时按群号"
                + "在待处理邀请里查找。QQ 不允许按群号主动申请入群，没有邀请时只能等群内成员邀请。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = BotAccountScope.resolveCapability(capabilities,
                    context.arguments(), BotAccountToolSupport.UNSUPPORTED_GROUP_REQUEST);
            GroupRequestCapability capability = capabilities.get(accountId);
            String groupId = BotToolSupport.requiredText(context.arguments(), "groupId", 32);
            long sequence = BotToolSupport.optionalLong(context.arguments(), "sequence", 0L);
            int eventType = BotToolSupport.optionalInt(context.arguments(), "eventType",
                    GroupSystemRequest.EVENT_TYPE_INVITE);
            if (eventType <= 0) {
                eventType = GroupSystemRequest.EVENT_TYPE_INVITE;
            }

            Optional<GroupSystemRequest> pending = sequence > 0L
                    ? Optional.empty()
                    : findPendingInvite(capability, groupId);
            if (sequence <= 0L) {
                if (pending.isEmpty()) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("accountId", accountId);
                    details.put("groupId", groupId);
                    details.put("hint", "QQ 不支持按群号主动申请加群。请让群内成员邀请这个账号，"
                            + "或让管理员先拉它进群；收到邀请后再调用本工具。");
                    return BotToolSupport.failure("NO_INVITATION",
                            "没有找到群 " + groupId + " 的待处理邀请", details);
                }
                sequence = pending.get().sequence();
                eventType = pending.get().eventType();
            }

            SendReceipt receipt = BotToolSupport.await(
                    capability.acceptGroupInvitation(groupId, sequence, eventType));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("groupId", groupId);
            data.put("groupName", pending.map(GroupSystemRequest::groupName).orElse(""));
            data.put("sequence", sequence);
            data.put("state", receipt.state().name());
            data.put("errorCode", receipt.errorCode());
            data.put("detail", receipt.detail());
            if (receipt.state() == SendReceipt.State.UNKNOWN) {
                return BotToolSupport.failure("GROUP_INVITE_UNKNOWN",
                        "接受群邀请的结果未知，禁止自动重试；稍后用群列表确认", data);
            }
            if (!receipt.isSent()) {
                return BotToolSupport.failure(
                        receipt.errorCode() == null ? "GROUP_INVITE_REJECTED"
                                : receipt.errorCode(),
                        "服务端没有接受这次入群请求", data);
            }
            remember(groupId, pending.map(GroupSystemRequest::groupName).orElse(""));
            data.put("joined", true);
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("GROUP_INVITE_UNAVAILABLE",
                    "接受群邀请暂不可用（内部原因已脱敏）");
        }
    }

    private Optional<GroupSystemRequest> findPendingInvite(
            GroupRequestCapability capability, String groupId) {
        List<GroupSystemRequest> requests = BotToolSupport.await(capability.listGroupRequests(50));
        return requests.stream()
                .filter(GroupSystemRequest::pending)
                .filter(request -> request.kind() == GroupSystemRequest.Kind.INVITE)
                .filter(request -> request.groupId().equals(groupId))
                .findFirst();
    }

    private void remember(String groupId, String groupName) {
        if (catalogue == null) {
            return;
        }
        try {
            catalogue.upsert(new GroupCatalogueEntry(groupId, groupName,
                    GroupCatalogueEntry.SOURCE_JOINED, Instant.now()));
        } catch (RuntimeException ignored) {
            // 目录只是辅助记忆。
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "groupId", "sequence", "eventType")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
