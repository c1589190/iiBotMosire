package io.mosire.iibot.connect.core;

import java.util.Objects;

/** OneBot 客户端侧最近会话与最后一条消息；不是 Bot Inbox 通知。 */
public record RecentQqMessage(
        InboundMessage.SessionType sessionType,
        String targetId,
        String displayName,
        ChatHistoryMessage lastMessage) {

    public RecentQqMessage {
        sessionType = Objects.requireNonNull(sessionType, "sessionType");
        targetId = requireText(targetId, "targetId");
        displayName = displayName == null ? "" : displayName;
        lastMessage = Objects.requireNonNull(lastMessage, "lastMessage");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
