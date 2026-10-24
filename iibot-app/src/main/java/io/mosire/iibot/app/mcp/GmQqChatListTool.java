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
import io.mosire.iibot.app.chat.QqChatDirectoryService;
import io.mosire.iibot.connect.core.ChatConversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 列出 Bot 可访问的 QQ 会话，默认只返回当前推演群。 */
public final class GmQqChatListTool implements AgentTool {

    public static final String NAME = "tf_qq_chat_list";
    public static final String RESOURCE_NAMESPACE = "qq-directory";
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "read");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "列出 Bot 可访问的最近会话/群/好友；默认只返回当前推演群。",
            "properties", Map.of(
                    "scope", Map.of("type", "string",
                            "enum", List.of("configured", "recent", "groups", "friends", "all"),
                            "description", "目录范围，默认 configured"),
                    "count", Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "最多返回条数，默认 50")),
            "additionalProperties", false);

    private final QqChatDirectoryService directory;

    public GmQqChatListTool(QqChatDirectoryService directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出 Bot 可访问的 QQ 会话元数据；默认只返回当前推演群，显式 scope 展开。";
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
            String scopeText = optionalText(context.arguments(), "scope", 32);
            QqChatDirectoryService.Scope scope;
            try {
                scope = scopeText == null ? QqChatDirectoryService.Scope.CONFIGURED
                        : QqChatDirectoryService.Scope.valueOf(scopeText.toUpperCase());
            } catch (IllegalArgumentException invalid) {
                throw new GmSecretaryToolSupport.InvalidArgumentsException(
                        "scope 只允许 configured/recent/groups/friends/all");
            }
            int count = optionalInt(context.arguments(), "count", 50);
            if (count < 1 || count > 200) {
                throw new GmSecretaryToolSupport.InvalidArgumentsException(
                        "count 必须在 1–200 之间");
            }
            QqChatDirectoryService.Result result = directory.list(scope, count);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ChatConversation conversation : result.conversations()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chatType", conversation.sessionType().name().toLowerCase());
                item.put("targetId", conversation.targetId());
                item.put("displayName", conversation.displayName());
                item.put("remark", conversation.remark());
                item.put("lastMessageAt", conversation.lastMessageAt() == null
                        ? null : conversation.lastMessageAt().toString());
                item.put("lastMessageId", conversation.lastMessageId());
                items.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scope", scope.name().toLowerCase());
            data.put("count", items.size());
            data.put("chats", items);
            data.put("partial", result.partial());
            data.put("warnings", result.warnings());
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "DIRECTORY_UNAVAILABLE",
                    "QQ 会话目录暂不可用（内部原因已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String name : List.of("scope", "count")) {
                if (args.containsKey(name)) {
                    safe.put(name, args.get(name));
                }
            }
        }
        return Map.copyOf(safe);
    }

    private static String optionalText(Map<String, Object> args, String name, int max) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 必须是非空白字符串");
        }
        if (text.length() > max) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 超过长度上限");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 必须是整数");
        }
        return number.intValue();
    }
}
