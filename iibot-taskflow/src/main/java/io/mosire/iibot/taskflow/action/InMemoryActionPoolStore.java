package io.mosire.iibot.taskflow.action;

import java.util.Objects;

/** 进程内行动池存储；只用于测试与宿主尚未接入持久化时的装配。 */
public final class InMemoryActionPoolStore implements ActionPoolStore {

    private ActionPoolSnapshot snapshot;

    public InMemoryActionPoolStore() {
        this(ActionPoolSnapshot.empty());
    }

    public InMemoryActionPoolStore(ActionPoolSnapshot initial) {
        snapshot = Objects.requireNonNull(initial, "initial");
    }

    @Override
    public synchronized ActionPoolSnapshot load() {
        return snapshot;
    }

    @Override
    public synchronized void save(ActionPoolSnapshot next) {
        snapshot = Objects.requireNonNull(next, "next");
    }
}
