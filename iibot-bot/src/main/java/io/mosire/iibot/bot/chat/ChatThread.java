package io.mosire.iibot.bot.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个来源会话的投影。{@code threadId} 来自 {@link ChatSourceKey#threadId()}。
 *
 * @param source       来源
 * @param displayName  群名/当前私聊展示名；可空串
 * @param lastSeq      本 thread 最新消息的 localSeq
 * @param messageCount 本 thread 的消息条数
 * @param lastMessageAt 最新消息的本机接收时间
 */
public record ChatThread(
        ChatSourceKey source,
        String displayName,
        long lastSeq,
        long messageCount,
        Instant lastMessageAt) {

    public ChatThread {
        source = Objects.requireNonNull(source, "source");
        displayName = displayName == null ? "" : displayName.trim();
        if (lastSeq < 0L) {
            throw new IllegalArgumentException("lastSeq 不能为负");
        }
        if (messageCount < 0L) {
            throw new IllegalArgumentException("messageCount 不能为负");
        }
    }

    /** 不透明来源 ID。 */
    public String threadId() {
        return source.threadId();
    }

    /** 创建空 thread 投影。 */
    public static ChatThread empty(ChatSourceKey source) {
        return new ChatThread(source, "", 0L, 0L, null);
    }

    /** 追加一条已入库消息后的新投影。 */
    public ChatThread after(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        if (!source.equals(message.source())) {
            throw new IllegalArgumentException("message 不属于该 thread");
        }
        if (message.localSeq() <= lastSeq) {
            throw new IllegalArgumentException("message.localSeq 必须大于 thread.lastSeq");
        }
        return new ChatThread(source, displayName, message.localSeq(), messageCount + 1L,
                message.receivedAt());
    }
}
