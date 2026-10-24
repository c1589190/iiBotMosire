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
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 只读 NapCat 聊天记录查看器 MCP 工具。
 *
 * <p>控制方可以查询任意 Bot 账号可访问的私聊/群历史；不传目标时默认查当前推演群。
 * 本工具不发送 QQ、不写 app cache、不自动重试，也不返回 OneBot 原始 JSON、token 或完整 URL。</p>
 */
public final class GmChatHistoryTool implements AgentTool {

    public static final String NAME = "tf_qq_history_read";
    public static final String RESOURCE_NAMESPACE = "qq-history";
    public static final int MAX_RESULT_CHARS = 32 * 1024;
    public static final int MAX_MESSAGE_CHARS = 4_000;
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    private static final ResourceManifest RESOURCES = ResourceManifest.of(
            RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "read");
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "只读查看 Bot 可访问的 QQ 私聊/群聊历史文本；"
                    + "省略目标时默认当前推演群，不发送 QQ。",
            "properties", Map.of(
                    "chatType", Map.of(
                            "type", "string",
                            "enum", List.of("private", "group", "auto"),
                            "description", "private/group；auto 时按 targetId 解析"),
                    "targetId", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "私聊 QQ 号或群号；省略时查当前推演群"),
                    "cursor", Map.of(
                            "type", "string",
                            "description", "可选 message_seq 游标；缺省从最新页开始"),
                    "count", Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "maximum", 100,
                            "description", "单页条数，默认 20"),
                    "direction", Map.of(
                            "type", "string",
                            "enum", List.of("older", "newer"),
                            "description", "翻页方向，默认 older（向过去）")),
            "additionalProperties", false);

    private final ChatHistoryProvider provider;
    private final Supplier<String> publicGroupId;
    private final QqChatDirectoryService directory;

    public GmChatHistoryTool(ChatHistoryProvider provider,
                             Supplier<String> publicGroupId,
                             QqChatDirectoryService directory) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.publicGroupId = Objects.requireNonNull(publicGroupId, "publicGroupId");
        this.directory = directory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读查看 Bot 可访问的 QQ 私聊/群聊历史文本；省略目标时默认当前推演群；"
                + "不发送 QQ、不写世界、不返回原始协议 JSON。";
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
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        copyArg(context, safe, "chatType");
        copyArg(context, safe, "targetId");
        copyArg(context, safe, "count");
        copyArg(context, safe, "direction");
        return Map.copyOf(safe);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            context.resources().require(Operation.READ, RESOURCE);
            String targetText = optionalText(context.arguments(), "targetId", 128);
            String chatTypeText = optionalText(context.arguments(), "chatType", 16);
            String targetId;
            SessionType type;
            if (targetText == null) {
                String configured = publicGroupId.get();
                if (configured == null || configured.isBlank()) {
                    return failure("DEFAULT_GROUP_UNAVAILABLE", "当前没有配置推演群");
                }
                targetId = configured.trim();
                type = SessionType.GROUP;
            } else {
                targetId = targetText;
                if (chatTypeText == null || "auto".equalsIgnoreCase(chatTypeText)) {
                    Optional<SessionType> resolved = resolveType(targetId);
                    if (resolved.isEmpty()) {
                        return failure("CHAT_TYPE_REQUIRED",
                                "无法确定 targetId 是私聊还是群聊；请显式传 chatType");
                    }
                    type = resolved.orElseThrow();
                } else {
                    type = switch (chatTypeText.toLowerCase()) {
                        case "private" -> SessionType.PRIVATE;
                        case "group" -> SessionType.GROUP;
                        default -> throw new GmSecretaryToolSupport.InvalidArgumentsException(
                                "chatType 只允许 private、group 或 auto");
                    };
                }
            }
            String cursor = optionalText(context.arguments(), "cursor", 128);
            int count = optionalInt(context.arguments(), "count", 20);
            if (count < 1 || count > 100) {
                throw new GmSecretaryToolSupport.InvalidArgumentsException(
                        "count 必须在 1–100 之间");
            }
            String direction = optionalText(context.arguments(), "direction", 16);
            boolean reverseOrder = direction == null || "older".equalsIgnoreCase(direction);
            if (direction != null && !"older".equalsIgnoreCase(direction)
                    && !"newer".equalsIgnoreCase(direction)) {
                throw new GmSecretaryToolSupport.InvalidArgumentsException(
                        "direction 只允许 older 或 newer");
            }

            ChatHistoryPage page = provider.history(new ChatHistoryQuery(
                            type, targetId, cursor, count, reverseOrder))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            List<Map<String, Object>> messages = new ArrayList<>();
            StringBuilder json = new StringBuilder();
            boolean truncated = false;
            for (ChatHistoryMessage message : page.messages()) {
                String text = message.text();
                if (text.length() > MAX_MESSAGE_CHARS) {
                    text = text.substring(0, MAX_MESSAGE_CHARS) + TRUNCATION_MARKER;
                    truncated = true;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("messageId", message.messageId());
                item.put("senderId", message.senderId());
                item.put("senderName", message.senderName());
                item.put("text", text);
                item.put("sentAt", message.sentAt() == null ? null : message.sentAt().toString());
                messages.add(item);
                json.append(text).append('\n');
            }
            if (json.length() > MAX_RESULT_CHARS) {
                return failure("HISTORY_RESULT_TOO_LARGE",
                        "聊天记录结果过大；请减小 count 或使用更早游标分段读取");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("chatType", type.name().toLowerCase());
            data.put("targetId", targetId);
            data.put("messages", messages);
            data.put("nextCursor", page.nextCursor());
            data.put("exhausted", page.exhausted());
            data.put("nonTextSkipped", page.nonTextSkipped());
            data.put("truncated", truncated);
            if (truncated) {
                data.put("truncationNote",
                        "部分单条消息正文过长，已按单条上限截断；可缩小 count 或分段读取。");
            }
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (TimeoutException timeout) {
            return failure("HISTORY_TIMEOUT", "聊天历史读取超时");
        } catch (ExecutionException failure) {
            return failure("HISTORY_ERROR", "聊天历史读取失败或目标不可访问");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("HISTORY_INTERRUPTED", "聊天历史读取被中断");
        } catch (RuntimeException failure) {
            return failure("HISTORY_ERROR", "聊天历史读取失败");
        }
    }

    private Optional<SessionType> resolveType(String targetId) {
        String configured = publicGroupId.get();
        if (configured != null && configured.equals(targetId)) {
            return Optional.of(SessionType.GROUP);
        }
        return directory == null ? Optional.empty() : directory.resolve(targetId);
    }

    private static String optionalText(Map<String, Object> arguments, String name, int maxChars) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 必须是字符串");
        }
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > maxChars) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 超过长度上限");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "参数 " + name + " 必须是整数");
        }
        return number.intValue();
    }

    private static void copyArg(ToolContext context, Map<String, Object> target, String name) {
        Object value = context.arguments() == null ? null : context.arguments().get(name);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(name, value);
        }
    }

    private static ToolResult failure(String code, String message) {
        return GmSecretaryToolSupport.failure(NAME, code, message);
    }
}
