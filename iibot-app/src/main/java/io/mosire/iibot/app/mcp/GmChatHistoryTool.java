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
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.taskflow.agent.MappedIdentity;

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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 只读 NapCat 聊天记录查看器 MCP 工具。
 *
 * <p>它把 adapter 的历史能力暴露给外部 supervisor：只允许查询已注册玩家/推演的私聊
 * QQ，以及当前公共群；不发送 QQ、不写 app cache、不自动重试。返回文本消息和游标，
 * 不返回 OneBot 原始 JSON、token 或完整 HTTP URL。</p>
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
            "description", "只读查看已注册玩家/推演私聊或当前公共群的 NapCat 历史文本；不发送 QQ。",
            "properties", Map.of(
                    "chatType", Map.of(
                            "type", "string",
                            "enum", List.of("private", "group"),
                            "description", "private=私聊，group=公共群"),
                    "targetId", Map.of(
                            "type", "string",
                            "minLength", 1,
                            "description", "私聊 QQ 号或群号"),
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
            "required", List.of("chatType", "targetId"),
            "additionalProperties", false);

    private final ChatHistoryProvider provider;
    private final Function<String, Optional<MappedIdentity>> privateResolver;
    private final Supplier<String> publicGroupId;

    public GmChatHistoryTool(ChatHistoryProvider provider,
                             Function<String, Optional<MappedIdentity>> privateResolver,
                             Supplier<String> publicGroupId) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.privateResolver = Objects.requireNonNull(privateResolver, "privateResolver");
        this.publicGroupId = Objects.requireNonNull(publicGroupId, "publicGroupId");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读查看已注册玩家/推演私聊或当前公共群的 NapCat 历史文本；"
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
            String chatType = requiredText(context.arguments(), "chatType", 16).toLowerCase();
            String targetId = requiredText(context.arguments(), "targetId", 128);
            SessionType type = switch (chatType) {
                case "private" -> SessionType.PRIVATE;
                case "group" -> SessionType.GROUP;
                default -> throw new InvalidArguments("chatType 只允许 private 或 group");
            };
            if (type == SessionType.PRIVATE && privateResolver.apply(targetId).isEmpty()) {
                return failure("TARGET_NOT_ALLOWED", "只允许查询已注册玩家/推演的私聊 QQ");
            }
            if (type == SessionType.GROUP) {
                String groupId = publicGroupId.get();
                if (groupId == null || !groupId.equals(targetId)) {
                    return failure("TARGET_NOT_ALLOWED", "只允许查询当前公共群");
                }
            }
            String cursor = optionalText(context.arguments(), "cursor", 128);
            int count = optionalInt(context.arguments(), "count", 20);
            if (count < 1 || count > 100) {
                throw new InvalidArguments("count 必须在 1–100 之间");
            }
            String direction = optionalText(context.arguments(), "direction", 16);
            boolean reverseOrder = direction == null || "older".equalsIgnoreCase(direction);
            if (!"older".equalsIgnoreCase(direction == null ? "older" : direction)
                    && !"newer".equalsIgnoreCase(direction)) {
                throw new InvalidArguments("direction 只允许 older 或 newer");
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
            data.put("chatType", chatType);
            data.put("targetId", targetId);
            data.put("messages", messages);
            data.put("nextCursor", page.nextCursor());
            data.put("exhausted", page.exhausted());
            data.put("truncated", truncated);
            if (truncated) {
                data.put("truncationNote", "部分单条消息正文过长，已按单条上限截断；可缩小 count 或分段读取。");
            }
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (InvalidArguments invalid) {
            return failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (TimeoutException timeout) {
            return failure("HISTORY_TIMEOUT", "聊天历史读取超时");
        } catch (ExecutionException failure) {
            return failure("HISTORY_ERROR", "聊天历史读取失败");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("HISTORY_INTERRUPTED", "聊天历史读取被中断");
        } catch (RuntimeException failure) {
            return failure("HISTORY_ERROR", "聊天历史读取失败");
        }
    }

    private static String requiredText(Map<String, Object> arguments, String name, int maxChars) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidArguments("参数 " + name + " 必须是非空白字符串");
        }
        if (text.length() > maxChars) {
            throw new InvalidArguments("参数 " + name + " 超过长度上限");
        }
        return text.trim();
    }

    private static String optionalText(Map<String, Object> arguments, String name, int maxChars) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new InvalidArguments("参数 " + name + " 必须是字符串");
        }
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > maxChars) {
            throw new InvalidArguments("参数 " + name + " 超过长度上限");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> arguments, String name, int fallback) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidArguments("参数 " + name + " 必须是整数");
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

    private static final class InvalidArguments extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InvalidArguments(String message) {
            super(message);
        }
    }
}
