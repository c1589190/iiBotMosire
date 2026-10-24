package io.mosire.iibot.taskflow.action;

import io.mosire.iibot.taskflow.event.DomainEvent;

/**
 * 行动池持久化端口。实现方必须在 {@link #save(ActionPoolSnapshot)} 返回成功后才算提交成功；
 * 抛出异常表示本次变更未落定。
 */
public interface ActionPoolStore {

    String RESOURCE_NAMESPACE = "action-pool";

    ActionPoolSnapshot load();

    void save(ActionPoolSnapshot snapshot);

    /**
     * 原子追加带完整领域事件的行动池快照变更。
     *
     * <p>默认实现保持旧内存 Store 兼容，只委托 {@link #save(ActionPoolSnapshot)}。
     * 事件源实现必须覆盖此方法，在事件 content 与 next snapshot 一致后仅通过
     * {@link io.mosire.iibot.taskflow.event.DomainEventJournal} 持久化。</p>
     */
    default void save(ActionPoolSnapshot snapshot, DomainEvent event) {
        save(snapshot);
    }
}
