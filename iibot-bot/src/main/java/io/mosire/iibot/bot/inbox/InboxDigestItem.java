package io.mosire.iibot.bot.inbox;

/** Inbox 侧一条 digest item 的最小状态视图；{@code digest} 为可回读的 content 投影。 */
public record InboxDigestItem(String itemId, long revision, String status,
                              long toSeq, long fromSeq, int priority,
                              InboxDigest digest) {

    public InboxDigestItem(String itemId, long revision, String status) {
        this(itemId, revision, status, 0L, 0L, 0, null);
    }

    public InboxDigestItem(String itemId, long revision, String status, long toSeq) {
        this(itemId, revision, status, toSeq, 0L, 0, null);
    }

    public InboxDigestItem(String itemId, long revision, String status,
                           long toSeq, long fromSeq) {
        this(itemId, revision, status, toSeq, fromSeq, 0, null);
    }

    public InboxDigestItem(String itemId, long revision, String status,
                           long toSeq, long fromSeq, int priority) {
        this(itemId, revision, status, toSeq, fromSeq, priority, null);
    }

    public InboxDigestItem {
        itemId = requireText(itemId, "itemId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision 必须为正");
        }
        status = status == null ? "" : status.trim();
        if (toSeq < 0L) {
            throw new IllegalArgumentException("toSeq 不能为负");
        }
        if (fromSeq < 0L) {
            throw new IllegalArgumentException("fromSeq 不能为负");
        }
        if (fromSeq > 0L && toSeq > 0L && fromSeq > toSeq) {
            throw new IllegalArgumentException("fromSeq 不能大于 toSeq");
        }
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
    }

    public boolean available() {
        return "available".equalsIgnoreCase(status);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
