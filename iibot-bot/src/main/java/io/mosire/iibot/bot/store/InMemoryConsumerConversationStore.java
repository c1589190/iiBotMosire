package io.mosire.iibot.bot.store;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单 JVM consumer 会话游标；用于契约测试和最小 fake 装配。 */
public final class InMemoryConsumerConversationStore implements ConsumerConversationStore {

    private final Map<Key, ConsumerConversationState> states = new LinkedHashMap<>();

    @Override
    public synchronized Optional<ConsumerConversationState> find(
            String consumerId, String threadId) {
        if (consumerId == null || consumerId.isBlank()
                || threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(new Key(
                consumerId.trim(), threadId.trim())));
    }

    @Override
    public synchronized ConsumerConversationState recordDelivered(
            String consumerId, String threadId, long deliveredSeq, Instant updatedAt) {
        Key key = key(consumerId, threadId);
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (deliveredSeq < 0L) {
            throw new IllegalArgumentException("deliveredSeq 不能为负");
        }
        ConsumerConversationState current = states.get(key);
        if (current != null) {
            ConsumerConversationState next = current.withDelivered(deliveredSeq, updatedAt);
            states.put(key, next);
            return next;
        }
        ConsumerConversationState created = new ConsumerConversationState(
                key.consumerId(), key.threadId(), 0L, deliveredSeq, updatedAt);
        states.put(key, created);
        return created;
    }

    @Override
    public synchronized ConsumerConversationState advanceRead(
            String consumerId, String threadId, long readSeq, Instant updatedAt) {
        Key key = key(consumerId, threadId);
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (readSeq < 0L) {
            throw new IllegalArgumentException("readSeq 不能为负");
        }
        ConsumerConversationState current = states.get(key);
        if (current == null) {
            throw new IllegalStateException("尚未记录 deliveredSeq，不能推进 readSeq");
        }
        ConsumerConversationState next = current.withRead(readSeq, updatedAt);
        states.put(key, next);
        return next;
    }

    private static Key key(String consumerId, String threadId) {
        return new Key(requireText(consumerId, "consumerId"),
                requireText(threadId, "threadId"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private record Key(String consumerId, String threadId) {
    }
}
