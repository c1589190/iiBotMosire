package io.mosire.iibot.taskflow.post;

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
}
