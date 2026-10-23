package io.mosire.iibot.taskflow.control;

import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.event.TaskflowEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 只读事件流端口。
 *
 * <p>控制面只通过本端口读取 {@link TaskflowEvent}，不读取文件、日志或数据库内部表。
 * 实现必须把 {@code afterSeq} 视为排他游标，且保持无副作用；是否持久化、如何分页均由
 * 宿主实现决定。{@link ExternalControlToolSet} 未注入本端口时，
 * {@code tf_event_poll} 返回稳定错误 {@code EVENT_STREAM_UNAVAILABLE}。</p>
 */
@FunctionalInterface
public interface EventReadPort {

    /**
     * 读取 seq 严格大于 {@code afterSeq} 的事件，最多 {@code limit} 条。
     *
     * @param afterSeq 排他游标，必须非负
     * @param limit    最大返回条数，必须为正
     * @return 事件列表；允许返回乱序，控制面会按 seq 升序整理
     */
    List<TaskflowEvent> readAfter(long afterSeq, int limit);

    /** {@link #readAfter(long, int)} 的语义别名。 */
    default List<TaskflowEvent> eventsAfter(long afterSeq, int limit) {
        return readAfter(afterSeq, limit);
    }

    /** {@link #readAfter(long, int)} 的短别名。 */
    default List<TaskflowEvent> events(long afterSeq, int limit) {
        return readAfter(afterSeq, limit);
    }

    /**
     * 为 {@link InMemoryEventStore} 提供适配器。
     *
     * @param store 内存事件存储
     * @return 只读、无消费游标的适配器
     */
    static EventReadPort of(InMemoryEventStore store) {
        return from(store);
    }

    /**
     * {@link #of(InMemoryEventStore)} 的语义别名，便于组合根按“来源适配器”命名。
     *
     * @param store 内存事件存储
     * @return 只读、无消费游标的适配器
     */
    static EventReadPort from(InMemoryEventStore store) {
        Objects.requireNonNull(store, "store");
        return (afterSeq, limit) -> {
            if (afterSeq < 0) {
                throw new IllegalArgumentException("afterSeq 不能为负");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit 必须为正");
            }
            List<TaskflowEvent> selected = new ArrayList<>();
            for (TaskflowEvent event : store.events()) {
                if (event.seq() > afterSeq) {
                    selected.add(event);
                    if (selected.size() >= limit) {
                        break;
                    }
                }
            }
            selected.sort(Comparator.comparingLong(TaskflowEvent::seq));
            return List.copyOf(selected);
        };
    }
}
