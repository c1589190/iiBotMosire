package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.connect.core.MessageSegment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 消息板持久化端口。
 *
 * <p>入站与出站消息共用 {@link #append(ChatMessage)}；{@code messageKey} 是跨重启唯一键，
 * {@code localSeq} 由实现分配。读取只能按来源 thread，不能把不同来源混在一起。</p>
 */
public interface MessageBoardStore {

    /**
     * 追加一条尚未入库的消息（{@code localSeq=0}）。
     * 重复 {@code messageKey} 返回已有记录且 {@code created=false}。
     */
    MessageAppendResult append(ChatMessage message);

    /**
     * 追加消息及其有序段元数据。默认实现只保存正文，供最小 fake 兼容。
     */
    default MessageAppendResult append(ChatMessage message, List<MessageSegment> segments) {
        return append(message);
    }

    /** 读取消息的协议中立段；默认空。 */
    default List<MessageSegment> segments(String messageKey) {
        return List.of();
    }

    /** 按稳定 messageKey 查找。 */
    Optional<ChatMessage> find(String messageKey);

    /**
     * 更新一条出站消息的投递结果。
     *
     * @param externalMessageId 外部消息 ID；null 表示保留原值
     * @param sentAt            外部发送时间；null 表示保留原值
     */
    ChatMessage updateDelivery(String messageKey, DeliveryState state,
                               String externalMessageId, Instant sentAt);

    /**
     * 读取一个 thread 中 {@code afterSeq} 之后的消息，按 localSeq 升序。
     *
     * @param threadId 来源 thread
     * @param afterSeq 严格大于该 seq；0 表示从头
     * @param limit    1..1000
     */
    List<ChatMessage> readThread(String threadId, long afterSeq, int limit);

    /**
     * 读取一个 thread 中 {@code afterSeq} 之后的入站消息，按 localSeq 升序。
     *
     * <p>Agent read 窗口只按入站消息计数；OUT 消息不能占窗口，也不能让
     * {@code more} 变成假阴性。</p>
     *
     * @param threadId 来源 thread
     * @param afterSeq 严格大于该 seq；0 表示从头
     * @param limit    1..1000
     */
    List<ChatMessage> readInboundThread(String threadId, long afterSeq, int limit);

    /**
     * 统计一个 thread 在 {@code afterSeq} 之后的入站消息数。
     * 默认实现用于最小 fake；SQLite 实现使用精确 COUNT。
     */
    default long countInboundAfter(String threadId, long afterSeq) {
        if (threadId == null || threadId.isBlank() || afterSeq < 0L) {
            throw new IllegalArgumentException("threadId/afterSeq 非法");
        }
        return readThread(threadId, afterSeq, 1000).stream()
                .filter(message -> message.direction() == io.mosire.iibot.bot.chat.ChatDirection.IN)
                .count();
    }

    /**
     * 返回 {@code afterSeq} 之后最后一条入站消息的 localSeq；没有则返回 afterSeq。
     * Inbox item 的 toSeq 必须落在入站边界内，不能被自己的 OUT 消息推后。
     */
    default long lastInboundSeqAfter(String threadId, long afterSeq) {
        if (threadId == null || threadId.isBlank() || afterSeq < 0L) {
            throw new IllegalArgumentException("threadId/afterSeq 非法");
        }
        return readThread(threadId, afterSeq, 1000).stream()
                .filter(message -> message.direction()
                        == io.mosire.iibot.bot.chat.ChatDirection.IN)
                .mapToLong(ChatMessage::localSeq)
                .max()
                .orElse(afterSeq);
    }

    /**
     * 读取 {@code beforeSeq} 之前最近的 {@code limit} 条消息，按 localSeq 升序。
     * 默认实现适合 fake；SQLite 使用索引。
     */
    default List<ChatMessage> readThreadBefore(String threadId, long beforeSeq, int limit) {
        if (threadId == null || threadId.isBlank() || beforeSeq < 0L
                || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("threadId/beforeSeq/limit 非法");
        }
        List<ChatMessage> all = readThread(threadId, 0L, 1000).stream()
                .filter(message -> message.localSeq() < beforeSeq)
                .toList();
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    /**
     * 在 thread 内按 localSeq 精确定位一条消息；不存在返回空。
     *
     * <p>这是 Agent 回复时使用业务序号解析平台 messageId 的服务端入口。</p>
     */
    default Optional<ChatMessage> findByLocalSeq(String threadId, long localSeq) {
        if (threadId == null || threadId.isBlank() || localSeq <= 0L) {
            return Optional.empty();
        }
        return readThread(threadId, localSeq - 1L, 1).stream()
                .filter(message -> message.localSeq() == localSeq)
                .findFirst();
    }

    /** 在 thread 内按 QQ messageId 定位；默认实现适合 fake。 */
    default Optional<ChatMessage> findByMessageId(String threadId, String messageId) {
        if (threadId == null || threadId.isBlank() || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return readThread(threadId, 0L, 1000).stream()
                .filter(message -> messageId.trim().equals(message.messageId()))
                .reduce((first, second) -> second);
    }

    /**
     * 用平台目录更新 thread 的展示名（群名/好友备注）；未知 thread 为 no-op。
     * 只更新展示名，不改变来源键、消息、未读或发送状态。
     *
     * @return 是否实际改变
     */
    default boolean updateThreadDisplayName(String threadId, String displayName) {
        return false;
    }

    /** 读取 thread 投影。 */
    Optional<ChatThread> thread(String threadId);

    /**
     * 按 lastSeq 降序列出 thread。
     *
     * @param limit 1..1000
     */
    List<ChatThread> threads(int limit);
}
