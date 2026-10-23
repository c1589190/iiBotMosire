package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** brief 来源校验与秘书知识面的硬过滤。 */
class BriefVisibilityTest {

    @Test
    void briefCanOnlyCiteFactsVisibleToItsPlayerAndKnowledgeNeverLeaksOtherPrivateFacts() {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(
                new WorldFact("public", 1, "公共港口", Visibility.PUBLIC),
                new WorldFact("p1-secret", 1, "玩家一的秘密", Visibility.forPlayers(Set.of("player-1"))),
                new WorldFact("p2-secret", 1, "玩家二的秘密", Visibility.forPlayers(Set.of("player-2"))),
                new WorldFact("director-secret", 1, "仅推演知道", Visibility.DIRECTOR_ONLY));
        f.openTurn();

        CommandResult request = f.expectOk(f.engine.requestBrief(f.p1,
                "港口有什么线索？", f.turnRevision(), "request-1"));
        String requestId = request.referenceId().orElseThrow();
        long revisionAfterRequest = f.turnRevision();
        int eventsBeforeInvalidIssue = f.store.size();

        CommandResult invalid = f.engine.issueBrief(new Actor("gm-1", ActorRole.GM), requestId,
                "不应签发", List.of("p2-secret"), f.clock.instant().plus(Duration.ofHours(1)),
                revisionAfterRequest, "issue-invalid");
        assertThat(invalid.code()).isEqualTo(ErrorCode.BRIEF_SOURCE_NOT_VISIBLE);
        assertThat(f.turnRevision()).isEqualTo(revisionAfterRequest);
        assertThat(f.store.size()).isEqualTo(eventsBeforeInvalidIssue);
        assertThat(f.engine.briefsForPlayer("player-1")).isEmpty();

        CommandResult issued = f.expectOk(f.engine.issueBrief(new Actor("gm-1", ActorRole.GM),
                requestId, "你只知道码头有盏灯。", List.of("public", "p1-secret"),
                f.clock.instant().plus(Duration.ofHours(1)), revisionAfterRequest, "issue-valid"));
        assertThat(issued.ok()).isTrue();
        assertThat(f.engine.infoRequest(requestId).orElseThrow().status())
                .isEqualTo(InfoRequestStatus.ISSUED);

        SecretaryKnowledge knowledge = f.engine.knowledgeForSecretary("player-1");
        assertThat(knowledge.briefs()).hasSize(1);
        assertThat(knowledge.briefs().getFirst().sourceFactIds())
                .containsExactlyInAnyOrder("public", "p1-secret");
        assertThat(knowledge.publicFacts()).extracting(WorldFact::factId).containsExactly("public");
        assertThat(knowledge.publicFacts()).extracting(WorldFact::factId)
                .doesNotContain("p1-secret", "p2-secret", "director-secret");
        assertThat(knowledge.briefs().getFirst().content()).doesNotContain("玩家二的秘密");

        assertThat(f.engine.visibleFactsFor("player-1"))
                .extracting(WorldFact::factId)
                .containsExactlyInAnyOrder("public", "p1-secret");
        assertThat(f.engine.visibleFactsFor("player-1"))
                .extracting(WorldFact::factId)
                .doesNotContain("p2-secret", "director-secret");
        assertThat(f.engine.visibleFactsFor("player-2"))
                .extracting(WorldFact::factId)
                .containsExactlyInAnyOrder("public", "p2-secret");

        f.clock.advance(Duration.ofHours(2));
        assertThat(f.engine.knowledgeForSecretary("player-1").briefs()).isEmpty();
    }
}
