package io.mosire.iibot.bot.chat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    /** 不透明的稳定 thread 键。 */
    public String threadId() {
        return "thread:" + chatType.wire() + ":" + encode(accountId) + ":" + encode(targetId);
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
