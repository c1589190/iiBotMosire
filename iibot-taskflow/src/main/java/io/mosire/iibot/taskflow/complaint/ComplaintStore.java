package io.mosire.iibot.taskflow.complaint;

/**
 * 投诉快照持久化端口。
 *
 * <p>实现必须把 {@link #save(ComplaintSnapshot)} 当作一次原子替换：要么完整保存新快照，
 * 要么保持旧快照不变。应用层后续可实现严格 JSON 持久化；本模块只提供内存实现供测试与
 * 离线装配。</p>
 */
public interface ComplaintStore {

    /** 工具资源命名空间；与 {@code SecretaryComplaintTools} 的资源元数据保持一致。 */
    String RESOURCE_NAMESPACE = "complaint";

    /** 读取完整快照；空 store 返回 {@link ComplaintSnapshot#empty()}。 */
    ComplaintSnapshot load();

    /** 原子替换完整快照。 */
    void save(ComplaintSnapshot snapshot);
}
