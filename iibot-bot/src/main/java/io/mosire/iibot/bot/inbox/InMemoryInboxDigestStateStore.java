package io.mosire.iibot.bot.inbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单 JVM 内存 digest 游标；用于契约测试。 */
public final class InMemoryInboxDigestStateStore implements InboxDigestStateStore {

    private final Map<String, InboxDigestState> states = new LinkedHashMap<>();

    @Override
    public synchronized Optional<InboxDigestState> find(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(threadId));
    }

    @Override
    public synchronized void save(InboxDigestState state) {
        Objects.requireNonNull(state, "state");
        states.put(state.threadId(), state);
    }

    @Override
    public synchronized void clear(String threadId) {
        if (threadId != null) {
            states.remove(threadId);
        }
    }
}
