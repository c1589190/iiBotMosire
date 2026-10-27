package io.mosire.iibot.connect.napcat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.iibot.connect.core.FriendRequestEvent;
import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.MessageSegment;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OneBot 11 消息事件到中立 {@link InboundMessage} 的解析器。
 *
 * <p>该类只解析 post_type=message 且 message_type 为 private/group 的事件；
 * meta_event/notice/request 及未知事件返回空。非文本段不下载资源，只汇总为
 * 类型/数量占位；纯非文本消息也会被保留。</p>
 */
final class OneBot11Codec {

    private static final Pattern CQ_CODE =
            Pattern.compile("\\[CQ:([A-Za-z0-9_.-]+)((?:,[^\\]]*)?)]");

    OneBot11Codec() {
    }

    /**
     * 解析消息事件。
     *
     * @param adapterId 适配器标识
     * @param event     已解析的 OneBot 事件 JSON
     * @param fallbackTime 事件缺少合法时间戳时使用的接收时间
     * @return 协议字段完整且至少有文本或非文本占位时返回中立消息，否则空
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

        DecodedContent content = decodeContent(event.get("message"), selfId);
        if (!content.hasDisplayableContent()) {
            return Optional.empty();
        }

        Instant receivedAt = eventTime(event, fallbackTime);
        String dedupeKey = dedupeKey(selfId, sessionType, groupId, messageId);
        return Optional.of(new InboundMessage(
                adapterId,
                selfId,
                messageId,
                userId,
                senderName(event.get("sender"), userId),
                sessionType,
                groupId,
                content.text(),
                content.nonTextSummary(),
                content.replyToMessageId(),
                content.mentionedSelf(),
                receivedAt,
                dedupeKey,
                content.segments()));
    }

    /** 生成稳定去重键：selfId + messageId；群消息附加 groupId。 */
    static String dedupeKey(
            String selfId, SessionType sessionType, String groupId, String messageId) {
        if (sessionType == SessionType.GROUP) {
            return "self:" + selfId + ":group:" + groupId + ":msg:" + messageId;
        }
        return "self:" + selfId + ":private:msg:" + messageId;
    }

    /** 解析 post_type=request / request_type=friend 事件。 */
    Optional<FriendRequestEvent> decodeFriendRequest(JsonNode event, Instant fallbackTime) {
        if (event == null || !"request".equals(text(event, "post_type"))
                || !"friend".equals(text(event, "request_type"))) {
            return Optional.empty();
        }
        String flag = scalar(event.get("flag"));
        String userId = scalar(event.get("user_id"));
        if (flag == null || userId == null) {
            return Optional.empty();
        }
        JsonNode time = event.get("time");
        Instant receivedAt = fallbackTime;
        if (time != null && time.isIntegralNumber() && time.asLong() > 0) {
            try {
                receivedAt = Instant.ofEpochSecond(time.asLong());
            } catch (RuntimeException ignored) {
                // 使用接收时间。
            }
        }
        return Optional.of(new FriendRequestEvent(flag, userId,
                scalar(event.get("nickname")), scalar(event.get("comment")), receivedAt));
    }

    /** 提取 message 数组中的 text 段，或解码 CQ 字符串中的文本；保留旧调用面。 */
    static String extractText(JsonNode message) {
        return decodeContent(message, null).text();
    }

    /** 提取协议中立消息段；供历史/转发解析复用。 */
    static List<MessageSegment> extractSegments(JsonNode message) {
        return decodeContent(message, null).segments();
    }

    private static DecodedContent decodeContent(JsonNode message, String selfId) {
        DecodedContentBuilder builder = new DecodedContentBuilder();
        if (message == null || message.isNull()) {
            return builder.build();
        }
        if (message.isTextual()) {
            decodeCqString(message.asText(), selfId, builder);
            return builder.build();
        }
        if (message.isObject()) {
            appendSegment(message, selfId, builder);
            return builder.build();
        }
        if (!message.isArray()) {
            return builder.build();
        }
        for (JsonNode segment : message) {
            if (segment != null && segment.isObject()) {
                appendSegment(segment, selfId, builder);
            }
        }
        return builder.build();
    }

    private static void decodeCqString(String raw, String selfId, DecodedContentBuilder builder) {
        Matcher matcher = CQ_CODE.matcher(raw);
        int last = 0;
        while (matcher.find()) {
            builder.appendText(decodeEntities(raw.substring(last, matcher.start())));
            String type = matcher.group(1);
            Map<String, String> params = parseCqParams(matcher.group(2));
            if ("at".equals(type)) {
                builder.addMention(params.get("qq"), selfId);
            } else if ("reply".equals(type)) {
                builder.addReply(params.get("id"));
            } else if ("image".equals(type)) {
                builder.addImage(params.get("file"), params.get("url"), params.get("file_size"));
            } else if ("forward".equals(type)) {
                builder.addForward(params.get("id"));
            } else {
                builder.addOther(type);
            }
            last = matcher.end();
        }
        builder.appendText(decodeEntities(raw.substring(last)));
    }

    private static void appendSegment(JsonNode segment, String selfId,
                                      DecodedContentBuilder builder) {
        String type = text(segment, "type");
        if (type == null) {
            return;
        }
        JsonNode data = segment.get("data");
        if ("text".equals(type)) {
            builder.appendText(text(data, "text"));
        } else if ("at".equals(type)) {
            builder.addMention(text(data, "qq"), selfId);
        } else if ("reply".equals(type)) {
            builder.addReply(text(data, "id"));
        } else if ("image".equals(type)) {
            builder.addImage(text(data, "file"), text(data, "url"),
                    text(data, "file_size"));
        } else if ("forward".equals(type)) {
            builder.addForward(text(data, "id"));
        } else {
            builder.addOther(type);
        }
    }

    private static Map<String, String> parseCqParams(String params) {
        if (params == null || params.isBlank()) {
            return Map.of();
        }
        String body = params.startsWith(",") ? params.substring(1) : params;
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : body.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1);
            if (!key.isEmpty()) {
                result.put(key, decodeEntities(value));
            }
        }
        return result;
    }

    private static String senderName(JsonNode sender, String fallback) {
        if (sender != null && sender.isObject()) {
            String card = scalar(sender.get("card"));
            if (card != null) {
                return card;
            }
            String nickname = scalar(sender.get("nickname"));
            if (nickname != null) {
                return nickname;
            }
        }
        return fallback;
    }

    private static String nonTextLabel(String type) {
        return switch (type) {
            case "image" -> "图片";
            case "file" -> "文件";
            case "record" -> "语音";
            case "video" -> "视频";
            case "face", "mface" -> "表情";
            case "at" -> "@";
            case "reply" -> "引用";
            case "forward" -> "转发";
            case "json", "xml" -> "卡片";
            case "dice", "rps" -> "随机";
            default -> "其他";
        };
    }

    private static String decodeEntities(String raw) {
        return raw
                .replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#44;", ",")
                .replace("&#39;", "'")
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
        String result = value.asText();
        return result == null || result.isBlank() ? null : result;
    }

    private record DecodedContent(
            String text,
            String nonTextSummary,
            String replyToMessageId,
            boolean mentionedSelf,
            List<MessageSegment> segments) {

        DecodedContent {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }

        boolean hasDisplayableContent() {
            return !text.isBlank() || !nonTextSummary.isBlank();
        }
    }

    /** 只在本解码器内使用的有序非文本段累加器。 */
    private static final class DecodedContentBuilder {
        private final StringBuilder text = new StringBuilder();
        private final Map<String, Integer> nonTextCounts = new LinkedHashMap<>();
        private final List<MessageSegment> segments = new ArrayList<>();
        private String replyToMessageId = "";
        private boolean mentionedSelf;

        void appendText(String value) {
            if (value != null && !value.isEmpty()) {
                text.append(value);
                segments.add(MessageSegment.text(segments.size(), value));
            }
        }

        void addMention(String qq, String selfId) {
            addNonText("@");
            markMention(qq, selfId);
            segments.add(MessageSegment.mention(segments.size(), qq));
        }

        void addReply(String messageId) {
            addNonText("引用");
            markReply(messageId);
            segments.add(MessageSegment.reply(segments.size(), messageId));
        }

        void addImage(String file, String url, String fileSize) {
            addNonText("图片");
            segments.add(MessageSegment.image(segments.size(), file, url, "",
                    parseSize(fileSize)));
        }

        void addForward(String id) {
            addNonText("转发");
            segments.add(MessageSegment.forward(segments.size(), id));
        }

        void addOther(String type) {
            addNonText(nonTextLabel(type));
            segments.add(new MessageSegment(segments.size(), MessageSegment.Type.OTHER,
                    "", "", "", "", "", "", 0L, "", type));
        }

        void addNonText(String label) {
            nonTextCounts.merge(label, 1, Integer::sum);
        }

        void markReply(String messageId) {
            if (replyToMessageId.isEmpty() && messageId != null && !messageId.isBlank()) {
                replyToMessageId = messageId.trim();
            }
        }

        void markMention(String qq, String selfId) {
            if (!mentionedSelf && qq != null && selfId != null && qq.trim().equals(selfId)) {
                mentionedSelf = true;
            }
        }

        DecodedContent build() {
            StringBuilder summary = new StringBuilder();
            nonTextCounts.forEach((label, count) -> summary
                    .append('[').append(label).append('×').append(count).append(']'));
            return new DecodedContent(text.toString(), summary.toString(), replyToMessageId,
                    mentionedSelf, segments);
        }

        private static long parseSize(String value) {
            if (value == null || value.isBlank()) {
                return 0L;
            }
            try {
                return Math.max(0L, Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}
