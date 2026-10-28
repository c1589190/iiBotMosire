package io.mosire.iibot.bot.chat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * 一个 QQ 会话来源：Bot 账号 + 私聊/群聊 + 目标。
 *
 * <p>私聊的 targetId 是对方 QQ；群聊的 targetId 是群号。threadId 是不透明、稳定的来源键，
 * 不用于文件路径。同一个 QQ 用户在群内发言不会产生不同的来源 thread。</p>
 */
public record ChatSourceKey(String accountId, ChatType chatType, String targetId) {

    public ChatSourceKey {
        accountId = requireText(accountId, "accountId");
        chatType = Objects.requireNonNull(chatType, "chatType");
        targetId = requireText(targetId, "targetId");
    }

    /** 私聊来源。 */
    public static ChatSourceKey privateChat(String accountId, String peerQq) {
        return new ChatSourceKey(accountId, ChatType.PRIVATE, peerQq);
    }

    /** 群聊来源。 */
    public static ChatSourceKey groupChat(String accountId, String groupId) {
        return new ChatSourceKey(accountId, ChatType.GROUP, groupId);
    }

    /** 公开的统一来源地址。 */
    public MessageSourceAddress sourceAddress() {
        return switch (chatType) {
            case GROUP -> MessageSourceAddress.qqGroup(accountId, targetId);
            case PRIVATE -> MessageSourceAddress.qqPrivate(accountId, targetId);
        };
    }

    /** 兼容旧数据的内部 thread 键；新对外契约使用 {@link #sourceAddress()}。 */
    public String threadId() {
        return "thread:" + chatType.wire() + ":" + encode(accountId) + ":" + encode(targetId);
    }

    /** 从统一 source 还原消息板 thread key；系统来源不允许调用。 */
    public static ChatSourceKey fromSourceAddress(MessageSourceAddress source) {
        Objects.requireNonNull(source, "source");
        return source.toChatSourceKey();
    }

    /** 解码旧版 threadId；用于兼容旧调用方。 */
    public static ChatSourceKey fromThreadId(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        String[] parts = threadId.split(":", -1);
        if (parts.length != 4 || !"thread".equals(parts[0])) {
            throw new IllegalArgumentException("threadId 格式非法");
        }
        ChatType type = ChatType.fromWire(parts[1]);
        String account = decode(parts[2]);
        String target = decode(parts[3]);
        return new ChatSourceKey(account, type, target);
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("threadId Base64 段非法", failure);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
