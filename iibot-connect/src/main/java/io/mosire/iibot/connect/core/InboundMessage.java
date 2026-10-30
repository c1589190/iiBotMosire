package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 协议无关的入站消息。
 *
 * <p>{@code text} 只保留纯文本；非文本消息段不下载资源，只汇总成
 * {@code nonTextSummary}（例如 {@code [图片×1][文件×1]}）。即使没有文本，
 * 只要有非文本段，消息仍应入站。</p>
 *
 * <p>QQ Adapter 继续填写 {@code sessionType/groupId}；非 QQ 平台填写
 * {@link #sourceAddress()}，并使用 {@link SessionType#EXTERNAL}。上层优先使用统一
 * source 进入消息板。</p>
 *
 * @param adapterId      产生消息的适配器实例标识
 * @param selfId         接收消息的 Bot 账号标识
 * @param messageId      协议消息标识（统一为字符串，避免不同实现的数值范围差异）
 * @param userId         发送者标识
 * @param senderName     发送者展示名/群名片；未知时可由适配器回退到 userId
 * @param sessionType    私聊、群聊或外部平台会话
 * @param groupId        群聊时的群标识；私聊/外部会话为 null
 * @param text           提取出的纯文本，不包含 OneBot 原始 JSON/CQ Code
 * @param nonTextSummary 非文本段的有界占位摘要；无则空串
 * @param replyToMessageId 被引用消息 ID；无则空串
 * @param mentionedSelf  群聊消息是否 @Bot
 * @param receivedAt     事件接收/解析时间
 * @param dedupeKey      稳定的去重键
 * @param segments       有序消息段；图片/转发/引用等恢复所需元数据
 * @param sourceAddress  可选统一来源地址；外部平台必须提供
 */
public record InboundMessage(
        String adapterId,
        String selfId,
        String messageId,
        String userId,
        String senderName,
        SessionType sessionType,
        String groupId,
        String text,
        String nonTextSummary,
        String replyToMessageId,
        boolean mentionedSelf,
        Instant receivedAt,
        String dedupeKey,
        java.util.List<MessageSegment> segments,
        MessageSourceAddress sourceAddress) {

    /** 会话类型。 */
    public enum SessionType {
        PRIVATE,
        GROUP,
        EXTERNAL
    }

    public InboundMessage {
        adapterId = requireText(adapterId, "adapterId");
        selfId = requireText(selfId, "selfId");
        messageId = requireText(messageId, "messageId");
        userId = requireText(userId, "userId");
        senderName = optionalText(senderName);
        sessionType = Objects.requireNonNull(sessionType, "sessionType");
        text = Objects.requireNonNull(text, "text");
        nonTextSummary = optionalText(nonTextSummary);
        replyToMessageId = optionalText(replyToMessageId);
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        dedupeKey = requireText(dedupeKey, "dedupeKey");
        segments = segments == null ? java.util.List.of() : java.util.List.copyOf(segments);
        if (sessionType == SessionType.GROUP) {
            groupId = requireText(groupId, "groupId");
        } else if (groupId != null) {
            throw new IllegalArgumentException("非群聊消息不能携带 groupId");
        }
        if (sessionType == SessionType.EXTERNAL) {
            sourceAddress = Objects.requireNonNull(sourceAddress,
                    "EXTERNAL 消息必须携带 sourceAddress");
        }
        if (sourceAddress != null && !selfId.equals(sourceAddress.accountId())) {
            throw new IllegalArgumentException("selfId 与 sourceAddress.accountId 不一致");
        }
    }

    /** 兼容加入 sourceAddress 之前的 14 参构造。 */
    public InboundMessage(String adapterId, String selfId, String messageId, String userId,
                          String senderName, SessionType sessionType, String groupId,
                          String text, String nonTextSummary, String replyToMessageId,
                          boolean mentionedSelf, Instant receivedAt, String dedupeKey,
                          java.util.List<MessageSegment> segments) {
        this(adapterId, selfId, messageId, userId, senderName, sessionType, groupId, text,
                nonTextSummary, replyToMessageId, mentionedSelf, receivedAt, dedupeKey,
                segments, null);
    }

    /**
     * 兼容旧构造：发送者展示名回退到 userId，其余新增字段为空/false。
     */
    public InboundMessage(String adapterId, String selfId, String messageId, String userId,
                          SessionType sessionType, String groupId, String text,
                          Instant receivedAt, String dedupeKey) {
        this(adapterId, selfId, messageId, userId, userId, sessionType, groupId, text,
                "", "", false, receivedAt, dedupeKey, java.util.List.of(), null);
    }

    /** 兼容 13 参构造：调用方尚未解析消息段。 */
    public InboundMessage(String adapterId, String selfId, String messageId, String userId,
                          String senderName, SessionType sessionType, String groupId,
                          String text, String nonTextSummary, String replyToMessageId,
                          boolean mentionedSelf, Instant receivedAt, String dedupeKey) {
        this(adapterId, selfId, messageId, userId, senderName, sessionType, groupId, text,
                nonTextSummary, replyToMessageId, mentionedSelf, receivedAt, dedupeKey,
                java.util.List.of(), null);
    }

    /** 接收消息的 Bot 账号标识；与 {@link #selfId()} 等价。 */
    public String accountId() {
        return selfId;
    }

    /** 群标识的可选视图。 */
    public Optional<String> optionalGroupId() {
        return Optional.ofNullable(groupId);
    }

    /** 统一来源地址的可选视图。 */
    public Optional<MessageSourceAddress> optionalSourceAddress() {
        return Optional.ofNullable(sourceAddress);
    }

    /** 被引用消息 ID 的可选视图。 */
    public Optional<String> optionalReplyToMessageId() {
        return replyToMessageId.isEmpty() ? Optional.empty() : Optional.of(replyToMessageId);
    }

    /** 是否有任何可展示的文本或非文本占位。 */
    public boolean hasDisplayableContent() {
        return !text.isBlank() || !nonTextSummary.isBlank();
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
