package io.mosire.iibot.taskflow.control;

import io.mosire.iibot.taskflow.event.InMemoryEventStore;

import java.util.Objects;

/** {@link InMemoryEventStore} 的具名只读适配器；不消费任何游标。 */
public final class InMemoryEventReadPort implements EventReadPort {

    private final EventReadPort delegate;

    public InMemoryEventReadPort(InMemoryEventStore store) {
        this.delegate = EventReadPort.from(Objects.requireNonNull(store, "store"));
    }

    public static InMemoryEventReadPort of(InMemoryEventStore store) {
        return new InMemoryEventReadPort(store);
    }

    public static InMemoryEventReadPort from(InMemoryEventStore store) {
        return new InMemoryEventReadPort(store);
    }

    @Override
    public java.util.List<io.mosire.iibot.taskflow.event.TaskflowEvent> readAfter(
            long afterSeq, int limit) {
        return delegate.readAfter(afterSeq, limit);
    }
}
