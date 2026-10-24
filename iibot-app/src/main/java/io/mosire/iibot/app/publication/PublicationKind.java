package io.mosire.iibot.app.publication;

/**
 * 发布记录类型。
 *
 * <p>{@link #BATCH} 是从已提交行动批次生成的公共/私密发布映射；
 * {@link #OPENING} 是零玩家阶段由 GM 独立创建的公共开场推文，不绑定
 * ActionBatch 或回合。旧事件缺少 {@code kind} 时按 {@link #BATCH}
 * 反序列化，以保持 P6 数据兼容。</p>
 */
public enum PublicationKind {
    BATCH,
    OPENING
}
