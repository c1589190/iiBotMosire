package io.mosire.iibot.taskflow.event;

/**
 * 完整事件追加端口。
 *
 * <p>返回值必须是同一次事务提交后的可读快照（至少包含存储分配的 seq 与内容 hash）。
 * 调用方只有在 append 成功返回后才能推进领域状态/回执。</p>
 */
@FunctionalInterface
public interface EventAppendPort {
    StoredEvent append(EventAppendRequest request);
}
