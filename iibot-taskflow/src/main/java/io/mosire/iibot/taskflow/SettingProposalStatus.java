package io.mosire.iibot.taskflow;

/**
 * 设定提案的生命周期状态。
 *
 * <p>提案创建时为 {@link #PENDING}；只有推演角色可以将其一次性决定为
 * {@link #APPROVED} 或 {@link #REJECTED}。状态一旦离开 PENDING 就不再改变。</p>
 */
public enum SettingProposalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
