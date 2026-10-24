package io.mosire.iibot.taskflow.post;

import io.mosire.iibot.taskflow.event.DomainEvent;

/**
 * 推文快照持久化端口。
 *
 * <p>实现必须把 {@link #save(PostSnapshot)} 当作一次原子替换：要么完整保存新快照，要么
 * 保持旧快照不变。应用层可在后续用 JSON/SQLite 实现；本模块只提供内存实现供测试与装配。</p>
 */
public interface PostStore {

    /** 工具资源命名空间；与 {@code SecretaryPostTools} 的资源元数据保持一致。 */
    String RESOURCE_NAMESPACE = "post";

    /** 读取完整快照；空 store 返回 {@link PostSnapshot#empty()}。 */
    PostSnapshot load();

    /** 原子替换完整快照。 */
    void save(PostSnapshot snapshot);

    /**
     * 原子追加带完整领域事件的快照变更。
     *
     * <p>默认实现保持旧内存/JSON Store 兼容，只委托 {@link #save(PostSnapshot)}。
     * 事件源实现必须覆盖此方法，把 {@code event.content} 与快照一致性校验后仅通过
     * {@link io.mosire.iibot.taskflow.event.DomainEventJournal} 持久化，禁止另写 JSON。</p>
     */
    default void save(PostSnapshot snapshot, DomainEvent event) {
        save(snapshot);
    }
}
