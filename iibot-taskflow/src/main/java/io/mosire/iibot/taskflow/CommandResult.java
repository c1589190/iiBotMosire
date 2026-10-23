package io.mosire.iibot.taskflow;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 写命令回执。
 *
 * <p>revision 是本次命令所针对聚合的版本：全局命令（注册、装载事实）使用引擎版本，
 * 回合命令使用回合版本；engineRevision 始终是引擎全局版本。eventSeq 为 {@code -1}
 * 表示命令失败且没有追加事件。重复幂等键会原样返回第一次回执，因此同一回执的
 * eventSeq 与 revision 不会因后续状态变化而改变。</p>
 *
 * @param details 只放少量可审计标识（如 draftId、requestId），不放秘密内容
 */
public record CommandResult(
        ErrorCode code,
        long revision,
        long engineRevision,
        long eventSeq,
        TurnState turnState,
        String message,
        Map<String, String> details) {

    public CommandResult {
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public boolean ok() {
        return code == ErrorCode.OK;
    }

    public Optional<String> detail(String key) {
        return Optional.ofNullable(details.get(key));
    }

    /** 成功命令生成的稳定引用 ID，例如 draftId 或 requestId。 */
    public Optional<String> referenceId() {
        return detail("referenceId");
    }
}
