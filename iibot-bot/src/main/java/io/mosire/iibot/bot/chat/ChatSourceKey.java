package io.mosire.iibot.bot.chat;

import io.mosire.iibot.connect.core.MessageSourceAddress;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 一个消息板会话来源：Bot 账号 + 来源类型 + 目标 + 统一 source。
 *
 * <p>QQ 私聊/群聊继续使用与历史数据兼容的 targetId 和 threadId；非 QQ 平台使用
 * {@link ChatType#EXTERNAL}，targetId 保存 canonical source。threadId 对外仍是不透明、
 * 稳定的来源键，不用于文件路径。</p>
 */
public record ChatSourceKey(String accountId, ChatType chatType, String targetId,
                            MessageSourceAddress sourceAddress) {

    public ChatSourceKey {
        accountId = requireText(accountId, "accountId");
        chatType = Objects.requireNonNull(chatType, "chatType");
        if (chatType == ChatType.EXTERNAL) {
            MessageSourceAddress parsed = sourceAddress == null
                    ? MessageSourceAddress.parse(requireText(targetId, "targetId"))
                    : sourceAddress;
            if (!parsed.isExternalConversation()) {
                throw new IllegalArgumentException(
                        "external key 必须使用非 QQ、非系统 source: " + parsed.canonical());
            }
            if (!accountId.equals(parsed.accountId())) {
                throw new IllegalArgumentException("accountId 与 external source 不一致");
            }
            sourceAddress = parsed;
            targetId = parsed.canonical();
        } else {
            targetId = requireText(targetId, "targetId");
            MessageSourceAddress derived = switch (chatType) {
                case GROUP -> MessageSourceAddress.qqGroup(accountId, targetId);
                case PRIVATE -> MessageSourceAddress.qqPrivate(accountId, targetId);
                case EXTERNAL -> throw new IllegalStateException("unreachable");
            };
            if (sourceAddress != null && !sourceAddress.equals(derived)) {
                throw new IllegalArgumentException("QQ chatType/targetId 与 source 不一致");
            }
            sourceAddress = derived;
        }
    }

    /** 兼容旧构造：source 由 chatType/targetId 推导；external 时 targetId 是 canonical source。 */
    public ChatSourceKey(String accountId, ChatType chatType, String targetId) {
        this(accountId, chatType, targetId, null);
    }

    /** 私聊来源。 */
    public static ChatSourceKey privateChat(String accountId, String peerQq) {
        return new ChatSourceKey(accountId, ChatType.PRIVATE, peerQq);
    }

    /** 群聊来源。 */
    public static ChatSourceKey groupChat(String accountId, String groupId) {
        return new ChatSourceKey(accountId, ChatType.GROUP, groupId);
    }

    /** 非 QQ 统一来源；accountId 必须与 source 首段一致。 */
    public static ChatSourceKey external(MessageSourceAddress source) {
        Objects.requireNonNull(source, "source");
        return new ChatSourceKey(source.accountId(), ChatType.EXTERNAL,
                source.canonical(), source);
    }

    /** 兼容旧数据的内部 thread 键；新对外契约使用 {@link #sourceAddress()}。 */
    public String threadId() {
        if (chatType == ChatType.EXTERNAL) {
            return "thread:external:" + encode(accountId) + ":"
                    + encode(sourceAddress.canonical());
        }
        return "thread:" + chatType.wire() + ":" + encode(accountId) + ":" + encode(targetId);
    }

    /** 从统一 source 还原消息板 thread key；系统来源不允许调用。 */
    public static ChatSourceKey fromSourceAddress(MessageSourceAddress source) {
        Objects.requireNonNull(source, "source");
        if (source.isQqGroup()) {
            return groupChat(source.accountId(), source.targetId());
        }
        if (source.isQqPrivate()) {
            return privateChat(source.accountId(), source.targetId());
        }
        if (source.isExternalConversation()) {
            return external(source);
        }
        throw new IllegalArgumentException(
                "系统来源不能映射为消息板 thread: " + source.canonical());
    }

    /** 解码 threadId；兼容旧 QQ 格式和 external canonical source 格式。 */
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
