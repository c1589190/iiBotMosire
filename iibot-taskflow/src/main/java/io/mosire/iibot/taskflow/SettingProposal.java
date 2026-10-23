package io.mosire.iibot.taskflow;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条尚未生效的世界设定提案。
 *
 * <p>提案不是 {@link WorldFact}：在 PENDING 阶段它不参与任何玩家可见性计算，也不会
 * 进入秘书/GM 的世界上下文。只有 DIRECTOR/GM/SUPERVISOR 审核并批准后，引擎才会以
 * 稳定 ID 将其转换为新的 {@link WorldFact}，同时提升 worldVersion。拒绝或重复审核
 * 不会改变世界事实。</p>
 *
 * <p>该 record 不可变并且对审核状态做结构校验：PENDING 不得带审核人/决定时间，
 * APPROVED 必须带有审核人、决定时间和稳定 worldFactId，REJECTED 必须带有审核人
 * 与决定时间但不得带 worldFactId。正文上限与引擎命令保持一致。</p>
 *
 * @param proposalId       提案稳定标识
 * @param authorActorId    提案作者角色标识
 * @param authorRole       提案作者角色
 * @param content          待生效设定正文
 * @param visibility       批准后世界事实的可见性
 * @param status           当前审核状态
 * @param createdAt        提案创建时间
 * @param reviewerActorId  审核人；PENDING 时为 null
 * @param reviewNote       审核说明，可为 null
 * @param decidedAt        审核决定时间；PENDING 时为 null
 * @param worldFactId      批准后生成的稳定世界事实 ID；仅 APPROVED 时非 null
 */
public record SettingProposal(
        String proposalId,
        String authorActorId,
        ActorRole authorRole,
        String content,
        Visibility visibility,
        SettingProposalStatus status,
        Instant createdAt,
        String reviewerActorId,
        String reviewNote,
        Instant decidedAt,
        String worldFactId) {

    /** 单条设定正文的最大 UTF-16 code unit 数。 */
    public static final int MAX_CONTENT_CHARS = 8_192;

    public SettingProposal {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId 不能为空");
        }
        if (authorActorId == null || authorActorId.isBlank()) {
            throw new IllegalArgumentException("authorActorId 不能为空");
        }
        Objects.requireNonNull(authorRole, "authorRole");
        if (authorRole != ActorRole.PLAYER && authorRole != ActorRole.DIRECTOR
                && authorRole != ActorRole.GM && authorRole != ActorRole.SUPERVISOR) {
            throw new IllegalArgumentException("authorRole 不能提交设定提案");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("content 不能超过 " + MAX_CONTENT_CHARS + " 字符");
        }
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");

        switch (status) {
            case PENDING -> {
                if (reviewerActorId != null || reviewNote != null
                        || decidedAt != null || worldFactId != null) {
                    throw new IllegalArgumentException("PENDING 提案不能包含审核结果");
                }
            }
            case APPROVED -> {
                if (reviewerActorId == null || reviewerActorId.isBlank()) {
                    throw new IllegalArgumentException("APPROVED 提案必须有 reviewerActorId");
                }
                if (decidedAt == null) {
                    throw new IllegalArgumentException("APPROVED 提案必须有 decidedAt");
                }
                if (worldFactId == null || worldFactId.isBlank()) {
                    throw new IllegalArgumentException("APPROVED 提案必须有 worldFactId");
                }
            }
            case REJECTED -> {
                if (reviewerActorId == null || reviewerActorId.isBlank()) {
                    throw new IllegalArgumentException("REJECTED 提案必须有 reviewerActorId");
                }
                if (decidedAt == null) {
                    throw new IllegalArgumentException("REJECTED 提案必须有 decidedAt");
                }
                if (worldFactId != null) {
                    throw new IllegalArgumentException("REJECTED 提案不能有 worldFactId");
                }
            }
        }
    }
}
