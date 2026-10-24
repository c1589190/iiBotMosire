package io.mosire.iibot.connect.core;

import io.mosire.iibot.connect.core.InboundMessage.SessionType;

import java.util.Objects;

/**
 * 协议中立的聊天历史查询。
 *
 * @param sessionType 私聊或群聊
 * @param targetId    私聊 QQ 号或群号
 * @param cursor      可选 {@code message_seq} 游标；null/空白表示从最新消息开始
 * @param count       单页条数，1–100
 * @param reverseOrder true 表示从游标向过去翻页，false 表示向现在方向
 */
public record ChatHistoryQuery(
        SessionType sessionType,
        String targetId,
        String cursor,
        int count,
        boolean reverseOrder) {

    public ChatHistoryQuery {
        sessionType = Objects.requireNonNull(sessionType, "sessionType");
        targetId = requireText(targetId, "targetId");
        if (cursor != null) {
            cursor = cursor.trim();
            if (cursor.isEmpty()) {
                cursor = null;
            }
        }
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("count 必须在 1–100 之间");
        }
    }

    public static ChatHistoryQuery latest(SessionType sessionType, String targetId, int count) {
        return new ChatHistoryQuery(sessionType, targetId, null, count, true);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
