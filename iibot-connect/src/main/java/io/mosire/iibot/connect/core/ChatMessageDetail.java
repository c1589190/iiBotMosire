package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;

/**
 * 按消息 ID 定位到的一条 QQ 消息；用于验证入团来源消息等控制面场景。
 *
 * @param messageId 消息 ID
 * @param sessionType 私聊或群聊
 * @param targetId 群号或私聊对象 QQ
 * @param senderId 发送者 QQ
 * @param senderName 发送者昵称/群名片
 * @param text 提取出的纯文本；没有文本时为空串
 * @param sentAt 发送时间；未知时为 null
 * @param nonTextSegments 非文本段数量
 */
public record ChatMessageDetail(
        String messageId,
        InboundMessage.SessionType sessionType,
        String targetId,
        String senderId,
        String senderName,
        String text,
        Instant sentAt,
        int nonTextSegments) {

    public ChatMessageDetail {
        messageId = requireText(messageId, "messageId");
        sessionType = Objects.requireNonNull(sessionType, "sessionType");
        targetId = requireText(targetId, "targetId");
        senderId = requireText(senderId, "senderId");
        senderName = senderName == null ? "" : senderName;
        text = text == null ? "" : text;
        if (nonTextSegments < 0) {
            throw new IllegalArgumentException("nonTextSegments 不能为负");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
