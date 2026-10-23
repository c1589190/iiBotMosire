package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.InfoRequestStatus;
import io.mosire.iibot.taskflow.WorldFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 世界事实可见性映射与 brief 信息请求/签发边界。 */
class ExternalControlWorldAndBriefTest {

    @Test
    void loadWorldFactsMapsAllVisibilityKindsAndInvalidValuesAreRejected() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult loaded = fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", 0L, "expectedWorldVersion", 0L,
                        "idempotencyKey", "load-visible", "reason", "装载可见性测试",
                        "facts", List.of(
                                Map.of("factId", "public-1", "content", "公开事实",
                                        "visibility", "PUBLIC"),
                                Map.of("factId", "director-1", "content", "仅推演事实",
                                        "visibility", "DIRECTOR_ONLY"),
                                Map.of("factId", "players-1", "content", "仅玩家一事实",
                                        "visibility", Map.of("type", "PLAYERS",
                                                "playerIds", List.of("p1"))))));
        assertThat(loaded.code()).isNull();
        assertThat(fixture.status().worldVersion()).isEqualTo(1);
        assertThat(fixture.engine.visibleFactsFor("p1"))
                .extracting(WorldFact::factId)
                .containsExactly("players-1", "public-1");
        assertThat(fixture.engine.visibleFactsFor("p2"))
                .extracting(WorldFact::factId)
                .containsExactly("public-1");
        assertThat(fixture.engine.visibleFactsFor("p1"))
                .allSatisfy(fact -> assertThat(fact.worldVersion()).isEqualTo(1));

        long revisionBeforeInvalid = fixture.status().engineRevision();
        int eventsBeforeInvalid = fixture.store.size();
        ToolResult invalid = fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", revisionBeforeInvalid,
                        "expectedWorldVersion", 1L,
                        "idempotencyKey", "load-invalid", "reason", "非法可见性",
                        "facts", List.of(Map.of("factId", "bad", "content", "坏事实",
                                "visibility", Map.of("type", "EVERYONE")))));
        assertThat(invalid.code()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(fixture.status().engineRevision()).isEqualTo(revisionBeforeInvalid);
        assertThat(fixture.status().worldVersion()).isEqualTo(1);
        assertThat(fixture.store.size()).isEqualTo(eventsBeforeInvalid);

        ToolResult emptyPlayers = fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", revisionBeforeInvalid,
                        "expectedWorldVersion", 1L,
                        "idempotencyKey", "load-empty-players", "reason", "空玩家集",
                        "facts", List.of(Map.of("factId", "bad2", "content", "坏事实",
                                "visibility", Map.of("type", "PLAYERS",
                                        "playerIds", List.of())))));
        assertThat(emptyPlayers.code()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(fixture.store.size()).isEqualTo(eventsBeforeInvalid);
    }

    @Test
    void requestAndIssueBriefWorkThroughControlToolsWithGmActor() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        assertThat(fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "P1", "expectedRevision", 0L,
                        "idempotencyKey", "brief-reg", "reason", "注册玩家")).code()).isNull();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", 1L, "expectedWorldVersion", 0L,
                        "idempotencyKey", "brief-load", "reason", "装载事实",
                        "facts", List.of(Map.of("factId", "public-1",
                                "content", "港口公开事实", "visibility", "PUBLIC")))).code()).isNull();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_OPEN_TURN,
                ControlTestFixture.args("minutes", 60, "expectedRevision", 2L,
                        "idempotencyKey", "brief-open", "reason", "开启回合")).code()).isNull();

        CommandResult request = fixture.engine.requestBrief(
                new Actor("p1", ActorRole.PLAYER), "港口发生了什么？",
                fixture.status().turn().revision(), "brief-request");
        assertThat(request.ok()).isTrue();
        String requestId = request.referenceId().orElseThrow();
        assertThat(requestId).isNotBlank();
        assertThat(fixture.engine.infoRequest(requestId).orElseThrow().status())
                .isEqualTo(InfoRequestStatus.REQUESTED);

        ToolResult issued = fixture.call(control, ExternalControlToolSet.TF_ISSUE_BRIEF,
                ControlTestFixture.args("requestId", requestId,
                        "content", "港口今天有钟声。",
                        "sourceFactIds", List.of("public-1"),
                        "expectedRevision", 2L, "idempotencyKey", "brief-issue",
                        "reason", "GM 过滤后签发"));
        assertThat(issued.code()).isNull();
        assertThat(fixture.engine.infoRequest(requestId).orElseThrow().status())
                .isEqualTo(InfoRequestStatus.ISSUED);
        assertThat(fixture.engine.briefsForPlayer("p1"))
                .extracting(brief -> brief.content())
                .containsExactly("港口今天有钟声。");
    }

    @Test
    void issueBriefRejectsDirectorOnlySourceForPlayerWithZeroSideEffects() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "P1", "expectedRevision", 0L,
                        "idempotencyKey", "reject-reg", "reason", "注册"));
        fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", 1L, "expectedWorldVersion", 0L,
                        "idempotencyKey", "reject-load", "reason", "装载",
                        "facts", List.of(
                                Map.of("factId", "public-1", "content", "公开",
                                        "visibility", "PUBLIC"),
                                Map.of("factId", "secret-1", "content", "推演秘密",
                                        "visibility", "DIRECTOR_ONLY"))));
        fixture.call(control, ExternalControlToolSet.TF_OPEN_TURN,
                ControlTestFixture.args("minutes", 60, "expectedRevision", 2L,
                        "idempotencyKey", "reject-open", "reason", "开启"));
        CommandResult request = fixture.engine.requestBrief(
                new Actor("p1", ActorRole.PLAYER), "秘密是什么？",
                fixture.status().turn().revision(), "reject-request");
        assertThat(request.ok()).isTrue();
        String requestId = request.referenceId().orElseThrow();
        long turnRevisionBefore = fixture.status().turn().revision();
        int eventsBefore = fixture.store.size();

        ToolResult denied = fixture.call(control, ExternalControlToolSet.TF_ISSUE_BRIEF,
                ControlTestFixture.args("requestId", requestId, "content", "不能泄露",
                        "sourceFactIds", List.of("secret-1"),
                        "expectedRevision", turnRevisionBefore,
                        "idempotencyKey", "reject-issue", "reason", "越权来源"));

        assertThat(denied.code()).isEqualTo("FORBIDDEN");
        assertThat(fixture.status().turn().revision()).isEqualTo(turnRevisionBefore);
        assertThat(fixture.store.size()).isEqualTo(eventsBefore);
        assertThat(fixture.engine.infoRequest(requestId).orElseThrow().status())
                .isEqualTo(InfoRequestStatus.REQUESTED);
    }

    @Test
    void supervisorCanIssueBriefButDecisionConfirmationRemainsPlayerOnly() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "P1", "expectedRevision", 0L,
                        "idempotencyKey", "role-reg", "reason", "注册"));
        fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", 1L, "expectedWorldVersion", 0L,
                        "idempotencyKey", "role-load", "reason", "装载",
                        "facts", List.of(Map.of("factId", "public-1", "content", "公开",
                                "visibility", "PUBLIC"))));
        fixture.call(control, ExternalControlToolSet.TF_OPEN_TURN,
                ControlTestFixture.args("minutes", 60, "expectedRevision", 2L,
                        "idempotencyKey", "role-open", "reason", "开启"));

        CommandResult request = fixture.engine.requestBrief(
                new Actor("p1", ActorRole.PLAYER), "问题",
                fixture.status().turn().revision(), "role-request");
        assertThat(request.ok()).isTrue();
        String requestId = request.referenceId().orElseThrow();
        long turnRevisionBefore = fixture.status().turn().revision();
        int eventsBefore = fixture.store.size();

        ToolResult issued = fixture.call(control, ExternalControlToolSet.TF_ISSUE_BRIEF,
                ControlTestFixture.args("requestId", requestId, "content", "答复",
                        "sourceFactIds", List.of("public-1"),
                        "expectedRevision", turnRevisionBefore,
                        "idempotencyKey", "role-issue", "reason", "监督签发"));

        assertThat(issued.code()).isNull();
        assertThat(fixture.store.size()).isEqualTo(eventsBefore + 1);
        assertThat(fixture.engine.infoRequest(requestId).orElseThrow().status())
                .isEqualTo(InfoRequestStatus.ISSUED);
        assertThat(control.names())
                .doesNotContain("tf_submit_decision", "tf_confirm_decision",
                        "decision_submit", "decision_confirm");
    }
}
