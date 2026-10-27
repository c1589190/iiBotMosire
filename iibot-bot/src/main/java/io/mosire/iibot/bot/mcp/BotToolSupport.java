package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.bot.note.UserNote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bot MCP 工具的 JSON/参数公共层；不记录正文、token 或原始协议数据。 */
final class BotToolSupport {

    static final String ROLE = "supervisor";

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private BotToolSupport() {
    }

    static ToolResult ok(String tool, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("ok", true);
        body.put("data", data);
        return ToolResult.ok(json(body));
    }

    static ToolResult failure(String code, String message) {
        return ToolResult.error(code, message);
    }

    static ToolResult failure(String code, String message, Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return failure(code, message);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("details", details);
        return ToolResult.error(code, json(body));
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Bot 工具结果序列化失败", failure);
        }
    }

    static String requiredText(Map<String, Object> args, String name, int maxChars) {
        Object value = args == null ? null : args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是非空白字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        return normalized;
    }

    static String optionalText(Map<String, Object> args, String name, int maxChars) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > maxChars) {
            throw new InvalidArgumentsException("参数 " + name + " 超过长度上限");
        }
        return normalized;
    }

    static long optionalLong(Map<String, Object> args, String name, long fallback) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是整数");
        }
        return number.longValue();
    }

    static boolean optionalBoolean(Map<String, Object> args, String name, boolean fallback) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean bool)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是布尔值");
        }
        return bool;
    }

    static int optionalInt(Map<String, Object> args, String name, int fallback) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是整数");
        }
        return number.intValue();
    }

    static Long optionalLongObject(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是整数");
        }
        return number.longValue();
    }

    static List<String> optionalStringList(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new InvalidArgumentsException("参数 " + name + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new InvalidArgumentsException("参数 " + name + " 不能包含空标签");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    static Map<String, Object> messageMap(ChatMessage message) {
        return messageMap(message, List.of());
    }

    static Map<String, Object> messageMap(ChatMessage message,
                                           List<MessageSegment> segments) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messageKey", message.messageKey());
        value.put("threadId", message.source().threadId());
        value.put("localSeq", message.localSeq());
        value.put("direction", message.direction().name());
        value.put("senderId", message.senderId());
        value.put("senderName", message.senderName());
        value.put("messageId", message.messageId());
        value.put("sentAt", message.sentAt() == null ? null : message.sentAt().toString());
        value.put("receivedAt", message.receivedAt().toString());
        value.put("text", message.text());
        value.put("nonTextSummary", message.nonTextSummary());
        value.put("replyToMessageId", message.replyToMessageId());
        value.put("deliveryState", message.deliveryState().name());
        value.put("idempotencyKey", message.idempotencyKey());
        value.put("segments", segments == null ? List.of() : segments.stream()
                .map(BotToolSupport::segmentMap).toList());
        return value;
    }

    static Map<String, Object> segmentMap(MessageSegment segment) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("index", segment.index());
        value.put("type", segment.type().name());
        value.put("text", segment.text());
        value.put("qqId", segment.qqId());
        value.put("targetMessageId", segment.targetMessageId());
        value.put("mediaType", segment.mediaType());
        value.put("byteSize", segment.byteSize());
        value.put("hasMedia", segment.media());
        value.put("hasForward", segment.type() == MessageSegment.Type.FORWARD
                && !segment.forwardId().isEmpty());
        return value;
    }

    static Map<String, Object> threadMap(ChatThread thread) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("threadId", thread.threadId());
        value.put("accountId", thread.source().accountId());
        value.put("chatType", thread.source().chatType().wire());
        value.put("targetId", thread.source().targetId());
        value.put("displayName", thread.displayName());
        value.put("lastSeq", thread.lastSeq());
        value.put("messageCount", thread.messageCount());
        value.put("lastMessageAt", thread.lastMessageAt() == null
                ? null : thread.lastMessageAt().toString());
        return value;
    }

    static Map<String, Object> noteMap(UserNote note) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("accountId", note.accountId());
        value.put("qqId", note.qqId());
        value.put("displayName", note.displayName());
        value.put("tags", note.tags().stream().sorted().toList());
        value.put("note", note.note());
        value.put("priority", note.priority());
        value.put("revision", note.revision());
        value.put("updatedAt", note.updatedAt().toString());
        return value;
    }

    static Set<String> roles() {
        return Set.of(ROLE);
    }

    /** 参数错误稳定码。 */
    static final class InvalidArgumentsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
