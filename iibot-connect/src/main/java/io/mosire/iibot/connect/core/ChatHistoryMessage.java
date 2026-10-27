package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条由宿主展示的 QQ 文本历史。
 *
 * @param messageId  协议消息 ID，统一为字符串
 * @param senderId   发送者 QQ 号
 * @param senderName 可展示的昵称/群名片；未知时可为空串
 * @param text       提取出的纯文本；不含 CQ Code
 * @param sentAt     消息时间；无法解析时为 null
 * @param segments   有序消息段；图片/转发等元数据
 */
public record ChatHistoryMessage(
        String messageId,
        String senderId,
        String senderName,
        String text,
        Instant sentAt,
        java.util.List<io.mosire.iibot.connect.core.MessageSegment> segments) {

    public ChatHistoryMessage {
        messageId = requireText(messageId, "messageId");
        senderId = requireText(senderId, "senderId");
        senderName = senderName == null ? "" : senderName;
        text = Objects.requireNonNull(text, "text");
        segments = segments == null ? java.util.List.of() : java.util.List.copyOf(segments);
        if (text.isBlank() && segments.isEmpty()) {
            throw new IllegalArgumentException("text 和 segments 不能同时为空");
        }
    }

    /** 兼容无消息段的 5 参构造。 */
    public ChatHistoryMessage(String messageId, String senderId, String senderName,
                              String text, Instant sentAt) {
        this(messageId, senderId, senderName, text, sentAt, java.util.List.of());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
