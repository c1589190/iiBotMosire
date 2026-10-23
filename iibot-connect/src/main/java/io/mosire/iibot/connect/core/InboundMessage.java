package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 协议无关的纯文本入站消息。
 *
 * @param adapterId  产生消息的适配器实例标识
 * @param selfId     接收消息的 Bot 账号标识
 * @param messageId  协议消息标识（统一为字符串，避免不同实现的数值范围差异）
 * @param userId     发送者标识
 * @param sessionType 私聊或群聊
 * @param groupId    群聊时的群标识；私聊时为 null
 * @param text       提取出的纯文本，不包含 OneBot 原始 JSON/CQ Code
 * @param receivedAt 事件接收/解析时间
 * @param dedupeKey  稳定的去重键
 */
public record InboundMessage(
        String adapterId,
        String selfId,
        String messageId,
        String userId,
        SessionType sessionType,
        String groupId,
        String text,
        Instant receivedAt,
        String dedupeKey) {

    /** 会话类型。 */
    public enum SessionType {
        PRIVATE,
        GROUP
    }

    public InboundMessage {
        adapterId = requireText(adapterId, "adapterId");
        selfId = requireText(selfId, "selfId");
        messageId = requireText(messageId, "messageId");
        userId = requireText(userId, "userId");
        sessionType = Objects.requireNonNull(sessionType, "sessionType");
        text = Objects.requireNonNull(text, "text");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        dedupeKey = requireText(dedupeKey, "dedupeKey");
        if (sessionType == SessionType.GROUP) {
            groupId = requireText(groupId, "groupId");
        } else if (groupId != null) {
            throw new IllegalArgumentException("私聊消息不能携带 groupId");
        }
    }

    /** 接收消息的 Bot 账号标识；与 {@link #selfId()} 等价。 */
    public String accountId() {
        return selfId;
    }

    /** 群标识的可选视图。 */
    public Optional<String> optionalGroupId() {
        return Optional.ofNullable(groupId);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
