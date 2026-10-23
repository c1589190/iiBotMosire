package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GM Agent 只能通过唯一提交工具落盘，非法/缺失调用零世界变更。 */
class AdjudicationServiceTest {

    @Test
    void validToolCallCommitsProposalAndAdvancesWorldVersion() {
        AgentTestFixture f = lockedFixture();
        long worldBefore = f.engine.status().worldVersion();
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("adj-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "港口的迷雾散去。",
                        "playerOutcomes", Map.of("player-1", "你找到了暗门。"),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "harbor", "value", "opened")),
                        "nextHooks", List.of("王宫开始行动"))),
                LlmResponse.text("推演完成。"));
        AdjudicationService service = new AdjudicationService(f.engine, llm);

        AdjudicationResult result = service.adjudicate(f.gm, f.loadedFacts);

        assertThat(result.committed()).isTrue();
        assertThat(result.turnState()).isEqualTo(TurnState.COMMITTED);
        assertThat(f.engine.status().worldVersion()).isEqualTo(worldBefore + 1);
        assertThat(result.toolInvocations()).hasSize(1);
        assertThat(result.toolInvocations().get(0).result().success()).isTrue();
        assertThat(result.toolInvocations().get(0).result().message())
                .contains("proposalHash").contains("worldVersion");
        assertThat(f.engine.turnView().orElseThrow().committedProposal()).isNotNull();

        SubmitAdjudicationTool declared = new SubmitAdjudicationTool(f.engine, f.gm,
                f.turn().turnId(), worldBefore);
        assertThat(declared.spec().sensitive()).isTrue();
        assertThat(declared.spec().destructive()).isTrue();
        assertThat(declared.roles()).containsExactlyInAnyOrder("gm", "director", "supervisor");
    }

    @Test
    void missingToolCallFailsExplicitlyWithZeroWorldChange() {
        AgentTestFixture f = lockedFixture();
        long worldBefore = f.engine.status().worldVersion();
        ScriptedLlm llm = ScriptedLlm.with(LlmResponse.text("我不提交。"));
        AdjudicationService service = new AdjudicationService(f.engine, llm);

        AdjudicationResult result = service.adjudicate(f.gm, f.loadedFacts);

        assertThat(result.committed()).isFalse();
        assertThat(result.turnState()).isEqualTo(TurnState.FAILED_RETRYABLE);
        assertThat(result.failureReason()).contains("未提交有效推演提案");
        assertThat(f.engine.status().worldVersion()).isEqualTo(worldBefore);
        assertThat(f.engine.turnView().orElseThrow().committedProposal()).isNull();
    }

    @Test
    void invalidToolArgumentsFailExplicitlyWithZeroWorldChange() {
        AgentTestFixture f = lockedFixture();
        long worldBefore = f.engine.status().worldVersion();
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("bad-call", SubmitAdjudicationTool.NAME, Map.of(
                        "playerOutcomes", Map.of(),
                        "worldDeltas", List.of(),
                        "nextHooks", List.of())),
                LlmResponse.text("提交失败。"));
        AdjudicationService service = new AdjudicationService(f.engine, llm);

        AdjudicationResult result = service.adjudicate(f.gm, f.loadedFacts);

        assertThat(result.committed()).isFalse();
        assertThat(result.turnState()).isEqualTo(TurnState.FAILED_RETRYABLE);
        assertThat(result.failureReason()).contains("未提交有效推演提案");
        assertThat(result.toolInvocations()).hasSize(1);
        assertThat(result.toolInvocations().get(0).result().success()).isFalse();
        assertThat(f.engine.status().worldVersion()).isEqualTo(worldBefore);
    }

    private static AgentTestFixture lockedFixture() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("public-1", 1, "港口迷雾", Visibility.PUBLIC));
        f.openTurn();
        var draft = f.createDraftDirect(f.p1, "调查港口");
        f.confirmDirect(f.p1, draft);
        f.lockDirect();
        return f;
    }
}
