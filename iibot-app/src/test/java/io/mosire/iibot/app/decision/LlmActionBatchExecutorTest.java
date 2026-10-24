package io.mosire.iibot.app.decision;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchStatus;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmActionBatchExecutorTest {

    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(BASE, ZoneOffset.UTC);

    @Test
    void sendsOneBoundedNoToolPromptWithWorldVersionAndAllBatchActions() {
        TaskflowEngine engine = engineWithFact();
        FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("多行动推演结果：两条都完成。"));
        LlmActionBatchExecutor executor = new LlmActionBatchExecutor(engine, llm, CLOCK);
        ActionBatch batch = batch("行动甲", "行动乙");

        ActionBatch result = executor.execute(batch);

        assertThat(result.status()).isEqualTo(ActionBatchStatus.COMMITTED);
        assertThat(result.result()).isEqualTo("多行动推演结果：两条都完成。");
        assertThat(result.turnId()).isNull();
        assertThat(llm.requests()).hasSize(1);
        String prompt = text(llm.requests().get(0).messages());
        assertThat(prompt)
                .contains("世界版本: " + engine.status().worldVersion())
                .contains("雾港事实", "行动甲", "行动乙")
                .contains("不要调用工具");
        assertThat(llm.requests().get(0).tools()).isEmpty();
        assertThat(llm.requests().get(0).sampling().maxTokens())
                .isEqualTo(LlmActionBatchExecutor.MAX_OUTPUT_TOKENS);
    }

    @Test
    void llmFailureOrBlankResultIsRejectedWithStableSummaryAndNeverCommitted() {
        TaskflowEngine engine = engineWithFact();
        FakeLlmClient failing = FakeLlmClient.with();
        ActionBatch failed = new LlmActionBatchExecutor(engine, failing, CLOCK)
                .execute(batch("失败行动"));
        assertThat(failed.status()).isEqualTo(ActionBatchStatus.REJECTED);
        assertThat(failed.result()).isEqualTo(LlmActionBatchExecutor.REJECT_LLM_FAILED);
        assertThat(failed.turnId()).isNull();
        assertThat(failing.requests()).hasSize(1);

        FakeLlmClient blank = FakeLlmClient.with(LlmResponse.text("   "));
        ActionBatch empty = new LlmActionBatchExecutor(engine, blank, CLOCK)
                .execute(batch("空结果行动"));
        assertThat(empty.status()).isEqualTo(ActionBatchStatus.REJECTED);
        assertThat(empty.result()).isEqualTo(LlmActionBatchExecutor.REJECT_EMPTY_RESULT);
        assertThat(empty.turnId()).isNull();
        assertThat(blank.requests()).hasSize(1);
    }

    @Test
    void committedAndRejectedBatchesCarryTheEngineCurrentTurnId() {
        TaskflowEngine engine = engineWithOpenTurn();
        String currentTurnId = engine.turnView().orElseThrow().turnId();

        ActionBatch committed = new LlmActionBatchExecutor(engine,
                FakeLlmClient.with(LlmResponse.text("当前回合推演完成。")), CLOCK)
                .execute(batch("回合内成功行动"));
        assertThat(committed.status()).isEqualTo(ActionBatchStatus.COMMITTED);
        assertThat(committed.turnId()).isEqualTo(currentTurnId);

        ActionBatch rejected = new LlmActionBatchExecutor(engine,
                FakeLlmClient.with(LlmResponse.text("   ")), CLOCK)
                .execute(batch("回合内空结果行动"));
        assertThat(rejected.status()).isEqualTo(ActionBatchStatus.REJECTED);
        assertThat(rejected.result()).isEqualTo(LlmActionBatchExecutor.REJECT_EMPTY_RESULT);
        assertThat(rejected.turnId()).isEqualTo(currentTurnId);
    }

    @Test
    void terminalTurnDoesNotBindTurnIdForCommittedOrRejectedBatch() {
        TaskflowEngine engine = engineWithTerminalTurn();
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.PUBLISHED);

        ActionBatch committed = new LlmActionBatchExecutor(engine,
                FakeLlmClient.with(LlmResponse.text("终态回合外推演结果。")), CLOCK)
                .execute(batch("终态后的行动"));
        assertThat(committed.status()).isEqualTo(ActionBatchStatus.COMMITTED);
        assertThat(committed.turnId()).isNull();

        ActionBatch rejected = new LlmActionBatchExecutor(engine,
                FakeLlmClient.with(LlmResponse.text("   ")), CLOCK)
                .execute(batch("终态后的空结果行动"));
        assertThat(rejected.status()).isEqualTo(ActionBatchStatus.REJECTED);
        assertThat(rejected.result()).isEqualTo(LlmActionBatchExecutor.REJECT_EMPTY_RESULT);
        assertThat(rejected.turnId()).isNull();
    }

    @Test
    void oversizedPromptIsRejectedWithoutCallingLlm() {
        TaskflowEngine engine = engineWithFact();
        FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("不应调用"));
        ActionBatch oversized = batch("x".repeat(LlmActionBatchExecutor.MAX_PROMPT_CHARS + 1));

        ActionBatch result = new LlmActionBatchExecutor(engine, llm, CLOCK)
                .execute(oversized);

        assertThat(result.status()).isEqualTo(ActionBatchStatus.REJECTED);
        assertThat(result.result()).isEqualTo(LlmActionBatchExecutor.REJECT_INPUT_TOO_LARGE);
        assertThat(llm.requests()).isEmpty();
    }

    private static TaskflowEngine engineWithFact() {
        TaskflowEngine engine = new TaskflowEngine(CLOCK, new InMemoryEventStore());
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        assertThat(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "register-director").ok()).isTrue();
        assertThat(engine.loadWorldFacts(director, engine.status().worldVersion(),
                List.of(new WorldFact("fact-1", engine.status().worldVersion() + 1,
                        "雾港事实：北码头封闭。", Visibility.PUBLIC)),
                engine.status().engineRevision(), "load-fact").ok()).isTrue();
        return engine;
    }

    private static TaskflowEngine engineWithOpenTurn() {
        TaskflowEngine engine = engineWithFact();
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        assertThat(engine.openTurn(director, engine.status().engineRevision(),
                "open-turn-for-executor", BASE.plusSeconds(3_600)).ok()).isTrue();
        return engine;
    }

    private static TaskflowEngine engineWithTerminalTurn() {
        TaskflowEngine engine = engineWithOpenTurn();
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        assertThat(engine.lockTurn(director, engine.turnView().orElseThrow().revision(),
                "lock-terminal-turn").ok()).isTrue();
        assertThat(engine.startAdjudication(director,
                engine.turnView().orElseThrow().revision(),
                "start-terminal-turn").ok()).isTrue();
        TurnView adjudicating = engine.turnView().orElseThrow();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION, adjudicating.turnId(),
                engine.status().worldVersion(), "终态回合裁定已完成。", Map.of(),
                List.of(new WorldDelta("SET_FLAG", "p6-terminal", "done")), List.of());
        assertThat(engine.commitAdjudication(director, adjudicating.revision(),
                "commit-terminal-turn", proposal).ok()).isTrue();
        assertThat(engine.publishTurn(director, engine.turnView().orElseThrow().revision(),
                "publish-terminal-turn").ok()).isTrue();
        return engine;
    }

    private static ActionBatch batch(String... texts) {
        return new ActionBatch("batch-test", "pkg-test", null,
                java.util.stream.IntStream.range(0, texts.length)
                        .mapToObj(index -> "act-" + index).toList(),
                List.of(texts),
                java.util.stream.IntStream.range(0, texts.length)
                        .mapToObj(index -> "player-1").toList(),
                BASE, ActionBatchStatus.PENDING, null);
    }

    private static String text(List<LlmMessage> messages) {
        StringBuilder result = new StringBuilder();
        for (LlmMessage message : messages) {
            for (ContentPart part : message.content()) {
                if (part instanceof ContentPart.Text text) {
                    result.append(text.text()).append('\n');
                }
            }
        }
        return result.toString();
    }
}
