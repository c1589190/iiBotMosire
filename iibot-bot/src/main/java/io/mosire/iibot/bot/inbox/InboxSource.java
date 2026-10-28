package io.mosire.iibot.bot.inbox;

import java.util.Objects;

/**
 * Inbox 未读条目的来源元数据。
 *
 * @param threadId   内部 thread id
 * @param chatType   GROUP / PRIVATE
 * @param targetId   群号或私聊对方 QQ
 * @param displayName 群名/私聊展示名；可空串
 */
public record InboxSource(String threadId, String chatType, String targetId,
                          String displayName) {

    public InboxSource {
        threadId = requireText(threadId, "threadId");
        chatType = requireText(chatType, "chatType");
        targetId = requireText(targetId, "targetId");
        displayName = displayName == null ? "" : displayName.trim();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
