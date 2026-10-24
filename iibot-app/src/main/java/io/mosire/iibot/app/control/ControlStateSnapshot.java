package io.mosire.iibot.app.control;

import java.time.Instant;
import java.util.Objects;

/**
 * 运行期控制面状态快照。
 *
 * <p>{@code revision} 是控制面本地单调版本，仅用于摘要/幂等，不替代
 * {@code TaskflowEngine.engineRevision}。玩家/推演身份仍以引擎快照为唯一事实源，
 * 本快照只保存公共群与初始化时间。</p>
 */
public record ControlStateSnapshot(long revision, String publicGroupId, Instant initializedAt) {

    public ControlStateSnapshot {
        if (revision < 0) {
            throw new IllegalArgumentException("control revision 不能为负");
        }
        if (publicGroupId == null || publicGroupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        Objects.requireNonNull(initializedAt, "initializedAt");
    }
}
