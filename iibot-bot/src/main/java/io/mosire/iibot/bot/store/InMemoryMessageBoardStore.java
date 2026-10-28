package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.connect.core.MessageSegment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单 JVM 内存消息板；用于契约测试和后续 SQLite 实现的行为基线。
 *
 * <p>写操作串行化；重复 messageKey 不重新分配 seq，也不修改 thread 投影。</p>
 */
public final class InMemoryMessageBoardStore implements MessageBoardStore {

    private final Map<String, ChatMessage> byMessageKey = new LinkedHashMap<>();
    private final Map<String, List<ChatMessage>> byThreadId = new LinkedHashMap<>();
    private final Map<String, List<MessageSegment>> segmentsByMessageKey = new HashMap<>();
    private final Map<String, ChatThread> threadsBySource = new HashMap<>();
    private long nextSeq;

    @Override
    public synchronized MessageAppendResult append(ChatMessage message) {
        return append(message, List.of());
    }

    @Override
    public synchronized MessageAppendResult append(ChatMessage message,
                                                   List<MessageSegment> segments) {
        Objects.requireNonNull(message, "message");
        if (message.localSeq() != 0L) {
            throw new IllegalArgumentException("append 只接受尚未入库的消息（localSeq=0）");
        }
        ChatMessage existing = byMessageKey.get(message.messageKey());
        if (existing != null) {
            return new MessageAppendResult(existing, false);
        }
        ChatMessage stored = message.withLocalSeq(++nextSeq);
        String threadId = stored.source().threadId();
        ChatThread thread = threadsBySource.getOrDefault(
                threadId, ChatThread.empty(stored.source()));
        ChatThread updated = thread.after(stored);
        byMessageKey.put(stored.messageKey(), stored);
        segmentsByMessageKey.put(stored.messageKey(),
                segments == null ? List.of() : List.copyOf(segments));
        byThreadId.computeIfAbsent(threadId, ignored -> new ArrayList<>()).add(stored);
        threadsBySource.put(threadId, updated);
        return new MessageAppendResult(stored, true);
    }

    @Override
    public synchronized List<MessageSegment> segments(String messageKey) {
        if (messageKey == null || messageKey.isBlank()) {
            return List.of();
        }
        return segmentsByMessageKey.getOrDefault(messageKey, List.of());
    }

    @Override
    public synchronized Optional<ChatMessage> find(String messageKey) {
        if (messageKey == null || messageKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byMessageKey.get(messageKey));
    }

    @Override
    public synchronized ChatMessage updateDelivery(String messageKey, DeliveryState state,
                                                   String externalMessageId, Instant sentAt) {
        if (state == null || state == DeliveryState.NONE) {
            throw new IllegalArgumentException("出站消息必须使用有效投递状态");
        }
        ChatMessage current = byMessageKey.get(messageKey);
        if (current == null) {
            throw new IllegalArgumentException("消息不存在: " + messageKey);
        }
        ChatMessage updated = current.withDelivery(
                state,
                externalMessageId == null ? current.messageId() : externalMessageId,
                sentAt == null ? current.sentAt() : sentAt);
        byMessageKey.put(messageKey, updated);
        List<ChatMessage> threadMessages = byThreadId.get(updated.source().threadId());
        if (threadMessages != null) {
            for (int index = 0; index < threadMessages.size(); index++) {
                if (threadMessages.get(index).messageKey().equals(messageKey)) {
                    threadMessages.set(index, updated);
                    break;
                }
            }
        }
        return updated;
    }

    @Override
    public synchronized List<ChatMessage> readThread(String threadId, long afterSeq, int limit) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (afterSeq < 0L) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        List<ChatMessage> messages = byThreadId.getOrDefault(threadId, List.of());
        List<ChatMessage> result = new ArrayList<>(Math.min(limit, messages.size()));
        for (ChatMessage message : messages) {
            if (message.localSeq() > afterSeq) {
                result.add(message);
                if (result.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized List<ChatMessage> readThreadBefore(
            String threadId, long beforeSeq, int limit) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (beforeSeq < 0L) {
            throw new IllegalArgumentException("beforeSeq 不能为负");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        List<ChatMessage> all = byThreadId.getOrDefault(threadId, List.of()).stream()
                .filter(message -> message.localSeq() < beforeSeq)
                .toList();
        int from = Math.max(0, all.size() - limit);
        return List.copyOf(all.subList(from, all.size()));
    }

    @Override
    public synchronized long countInboundAfter(String threadId, long afterSeq) {
        if (threadId == null || threadId.isBlank() || afterSeq < 0L) {
            throw new IllegalArgumentException("threadId/afterSeq 非法");
        }
        return byThreadId.getOrDefault(threadId, List.of()).stream()
                .filter(message -> message.localSeq() > afterSeq)
                .filter(message -> message.direction() == ChatDirection.IN)
                .count();
    }

    @Override
    public synchronized Optional<ChatMessage> findByMessageId(
            String threadId, String messageId) {
        if (threadId == null || threadId.isBlank()
                || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return byThreadId.getOrDefault(threadId, List.of()).stream()
                .filter(message -> messageId.trim().equals(message.messageId()))
                .reduce((first, second) -> second);
    }

    @Override
    public synchronized Optional<ChatThread> thread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(threadsBySource.get(threadId));
    }

    @Override
    public synchronized List<ChatThread> threads(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        return threadsBySource.values().stream()
                .sorted(Comparator.comparingLong(ChatThread::lastSeq).reversed())
                .limit(limit)
                .toList();
    }
}
