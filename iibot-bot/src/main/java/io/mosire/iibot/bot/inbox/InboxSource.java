package io.mosire.iibot.bot.inbox;

import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.connect.core.MessageSourceAddress;

import java.util.Objects;

/**
 * Inbox 未读条目的来源元数据。
 *
 * @param threadId   内部 thread id（旧版兼容）
 * @param chatType   GROUP / PRIVATE
 * @param targetId   群号或私聊对方 QQ
 * @param displayName 群名/私聊展示名；可空串
 * @param source     统一消息来源地址；旧数据可自动从 threadId 推导
 */
public record InboxSource(String threadId, String chatType, String targetId,
                          String displayName, String source) {

    public InboxSource {
        threadId = requireText(threadId, "threadId");
        chatType = requireText(chatType, "chatType");
        targetId = requireText(targetId, "targetId");
        displayName = displayName == null ? "" : displayName.trim();
        source = source == null || source.isBlank()
                ? deriveSource(threadId) : MessageSourceAddress.parse(source).canonical();
    }

    /** 兼容旧构造：source 从旧 threadId 推导，推导失败保留空串。 */
    public InboxSource(String threadId, String chatType, String targetId,
                       String displayName) {
        this(threadId, chatType, targetId, displayName, "");
    }

    private static String deriveSource(String threadId) {
        try {
            return ChatSourceKey.fromThreadId(threadId).sourceAddress().canonical();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
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
