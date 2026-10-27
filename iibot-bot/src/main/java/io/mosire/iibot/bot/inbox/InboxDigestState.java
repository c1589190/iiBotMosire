package io.mosire.iibot.bot.inbox;

import java.util.Objects;

/**
 * 一个 thread 的 Inbox digest 游标。
 *
 * <p>{@code itemId} 为空表示只有游标、没有 active item；此时 {@code revision=0}。
 * 该形状允许 Bot 自己的出站消息推进 lastSeq 而不产生自我唤醒。</p>
 */
public record InboxDigestState(String threadId, String itemId, long revision, long lastSeq) {

    public InboxDigestState {
        threadId = requireText(threadId, "threadId");
        itemId = itemId == null ? "" : itemId.trim();
        if (lastSeq < 0L) {
            throw new IllegalArgumentException("lastSeq 不能为负");
        }
        if (itemId.isEmpty() && revision != 0L) {
            throw new IllegalArgumentException("无 active item 时 revision 必须为 0");
        }
        if (!itemId.isEmpty() && revision <= 0L) {
            throw new IllegalArgumentException("active item 的 revision 必须为正");
        }
    }

    public static InboxDigestState cursorOnly(String threadId, long lastSeq) {
        return new InboxDigestState(threadId, "", 0L, lastSeq);
    }

    public boolean hasActiveItem() {
        return !itemId.isEmpty();
    }

    public InboxDigestState withItem(Item item, long newLastSeq) {
        Objects.requireNonNull(item, "item");
        return new InboxDigestState(threadId, item.itemId(), item.revision(), newLastSeq);
    }

    public InboxDigestState withLastSeq(long newLastSeq) {
        return new InboxDigestState(threadId, itemId, revision, newLastSeq);
    }

    /** 只用于与 Inbox 返回对象组合的内部形状。 */
    public record Item(String itemId, long revision) {
        public Item {
            itemId = requireText(itemId, "itemId");
            if (revision <= 0L) {
                throw new IllegalArgumentException("revision 必须为正");
            }
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
