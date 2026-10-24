package io.mosire.iibot.taskflow.event;

/**
 * 完整领域事件追加端口。
 *
 * <p>实现必须在一次持久化事务中写入事件 header 与完整内容，并在成功提交后返回
 * 存储分配的单调递增 seq。调用方只有在 append 成功返回后才能替换内存状态或返回
 * 成功回执；失败时必须保持旧状态。</p>
 */
@FunctionalInterface
public interface DomainEventJournal {

    long append(DomainEvent event);
}
