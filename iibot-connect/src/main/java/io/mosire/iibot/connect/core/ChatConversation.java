package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个 Bot 账号可见的 QQ 会话摘要。
 *
 * <p>只用于目录/定位，不包含消息正文。{@link #targetId()} 在私聊时为 QQ 号，在群聊时为群号。</p>
 *
 * @param sessionType 私聊或群聊
 * @param targetId    私聊 QQ 号或群号
 * @param displayName 可展示的昵称/群名
 * @param remark      备注；未知时为空串
 * @param lastMessageAt 最后一条消息时间；未知时为 null
 * @param lastMessageId 最后一条消息 ID；未知时为 null
 */
public record ChatConversation(
        InboundMessage.SessionType sessionType,
        String targetId,
        String displayName,
        String remark,
        Instant lastMessageAt,
        String lastMessageId) {

    public ChatConversation {
        sessionType = Objects.requireNonNull(sessionType, "sessionType");
        targetId = requireText(targetId, "targetId");
        displayName = displayName == null ? "" : displayName;
        remark = remark == null ? "" : remark;
        if (lastMessageId != null && lastMessageId.isBlank()) {
            lastMessageId = null;
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
