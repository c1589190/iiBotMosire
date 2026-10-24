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
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.QqPlatformCapability;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按消息 ID 读取一条 Bot 可访问的 QQ 消息，不返回原始协议 JSON。
 *
 * <p>NapCat 的 get_msg 依赖有界 MessageUnique 缓存；较早消息可能不在 live cache。
 * 调用方提供 chatType/targetId 时，本工具会回扫历史页兜底。</p>
 */
public final class GmQqMessageGetTool implements AgentTool {

    public static final String NAME = "tf_qq_message_get";
    public static final String RESOURCE_NAMESPACE = "qq-history";
    public static final int DEFAULT_MAX_SCAN = 200;
    public static final int MAX_SCAN = 500;
    private static final ResourceId RESOURCE = ResourceId.of(RESOURCE_NAMESPACE, "read");
    private static final ResourceManifest RESOURCES =
            ResourceManifest.of(RESOURCE_NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of(GmSecretaryToolSupport.SUPERVISOR_ROLE);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按消息 ID 读取一条 Bot 可访问的 QQ 文本消息；"
                    + "可选 target 回扫较早历史。",
            "properties", Map.of(
                    "messageId", Map.of("type", "string", "minLength", 1,
                            "description", "QQ 消息 ID"),
                    "chatType", Map.of("type", "string",
                            "enum", List.of("private", "group"),
                            "description", "回扫目标类型；提供 targetId 时必填"),
                    "targetId", Map.of("type", "string",
                            "description", "回扫目标的 QQ 号或群号"),
                    "maxScan", Map.of("type", "integer", "minimum", 1,
                            "maximum", MAX_SCAN,
                            "description", "live cache 未命中时最多扫描的历史消息数，默认 "
                                    + DEFAULT_MAX_SCAN)),
            "required", List.of("messageId"),
            "additionalProperties", false);

    private final QqPlatformCapability platform;

    public GmQqMessageGetTool(QqPlatformCapability platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读按消息 ID 取一条 QQ 消息；live cache 未命中时可按 target 回扫历史。";
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
            String messageId = GmSecretaryToolSupport.requiredText(
                    context.arguments(), "messageId", 128);
            String targetId = optionalText(context.arguments(), "targetId", 128);
            SessionType scanType = optionalChatType(context.arguments());
            if (targetId != null && scanType == null) {
                return GmSecretaryToolSupport.failure(NAME, "CHAT_TYPE_REQUIRED",
                        "提供 targetId 时必须同时提供 chatType");
            }
            int maxScan = optionalMaxScan(context.arguments());
            try {
                ChatMessageDetail message = platform.message(messageId)
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                return success(message, "LIVE_CACHE", 0);
            } catch (ExecutionException notInLiveCache) {
                if (targetId == null || scanType == null) {
                    return GmSecretaryToolSupport.failure(NAME,
                            "MESSAGE_NOT_IN_LIVE_CACHE",
                            "NapCat live cache 未命中；提供 chatType/targetId 可回扫历史",
                            Map.of("hint", "较早消息不在 get_msg 的有界缓存时，"
                                    + "用 chatType/targetId/maxScan 回扫"));
                }
            }

            ScannedMessage scanned = scanHistory(messageId, scanType, targetId, maxScan);
            if (scanned.found() == null) {
                return GmSecretaryToolSupport.failure(NAME, "MESSAGE_NOT_FOUND_IN_SCAN",
                        "历史回扫范围内未找到该消息",
                        Map.of("scannedMessages", scanned.scannedMessages(),
                                "maxScan", maxScan));
            }
            return success(scanned.found(), "HISTORY_SCAN", scanned.scannedMessages());
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSecretaryToolSupport.InvalidArgumentsException invalid) {
            return GmSecretaryToolSupport.failure(NAME, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (TimeoutException timeout) {
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_TIMEOUT", "消息读取超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_INTERRUPTED", "消息读取被中断");
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "MESSAGE_UNAVAILABLE",
                    "消息读取失败（内部原因已脱敏）");
        }
    }

    private ScannedMessage scanHistory(String messageId, SessionType type,
                                       String targetId, int maxScan) {
        int scanned = 0;
        String cursor = null;
        Set<String> seenCursors = new HashSet<>();
        while (scanned < maxScan) {
            int pageSize = Math.min(100, maxScan - scanned);
            ChatHistoryPage page;
            try {
                page = platform.history(new ChatHistoryQuery(
                                type, targetId, cursor, pageSize, true))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GmSecretaryToolSupport.InvalidArgumentsException(
                        "历史回扫被中断");
            } catch (ExecutionException | TimeoutException failure) {
                return new ScannedMessage(null, scanned);
            }
            for (ChatHistoryMessage message : page.messages()) {
                if (message.messageId().equals(messageId)) {
                    ChatMessageDetail detail = new ChatMessageDetail(
                            message.messageId(), type, targetId, message.senderId(),
                            message.senderName(), message.text(), message.sentAt(), 0);
                    return new ScannedMessage(detail, scanned + 1);
                }
            }
            scanned += page.messages().size() + page.nonTextSkipped();
            String next = page.nextCursor();
            if (page.exhausted() || next == null || !seenCursors.add(next)) {
                break;
            }
            cursor = next;
        }
        return new ScannedMessage(null, scanned);
    }

    private static ToolResult success(ChatMessageDetail message, String resolvedBy,
                                      int scannedMessages) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", message.messageId());
        data.put("chatType", message.sessionType().name().toLowerCase());
        data.put("targetId", message.targetId());
        data.put("senderId", message.senderId());
        data.put("senderName", message.senderName());
        data.put("text", message.text());
        data.put("sentAt", message.sentAt() == null ? null : message.sentAt().toString());
        data.put("nonTextSegments", message.nonTextSegments());
        data.put("resolvedBy", resolvedBy);
        if ("HISTORY_SCAN".equals(resolvedBy)) {
            data.put("scannedMessages", scannedMessages);
        }
        return GmSecretaryToolSupport.success(NAME, data);
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

    private static SessionType optionalChatType(Map<String, Object> args) {
        String raw = optionalText(args, "chatType", 16);
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase()) {
            case "private" -> SessionType.PRIVATE;
            case "group" -> SessionType.GROUP;
            default -> throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "chatType 只允许 private 或 group");
        };
    }

    private static int optionalMaxScan(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("maxScan");
        if (raw == null) {
            return DEFAULT_MAX_SCAN;
        }
        if (!(raw instanceof Number number)) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "maxScan 必须是整数");
        }
        int value = number.intValue();
        if (value < 1 || value > MAX_SCAN) {
            throw new GmSecretaryToolSupport.InvalidArgumentsException(
                    "maxScan 必须在 1.." + MAX_SCAN + " 范围内");
        }
        return value;
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String name : List.of("messageId", "chatType", "targetId", "maxScan")) {
                if (args.containsKey(name)) {
                    safe.put(name, args.get(name));
                }
            }
        }
        return Map.copyOf(safe);
    }

    private record ScannedMessage(ChatMessageDetail found, int scannedMessages) {
    }
}
