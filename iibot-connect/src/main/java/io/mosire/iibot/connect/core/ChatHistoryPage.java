package io.mosire.iibot.connect.core;

import java.util.List;

/**
 * 一页聊天历史。
 *
 * @param messages       按协议返回顺序排列的文本消息
 * @param nextCursor     继续向同一方向翻页的 {@code message_seq}；没有更多时为 null
 * @param exhausted      当前方向是否已到底
 * @param nonTextSkipped 本页跳过的非文本消息/消息段数量
 */
public record ChatHistoryPage(
        List<ChatHistoryMessage> messages,
        String nextCursor,
        boolean exhausted,
        int nonTextSkipped) {

    public ChatHistoryPage {
        messages = List.copyOf(messages);
        if (nextCursor != null && nextCursor.isBlank()) {
            nextCursor = null;
        }
        if (exhausted) {
            nextCursor = null;
        }
        if (nonTextSkipped < 0) {
            throw new IllegalArgumentException("nonTextSkipped 不能为负");
        }
    }

    /** 兼容旧三参构造：非文本计数为 0。 */
    public ChatHistoryPage(List<ChatHistoryMessage> messages, String nextCursor,
                           boolean exhausted) {
        this(messages, nextCursor, exhausted, 0);
    }

    public static ChatHistoryPage empty() {
        return new ChatHistoryPage(List.of(), null, true, 0);
    }
}
