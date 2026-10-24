package io.mosire.iibot.taskflow.ticket;

import io.mosire.iibot.taskflow.event.DomainEvent;

/** 工单快照持久化端口。 */
public interface TicketStore {

    /** 工具资源命名空间。 */
    String RESOURCE_NAMESPACE = "ticket";

    TicketSnapshot load();

    void save(TicketSnapshot snapshot);

    /**
     * 原子追加带完整领域事件的快照变更。默认兼容旧 Store；事件源实现必须覆盖并校验
     * event.content 与 snapshot 完全一致后只写 journal。
     */
    default void save(TicketSnapshot snapshot, DomainEvent event) {
        save(snapshot);
    }
}
