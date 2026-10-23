package io.mosire.iibot.taskflow;

import java.util.Objects;
import java.util.Optional;

/**
 * 引擎全局只读状态。
 *
 * <p>engineRevision 是所有全局与回合写操作的统一乐观锁版本；worldVersion 只在成功
 * commit 或装载新世界事实时变化。turn 为空表示当前没有未终态回合。</p>
 */
public record EngineStatus(
        long engineRevision,
        long worldVersion,
        int playerCount,
        int directorCount,
        int eventCount,
        TurnView turn) {

    public EngineStatus {
        if (engineRevision < 0 || worldVersion < 0 || playerCount < 0 || directorCount < 0 || eventCount < 0) {
            throw new IllegalArgumentException("计数和版本不能为负");
        }
    }

    public Optional<TurnView> currentTurn() {
        return Optional.ofNullable(turn);
    }

    public boolean hasActiveTurn() {
        return turn != null && !turn.state().isTerminal();
    }
}
