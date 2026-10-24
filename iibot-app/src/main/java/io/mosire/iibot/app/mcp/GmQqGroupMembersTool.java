package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.QqPlatformCapability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 只读群成员列表，用于公屏玩家定位与入团前核验。 */
public final class GmQqGroupMembersTool implements AgentTool {

    public static final String NAME = "tf_qq_group_members";
    public static final String RESOURCE_NAMESPACE = "qq-directory";
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "read");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "只读查询 Bot 已加入群的成员列表。",
            "properties", Map.of(
                    "groupId", Map.of("type", "string", "minLength", 1,
                            "description", "群号")),
            "required", List.of("groupId"),
            "additionalProperties", false);

    private final QqPlatformCapability platform;

    public GmQqGroupMembersTool(QqPlatformCapability platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读查询 QQ 群成员；不发送消息、不写世界。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Set<String> roles() {
        return ROLES;
    }

    @Override
    public ResourceManifest resources() {
        return RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            context.resources().require(Operation.READ, RESOURCE);
            String groupId = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "groupId", 64);
            List<GroupMember> members = platform.groupMembers(groupId)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            List<Map<String, Object>> items = new ArrayList<>();
            for (GroupMember member : members) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("groupId", member.groupId());
                item.put("qqId", member.qqId());
                item.put("displayName", member.displayName());
                item.put("card", member.card());
                item.put("role", member.role());
                items.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("groupId", groupId);
            data.put("count", items.size());
            data.put("members", items);
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (TimeoutException timeout) {
            return GmSecretaryToolSupport.failure(NAME, "DIRECTORY_TIMEOUT", "群成员读取超时");
        } catch (ExecutionException failure) {
            return GmSecretaryToolSupport.failure(NAME, "DIRECTORY_UNAVAILABLE",
                    "群成员读取失败（内部原因已脱敏）");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GmSecretaryToolSupport.failure(NAME, "DIRECTORY_INTERRUPTED",
                    "群成员读取被中断");
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "DIRECTORY_UNAVAILABLE",
                    "群成员读取失败（内部原因已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        return args != null && args.get("groupId") instanceof String groupId
                ? Map.of("groupId", groupId) : Map.of();
    }
}
