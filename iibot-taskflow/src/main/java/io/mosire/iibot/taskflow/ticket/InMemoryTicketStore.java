package io.mosire.iibot.taskflow.ticket;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 线程安全内存 TicketStore；只用于测试/离线装配。 */
public final class InMemoryTicketStore implements TicketStore {

    private final AtomicReference<TicketSnapshot> snapshot;

    public InMemoryTicketStore() {
        this(TicketSnapshot.empty());
    }

    public InMemoryTicketStore(TicketSnapshot initial) {
        this.snapshot = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public TicketSnapshot load() {
        return snapshot.get();
    }

    @Override
    public void save(TicketSnapshot next) {
        snapshot.set(Objects.requireNonNull(next, "next"));
    }
}
