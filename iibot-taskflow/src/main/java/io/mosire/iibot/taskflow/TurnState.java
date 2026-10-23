package io.mosire.iibot.taskflow;

/**
 * 回合状态机。
 *
 * <p>正常路径为 {@code OPEN -> LOCKED -> ADJUDICATING -> COMMITTED -> PUBLISHED}；
 * 推演失败可从 {@code ADJUDICATING} 进入 {@code FAILED_RETRYABLE} 再重试。终态
 * 回合不能再被修改，只有终态之后才允许开启下一回合。</p>
 */
public enum TurnState {
    OPEN,
    LOCKED,
    ADJUDICATING,
    COMMITTED,
    PUBLISHED,
    FAILED_RETRYABLE,
    PAUSED,
    CANCELLED;

    /** 是否已经结束，结束后才能开启下一回合。 */
    public boolean isTerminal() {
        return this == PUBLISHED || this == CANCELLED;
    }
}
