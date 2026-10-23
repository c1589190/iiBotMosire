package io.mosire.iibot.taskflow.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 线程安全的纯内存事件存储。
 *
 * <p>seq 从 1 开始单调递增；events() 返回不可变快照，避免测试或上层意外改写审计
 * 事实。该实现只用于领域内核与单测，不承诺跨进程持久化。</p>
 */
public final class InMemoryEventStore implements EventSink {
    private final List<TaskflowEvent> events = new ArrayList<>();
    private long lastSeq;

    @Override
    public synchronized long append(TaskflowEvent event) {
        Objects.requireNonNull(event, "event");
        long seq = ++lastSeq;
        events.add(event.withSeq(seq));
        return seq;
    }

    public synchronized List<TaskflowEvent> events() {
        return List.copyOf(events);
    }

    public synchronized Optional<TaskflowEvent> event(long seq) {
        if (seq <= 0 || seq > events.size()) {
            return Optional.empty();
        }
        return Optional.of(events.get((int) seq - 1));
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }
}
