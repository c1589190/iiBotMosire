package io.mosire.iibot.taskflow.post;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 线程安全的内存推文 store；只用于测试和离线装配。 */
public final class InMemoryPostStore implements PostStore {

    private final AtomicReference<PostSnapshot> snapshot;

    public InMemoryPostStore() {
        this(PostSnapshot.empty());
    }

    public InMemoryPostStore(PostSnapshot initial) {
        this.snapshot = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    @Override
    public PostSnapshot load() {
        return snapshot.get();
    }

    @Override
    public void save(PostSnapshot next) {
        snapshot.set(Objects.requireNonNull(next, "next"));
    }
}
