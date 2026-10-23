package io.mosire.iibot.taskflow.complaint;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 线程安全的内存投诉 store；只用于测试和离线装配。 */
public final class InMemoryComplaintStore implements ComplaintStore {

    private final AtomicReference<ComplaintSnapshot> snapshot;

    public InMemoryComplaintStore() {
        this(ComplaintSnapshot.empty());
    }

    public InMemoryComplaintStore(ComplaintSnapshot initial) {
        this.snapshot = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public ComplaintSnapshot load() {
        return snapshot.get();
    }

    @Override
    public void save(ComplaintSnapshot next) {
        snapshot.set(Objects.requireNonNull(next, "next"));
    }
}
