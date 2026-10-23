package io.mosire.iibot.taskflow;

/**
 * 玩家或推演在领域中的稳定身份映射。
 *
 * <p>qqId 只作为外部账号到内部 playerId 的映射，不参与领域授权。所有内部事件和
 * 快照只使用 playerId，避免接入协议字段泄漏到业务模型。</p>
 */
public record PlayerProfile(String playerId, String qqId, String displayName) {
    public PlayerProfile {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        if (qqId == null || qqId.isBlank()) {
            throw new IllegalArgumentException("qqId 不能为空");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 不能为空");
        }
    }
}
