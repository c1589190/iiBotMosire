package io.mosire.iibot.bot.inbox;

import java.util.Optional;

/**
 * Inbox 来源大框端口。
 *
 * <p>实现负责把 {@link InboxDigest} 编码成 Inbox 的 summary/content；core 不直接依赖
 * InboxMosire 客户端。</p>
 */
public interface InboxDigestPort {

    /** 新建或按去重键复用 item。 */
    InboxDigestItem push(String dedupeKey, InboxDigest digest);

    /** 读取 item 当前状态；item 不存在时为空。 */
    Optional<InboxDigestItem> view(String itemId);

    /**
     * 替换 available item 的摘要/content。
     *
     * @throws InboxDigestConflictException revision/状态冲突
     */
    InboxDigestItem update(String itemId, long expectedRevision, InboxDigest digest);

    /** 只更新 available item 的优先级；返回新 revision。 */
    default long updatePriority(String itemId, long expectedRevision, int priority) {
        return expectedRevision + 1L;
    }

    /** 归档 available item。 */
    void archive(String itemId, long expectedRevision);
}
