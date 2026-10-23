package io.mosire.iibot.taskflow.snapshot;

import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.SettingProposal;
import io.mosire.iibot.taskflow.SettingProposalStatus;

import java.time.Instant;
import java.util.Objects;

/** 设定提案的纯数据快照，可见性以 {@link VisibilitySnapshot} 表示。 */
public record SettingProposalSnapshot(
        String proposalId,
        String authorActorId,
        ActorRole authorRole,
        String content,
        VisibilitySnapshot visibility,
        SettingProposalStatus status,
        Instant createdAt,
        String reviewerActorId,
        String reviewNote,
        Instant decidedAt,
        String worldFactId) {

    public SettingProposalSnapshot {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId 不能为空");
        }
        if (authorActorId == null || authorActorId.isBlank()) {
            throw new IllegalArgumentException("authorActorId 不能为空");
        }
        Objects.requireNonNull(authorRole, "authorRole");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (content.length() > SettingProposal.MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "content 不能超过 " + SettingProposal.MAX_CONTENT_CHARS + " 字符");
        }
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static SettingProposalSnapshot fromDomain(SettingProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        return new SettingProposalSnapshot(proposal.proposalId(), proposal.authorActorId(),
                proposal.authorRole(), proposal.content(),
                VisibilitySnapshot.fromDomain(proposal.visibility()), proposal.status(),
                proposal.createdAt(), proposal.reviewerActorId(), proposal.reviewNote(),
                proposal.decidedAt(), proposal.worldFactId());
    }

    public SettingProposal toDomain() {
        return new SettingProposal(proposalId, authorActorId, authorRole, content,
                visibility.toDomain(), status, createdAt, reviewerActorId, reviewNote,
                decidedAt, worldFactId);
    }
}
