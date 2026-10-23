package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实引擎上的写工具状态机、revision、幂等与三要素契约。 */
class ExternalControlWriteFlowTest {

    @Test
    void fullHappyPathRunsThroughControlToolsOnly() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult registerPlayer = fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "Player One", "expectedRevision", 0L,
                        "idempotencyKey", "reg-p1", "reason", "注册玩家"));
        assertThat(registerPlayer.code()).isNull();

        ToolResult registerDirector = fixture.call(control, ExternalControlToolSet.TF_REGISTER_DIRECTOR,
                ControlTestFixture.args("qqId", "qq-d1", "playerId", "d1",
                        "displayName", "Director", "expectedRevision", 1L,
                        "idempotencyKey", "reg-d1", "reason", "注册推演"));
        assertThat(registerDirector.code()).isNull();

        ToolResult load = fixture.call(control, ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ControlTestFixture.args("expectedRevision", 2L, "expectedWorldVersion", 0L,
                        "idempotencyKey", "load-1", "reason", "装载世界",
                        "facts", List.of(
                                Map.of("factId", "f-public", "content", "港口公开事实",
                                        "visibility", "PUBLIC"),
                                Map.of("factId", "f-secret", "content", "推演秘密事实",
                                        "visibility", "DIRECTOR_ONLY"),
                                Map.of("factId", "f-p1", "content", "玩家一独有事实",
                                        "visibility", Map.of("type", "PLAYERS",
                                                "playerIds", List.of("p1"))))));
        assertThat(load.code()).isNull();
        assertThat(fixture.status().worldVersion()).isEqualTo(1);

        ToolResult open = fixture.call(control, ExternalControlToolSet.TF_OPEN_TURN,
                ControlTestFixture.args("minutes", 30, "expectedRevision", 3L,
                        "idempotencyKey", "open-1", "reason", "开启回合"));
        assertThat(open.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.OPEN);

        ToolResult lock = fixture.call(control, ExternalControlToolSet.TF_LOCK_TURN,
                ControlTestFixture.args("expectedRevision", 1L,
                        "idempotencyKey", "lock-1", "reason", "冻结回合"));
        assertThat(lock.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.LOCKED);
        assertThat(fixture.status().turn().hasSnapshot()).isTrue();

        ToolResult start = fixture.call(control, ExternalControlToolSet.TF_START_ADJUDICATION,
                ControlTestFixture.args("expectedRevision", 2L,
                        "idempotencyKey", "start-1", "reason", "启动推演"));
        assertThat(start.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.ADJUDICATING);

        ToolResult commit = fixture.call(control, ExternalControlToolSet.TF_COMMIT_ADJUDICATION,
                ControlTestFixture.args("expectedRevision", 3L,
                        "idempotencyKey", "commit-1", "reason", "提交推演",
                        "publicNarrative", "港口事件尘埃落定。",
                        "playerOutcomes", Map.of(),
                        "worldDeltas", List.of(
                                Map.of("type", "SET_FLAG", "targetId", "harbor",
                                        "value", "investigated")),
                        "nextHooks", List.of("潮汐将在下一回合改变")));
        assertThat(commit.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.COMMITTED);
        assertThat(fixture.status().worldVersion()).isEqualTo(2);

        ToolResult publish = fixture.call(control, ExternalControlToolSet.TF_PUBLISH_TURN,
                ControlTestFixture.args("expectedRevision", 4L,
                        "idempotencyKey", "publish-1", "reason", "发布回合"));
        assertThat(publish.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.PUBLISHED);

        assertThat(fixture.store.events()).extracting(event -> event.type())
                .containsExactly(EventType.ACTOR_REGISTERED, EventType.ACTOR_REGISTERED,
                        EventType.WORLD_FACTS_LOADED, EventType.TURN_OPENED,
                        EventType.TURN_LOCKED, EventType.ADJUDICATION_STARTED,
                        EventType.ADJUDICATION_COMMITTED, EventType.TURN_PUBLISHED);
        assertThat(fixture.store.events()).extracting(event -> event.actorRole().name())
                .allMatch("SUPERVISOR"::equals);
    }

    @Test
    void everyWriteToolRejectsMissingEnvelopeWithoutSideEffects() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        long engineRevisionBefore = fixture.status().engineRevision();
        int eventCountBefore = fixture.store.size();

        List<Map<String, Object>> incomplete = List.of(
                Map.of(),
                Map.of("idempotencyKey", "only-key", "reason", "原因"),
                Map.of("expectedRevision", 0L, "reason", "原因"),
                Map.of("expectedRevision", 0L, "idempotencyKey", "only-key"));

        for (String name : ExternalControlToolSet.WRITE_TOOL_NAMES) {
            for (Map<String, Object> args : incomplete) {
                ToolResult result = fixture.call(control, name, args);
                assertThat(result.code())
                        .as("工具 %s 的缺失三要素参数 %s", name, args)
                        .isEqualTo("INVALID_ARGUMENTS");
            }
        }

        assertThat(fixture.status().engineRevision()).isEqualTo(engineRevisionBefore);
        assertThat(fixture.store.size()).isEqualTo(eventCountBefore);
    }

    @Test
    void duplicateIdempotencyKeyDoesNotRunEngineWriteTwice() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        Map<String, Object> args = ControlTestFixture.args(
                "qqId", "qq-p1", "playerId", "p1", "displayName", "Player One",
                "expectedRevision", 0L, "idempotencyKey", "same-key", "reason", "注册玩家");

        ToolResult first = fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER, args);
        ToolResult second = fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER, args);

        assertThat(first.code()).isNull();
        assertThat(second.code()).isNull();
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(fixture.status().playerCount()).isEqualTo(1);
        assertThat(fixture.status().engineRevision()).isEqualTo(1);
        assertThat(fixture.store.size()).isEqualTo(1);
    }

    @Test
    void revisionConflictIsRejectedWithZeroSideEffects() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "Player One", "expectedRevision", 99L,
                        "idempotencyKey", "conflict-key", "reason", "错误 revision"));

        assertThat(result.code()).isEqualTo("REVISION_CONFLICT");
        assertThat(fixture.store.size()).isZero();
        assertThat(fixture.status().playerCount()).isZero();
        assertThat(fixture.status().engineRevision()).isZero();
    }

    @Test
    void wrongStateIsRejectedWithZeroSideEffects() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_LOCK_TURN,
                ControlTestFixture.args("expectedRevision", 0L,
                        "idempotencyKey", "wrong-state-key", "reason", "无回合冻结"));

        assertThat(result.code()).isEqualTo("WRONG_STATE");
        assertThat(fixture.store.size()).isZero();
        assertThat(fixture.status().engineRevision()).isZero();
        assertThat(fixture.status().turn()).isNull();
    }

    @Test
    void failedAdjudicationCanBeRetriedOnlyThroughCorrectStateSequence() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        assertThat(fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "P1", "expectedRevision", 0L,
                        "idempotencyKey", "fr-reg", "reason", "注册")).code()).isNull();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_OPEN_TURN,
                ControlTestFixture.args("minutes", 30, "expectedRevision", 1L,
                        "idempotencyKey", "fr-open", "reason", "开启")).code()).isNull();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_LOCK_TURN,
                ControlTestFixture.args("expectedRevision", 1L,
                        "idempotencyKey", "fr-lock", "reason", "冻结")).code()).isNull();
        assertThat(fixture.call(control, ExternalControlToolSet.TF_START_ADJUDICATION,
                ControlTestFixture.args("expectedRevision", 2L,
                        "idempotencyKey", "fr-start", "reason", "启动")).code()).isNull();

        long worldBefore = fixture.status().worldVersion();
        ToolResult failed = fixture.call(control, ExternalControlToolSet.TF_FAIL_ADJUDICATION,
                ControlTestFixture.args("expectedRevision", 3L,
                        "idempotencyKey", "fr-fail", "reason", "模型输出无法解析"));
        assertThat(failed.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.FAILED_RETRYABLE);
        assertThat(fixture.status().worldVersion()).isEqualTo(worldBefore);

        ToolResult retried = fixture.call(control, ExternalControlToolSet.TF_RETRY_ADJUDICATION,
                ControlTestFixture.args("expectedRevision", 4L,
                        "idempotencyKey", "fr-retry", "reason", "修正后重试"));
        assertThat(retried.code()).isNull();
        assertThat(fixture.status().turn().state()).isEqualTo(TurnState.ADJUDICATING);
        assertThat(fixture.status().worldVersion()).isEqualTo(worldBefore);
    }

    @Test
    void invalidOpenMinutesIsRejectedBeforeEngineCall() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_OPEN_TURN,
                ControlTestFixture.args("minutes", 241, "expectedRevision", 0L,
                        "idempotencyKey", "bad-minutes", "reason", "非法分钟"));

        assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(fixture.status().engineRevision()).isZero();
        assertThat(fixture.store.size()).isZero();
    }
}
