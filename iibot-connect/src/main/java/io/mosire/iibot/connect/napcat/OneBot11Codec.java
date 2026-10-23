package io.mosire.iibot.connect.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * OneBot 11 消息事件到中立 {@link InboundMessage} 的解析器。
 *
 * <p>该类只解析 post_type=message 且 message_type 为 private/group 的事件；
 * meta_event/notice/request 及未知事件返回空。非文本段会被跳过，不下载任何资源；
 * 没有任何文本时返回空。</p>
 */
final class OneBot11Codec {

    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[A-Za-z0-9_.-]+(?:,[^\\]]*)?]");

    OneBot11Codec() {
    }

    /**
     * 解析消息事件。
     *
     * @param adapterId 适配器标识
     * @param event     已解析的 OneBot 事件 JSON
     * @param fallbackTime 事件缺少合法时间戳时使用的接收时间
     * @return 协议字段完整且包含文本时返回中立消息，否则空
     */
    Optional<InboundMessage> decodeMessage(
            String adapterId, JsonNode event, Instant fallbackTime) {
        if (adapterId == null || adapterId.isBlank() || event == null || fallbackTime == null) {
            return Optional.empty();
        }
        if (!"message".equals(text(event, "post_type"))) {
            return Optional.empty();
        }

        SessionType sessionType;
        String messageType = text(event, "message_type");
        if ("private".equals(messageType)) {
            sessionType = SessionType.PRIVATE;
        } else if ("group".equals(messageType)) {
            sessionType = SessionType.GROUP;
        } else {
            return Optional.empty();
        }

        String selfId = scalar(event.get("self_id"));
        String messageId = scalar(event.get("message_id"));
        String userId = scalar(event.get("user_id"));
        String groupId = sessionType == SessionType.GROUP ? scalar(event.get("group_id")) : null;
        if (selfId == null || messageId == null || userId == null
                || (sessionType == SessionType.GROUP && groupId == null)) {
            return Optional.empty();
        }

        String messageText = extractText(event.get("message"));
        if (messageText.isEmpty()) {
            return Optional.empty();
        }

        Instant receivedAt = eventTime(event, fallbackTime);
        String dedupeKey = dedupeKey(selfId, sessionType, groupId, messageId);
        return Optional.of(new InboundMessage(
                adapterId,
                selfId,
                messageId,
                userId,
                sessionType,
                groupId,
                messageText,
                receivedAt,
                dedupeKey));
    }

    /** 生成稳定去重键：selfId + messageId；群消息附加 groupId。 */
    static String dedupeKey(
            String selfId, SessionType sessionType, String groupId, String messageId) {
        if (sessionType == SessionType.GROUP) {
            return "self:" + selfId + ":group:" + groupId + ":msg:" + messageId;
        }
        return "self:" + selfId + ":private:msg:" + messageId;
    }

    /** 提取 message 数组中的 text 段，或解码 CQ 字符串中的文本。 */
    static String extractText(JsonNode message) {
        if (message == null || message.isNull()) {
            return "";
        }
        if (message.isTextual()) {
            return stripCqCodes(message.asText());
        }
        if (message.isObject()) {
            return textFromSegment(message);
        }
        if (!message.isArray()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (JsonNode segment : message) {
            if (segment != null && segment.isObject()) {
                out.append(textFromSegment(segment));
            }
        }
        return out.toString();
    }

    private static String textFromSegment(JsonNode segment) {
        String type = text(segment, "type");
        if (!"text".equals(type)) {
            return "";
        }
        JsonNode data = segment.get("data");
        if (data == null || data.isNull()) {
            return "";
        }
        JsonNode textNode = data.get("text");
        if (textNode == null || textNode.isNull()) {
            return "";
        }
        return textNode.isTextual() ? textNode.asText() : "";
    }

    private static String stripCqCodes(String raw) {
        String withoutCodes = CQ_CODE.matcher(raw).replaceAll("");
        return withoutCodes
                .replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#44;", ",")
                .replace("&amp;", "&");
    }

    private static Instant eventTime(JsonNode event, Instant fallback) {
        JsonNode time = event.get("time");
        if (time != null && time.isIntegralNumber()) {
            try {
                long epochSeconds = time.asLong();
                if (epochSeconds > 0) {
                    return Instant.ofEpochSecond(epochSeconds);
                }
            } catch (RuntimeException ignored) {
                // 数值超出 Instant 范围时使用接收时间。
            }
        }
        return fallback;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String result = value.isTextual() ? value.asText() : value.asText();
        return result == null || result.isBlank() ? null : result;
    }
}
