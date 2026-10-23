package io.mosire.iibot.taskflow;

import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 玩家/推演设定提案的领域流程、可见性与幂等边界。 */
class SettingProposalFlowTest {

    @Test
    void playerPublicProposalStaysPendingUntilDirectorApproval() {
        EngineFixture f = EngineFixture.create();
        long worldVersionBefore = f.worldVersion();
        String content = "海港午夜会升起蓝色月亮，只有目睹者知道其真正含义。";

        CommandResult proposed = f.expectOk(f.engine.proposeSetting(f.p1, content,
                Visibility.PUBLIC, f.globalRevision(), "propose-public"));
        String proposalId = proposed.referenceId().orElseThrow();
        SettingProposal pending = f.engine.settingProposal(proposalId).orElseThrow();

        assertThat(pending.status()).isEqualTo(SettingProposalStatus.PENDING);
        assertThat(pending.authorActorId()).isEqualTo("player-1");
        assertThat(pending.authorRole()).isEqualTo(ActorRole.PLAYER);
        assertThat(pending.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(pending.content()).isEqualTo(content);
        assertThat(f.worldVersion()).isEqualTo(worldVersionBefore);
        assertThat(f.engine.visibleFactsFor("player-1"))
                .noneMatch(fact -> fact.content().equals(content));
        assertThat(f.engine.knowledgeForSecretary("player-1").publicFacts())
                .noneMatch(fact -> fact.content().equals(content));
        assertThat(f.engine.pendingSettings()).extracting(SettingProposal::proposalId)
                .containsExactly(proposalId);

        CommandResult approved = f.expectOk(f.engine.decideSetting(f.director, proposalId,
                true, "符合世界观", f.globalRevision(), "approve-public"));
        SettingProposal decided = f.engine.settingProposal(proposalId).orElseThrow();
        String expectedFactId = "setting-" + proposalId;

        assertThat(approved.details().get("worldFactId")).isEqualTo(expectedFactId);
        assertThat(approved.details().get("worldVersion"))
                .isEqualTo(Long.toString(worldVersionBefore + 1));
        assertThat(f.worldVersion()).isEqualTo(worldVersionBefore + 1);
        assertThat(decided.status()).isEqualTo(SettingProposalStatus.APPROVED);
        assertThat(decided.reviewerActorId()).isEqualTo("director-1");
        assertThat(decided.reviewNote()).isEqualTo("符合世界观");
        assertThat(decided.decidedAt()).isNotNull();
        assertThat(decided.worldFactId()).isEqualTo(expectedFactId);
        assertThat(f.engine.visibleFactsFor("player-1"))
                .anySatisfy(fact -> {
                    assertThat(fact.factId()).isEqualTo(expectedFactId);
                    assertThat(fact.worldVersion()).isEqualTo(worldVersionBefore + 1);
                    assertThat(fact.content()).isEqualTo(content);
                    assertThat(fact.visibility()).isEqualTo(Visibility.PUBLIC);
                });
        assertThat(f.engine.knowledgeForSecretary("player-1").publicFacts())
                .anyMatch(fact -> fact.factId().equals(expectedFactId));
        assertThat(f.engine.pendingSettings()).isEmpty();
    }

    @Test
    void playerCannotProposeDirectorOnlyOrDecideAProposal() {
        EngineFixture f = EngineFixture.create();
        int eventsBefore = f.store.size();
        long revisionBefore = f.globalRevision();

        CommandResult forbiddenVisibility = f.engine.proposeSetting(f.p1, "玩家不能制造秘密",
                Visibility.DIRECTOR_ONLY, revisionBefore, "player-private");
        assertThat(forbiddenVisibility.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(f.globalRevision()).isEqualTo(revisionBefore);
        assertThat(f.store.size()).isEqualTo(eventsBefore);
        assertThat(f.engine.pendingSettings()).isEmpty();

        CommandResult proposed = f.expectOk(f.engine.proposeSetting(f.director,
                "推演私密储备", Visibility.DIRECTOR_ONLY, f.globalRevision(),
                "director-private"));
        int eventsAfterProposal = f.store.size();
        long revisionAfterProposal = f.globalRevision();
        long worldVersion = f.worldVersion();

        CommandResult playerDecision = f.engine.decideSetting(f.p1,
                proposed.referenceId().orElseThrow(), true, "玩家想批准",
                revisionAfterProposal, "player-decide");
        assertThat(playerDecision.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(f.globalRevision()).isEqualTo(revisionAfterProposal);
        assertThat(f.worldVersion()).isEqualTo(worldVersion);
        assertThat(f.store.size()).isEqualTo(eventsAfterProposal);
        assertThat(f.engine.settingProposal(proposed.referenceId().orElseThrow()).orElseThrow()
                .status()).isEqualTo(SettingProposalStatus.PENDING);
    }

    @Test
    void approvedProposalKeepsPlayerSetAndDirectorOnlyVisibility() {
        EngineFixture f = EngineFixture.create();
        CommandResult playerOnly = f.expectOk(f.engine.proposeSetting(f.director,
                "只有玩家一能看到的港湾暗号", Visibility.forPlayers(Set.of("player-1")),
                f.globalRevision(), "director-player-set"));
        f.expectOk(f.engine.decideSetting(f.director, playerOnly.referenceId().orElseThrow(),
                true, "定向批准", f.globalRevision(), "approve-player-set"));
        assertThat(f.engine.visibleFactsFor("player-1"))
                .anyMatch(fact -> fact.factId().equals(
                        "setting-" + playerOnly.referenceId().orElseThrow())
                        && fact.visibility().equals(Visibility.forPlayers(Set.of("player-1"))));
        assertThat(f.engine.visibleFactsFor("player-2"))
                .noneMatch(fact -> fact.factId().equals(
                        "setting-" + playerOnly.referenceId().orElseThrow()));

        CommandResult directorOnly = f.expectOk(f.engine.proposeSetting(f.director,
                "只有推演知道的幕后真相", Visibility.DIRECTOR_ONLY,
                f.globalRevision(), "director-only-setting"));
        String proposalId = directorOnly.referenceId().orElseThrow();
        f.expectOk(f.engine.decideSetting(f.director, proposalId, true, "仅推演",
                f.globalRevision(), "approve-director-only"));
        String factId = "setting-" + proposalId;
        assertThat(f.engine.visibleFactsFor("player-1"))
                .noneMatch(fact -> fact.factId().equals(factId));
        assertThat(f.engine.visibleFactsFor("player-2"))
                .noneMatch(fact -> fact.factId().equals(factId));
        assertThat(Visibility.DIRECTOR_ONLY.canSee("director-1", ActorRole.DIRECTOR)).isTrue();
        assertThat(f.engine.settingProposal(proposalId).orElseThrow().worldFactId())
                .isEqualTo(factId);
    }

    @Test
    void rejectionKeepsWorldVersionAndNeverLeaksContentIntoFacts() {
        EngineFixture f = EngineFixture.create();
        String content = "只有推演知道的旧神真名";
        CommandResult proposed = f.expectOk(f.engine.proposeSetting(f.p1, content,
                Visibility.PUBLIC, f.globalRevision(), "propose-reject"));
        String proposalId = proposed.referenceId().orElseThrow();
        long worldVersion = f.worldVersion();
        int eventsBeforeReject = f.store.size();

        CommandResult rejected = f.expectOk(f.engine.decideSetting(f.director, proposalId,
                false, "与既有设定冲突", f.globalRevision(), "reject-setting"));
        SettingProposal proposal = f.engine.settingProposal(proposalId).orElseThrow();

        assertThat(rejected.details().get("worldVersion"))
                .isEqualTo(Long.toString(worldVersion));
        assertThat(f.worldVersion()).isEqualTo(worldVersion);
        assertThat(proposal.status()).isEqualTo(SettingProposalStatus.REJECTED);
        assertThat(proposal.worldFactId()).isNull();
        assertThat(proposal.reviewNote()).isEqualTo("与既有设定冲突");
        assertThat(f.engine.visibleFactsFor("player-1"))
                .noneMatch(fact -> fact.content().equals(content));
        assertThat(f.store.size()).isEqualTo(eventsBeforeReject + 1);
        assertThat(f.store.events().get(f.store.size() - 1).type())
                .isEqualTo(EventType.SETTING_REJECTED);
        assertThat(f.store.events().get(f.store.size() - 1).payload().values())
                .noneMatch(value -> value.contains(content));
    }

    @Test
    void unknownRepeatRevisionConflictAndIdempotencyHaveNoDuplicateEffects() {
        EngineFixture f = EngineFixture.create();
        long revision = f.globalRevision();
        int eventsBefore = f.store.size();

        CommandResult unknown = f.engine.decideSetting(f.director, "missing-proposal",
                true, "", revision, "unknown-decide");
        assertThat(unknown.code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(f.globalRevision()).isEqualTo(revision);
        assertThat(f.store.size()).isEqualTo(eventsBefore);

        CommandResult firstPropose = f.expectOk(f.engine.proposeSetting(f.p1, "潮汐规律",
                Visibility.PUBLIC, f.globalRevision(), "duplicate-propose"));
        int eventsAfterPropose = f.store.size();
        CommandResult duplicatePropose = f.engine.proposeSetting(f.p1, "完全不同的内容",
                Visibility.DIRECTOR_ONLY, f.globalRevision(), "duplicate-propose");
        assertThat(duplicatePropose).isEqualTo(firstPropose);
        assertThat(f.store.size()).isEqualTo(eventsAfterPropose);
        assertThat(f.engine.pendingSettings()).hasSize(1);

        String proposalId = firstPropose.referenceId().orElseThrow();
        long approveRevision = f.globalRevision();
        CommandResult conflict = f.engine.decideSetting(f.director, proposalId, true, "",
                approveRevision - 1, "conflict-decide");
        assertThat(conflict.code()).isEqualTo(ErrorCode.REVISION_CONFLICT);
        assertThat(f.globalRevision()).isEqualTo(approveRevision);
        assertThat(f.worldVersion()).isZero();
        assertThat(f.store.size()).isEqualTo(eventsAfterPropose);
        assertThat(f.engine.settingProposal(proposalId).orElseThrow().status())
                .isEqualTo(SettingProposalStatus.PENDING);

        CommandResult firstApprove = f.expectOk(f.engine.decideSetting(f.director, proposalId,
                true, "通过", approveRevision, "approve-once"));
        int eventsAfterApprove = f.store.size();
        long worldVersionAfterApprove = f.worldVersion();
        CommandResult duplicateApprove = f.engine.decideSetting(f.director, proposalId,
                false, "重复决定", approveRevision - 1, "approve-once");
        assertThat(duplicateApprove).isEqualTo(firstApprove);
        assertThat(f.store.size()).isEqualTo(eventsAfterApprove);
        assertThat(f.worldVersion()).isEqualTo(worldVersionAfterApprove);

        CommandResult secondDecisionWithNewKey = f.engine.decideSetting(f.director,
                proposalId, false, "", f.globalRevision(), "approve-again");
        assertThat(secondDecisionWithNewKey.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(f.store.size()).isEqualTo(eventsAfterApprove);
        assertThat(f.worldVersion()).isEqualTo(worldVersionAfterApprove);
        assertThat(f.engine.settingProposal(proposalId).orElseThrow().status())
                .isEqualTo(SettingProposalStatus.APPROVED);
    }

    @Test
    void settingEventsCarryAuditIdsButNotFullContent() {
        EngineFixture f = EngineFixture.create();
        String marker = "LONG_SETTING_SECRET_MARKER_";
        String content = marker + "x".repeat(4_000);
        CommandResult proposed = f.expectOk(f.engine.proposeSetting(f.p1, content,
                Visibility.PUBLIC, f.globalRevision(), "long-propose"));
        String proposalId = proposed.referenceId().orElseThrow();
        f.expectOk(f.engine.decideSetting(f.director, proposalId, true, "长正文审计",
                f.globalRevision(), "long-approve"));

        List<TaskflowEvent> settingEvents = f.store.events().stream()
                .filter(event -> event.type() == EventType.SETTING_PROPOSED
                        || event.type() == EventType.SETTING_APPROVED
                        || event.type() == EventType.SETTING_REJECTED)
                .toList();
        assertThat(settingEvents).extracting(TaskflowEvent::type)
                .containsExactly(EventType.SETTING_PROPOSED, EventType.SETTING_APPROVED);
        assertThat(settingEvents.get(0).payload())
                .containsEntry("proposalId", proposalId)
                .containsEntry("authorId", "player-1")
                .containsEntry("visibility", "PUBLIC");
        assertThat(settingEvents.get(1).payload())
                .containsEntry("proposalId", proposalId)
                .containsEntry("worldFactId", "setting-" + proposalId);
        for (TaskflowEvent event : settingEvents) {
            assertThat(event.payload().values())
                    .allSatisfy(value -> assertThat(value).doesNotContain(marker));
        }
        assertThat(f.engine.visibleFactsFor("player-1"))
                .anySatisfy(fact -> assertThat(fact.content()).isEqualTo(content));
    }

    @Test
    void pendingSettingsAreStableAndRecordValidatesState() {
        EngineFixture f = EngineFixture.create();
        f.expectOk(f.engine.proposeSetting(f.p1, "设定甲", Visibility.PUBLIC,
                f.globalRevision(), "pending-a"));
        f.expectOk(f.engine.proposeSetting(f.p1, "设定乙", Visibility.PUBLIC,
                f.globalRevision(), "pending-b"));
        List<SettingProposal> pending = f.engine.pendingSettings();
        assertThat(pending).hasSize(2);
        assertThat(pending).isSortedAccordingTo(
                Comparator.comparing(SettingProposal::createdAt)
                        .thenComparing(SettingProposal::proposalId));

        int eventsBeforeTooLong = f.store.size();
        long revisionBeforeTooLong = f.globalRevision();
        CommandResult tooLong = f.engine.proposeSetting(f.p1,
                "x".repeat(SettingProposal.MAX_CONTENT_CHARS + 1), Visibility.PUBLIC,
                revisionBeforeTooLong, "too-long-setting");
        assertThat(tooLong.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(f.globalRevision()).isEqualTo(revisionBeforeTooLong);
        assertThat(f.store.size()).isEqualTo(eventsBeforeTooLong);

        Instant now = f.clock.instant();
        assertThatThrownBy(() -> new SettingProposal("p", "player-1", ActorRole.PLAYER,
                "x".repeat(SettingProposal.MAX_CONTENT_CHARS + 1), Visibility.PUBLIC,
                SettingProposalStatus.PENDING, now, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过");
        assertThatThrownBy(() -> new SettingProposal("p", "player-1", ActorRole.PLAYER,
                "内容", Visibility.PUBLIC, SettingProposalStatus.APPROVED, now,
                null, "通过", now, "setting-p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewerActorId");
    }
}
