package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 全组合根重启 smoke：已发布回合结果不得因进程内状态丢失而再次外发。 */
class AppBootstrapDeliveryRestartTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_NAPCAT_TOKEN", "napcat-secret-value",
            "IIBOT_LLM_KEY", "llm-secret-value"));

    @TempDir
    Path tempDir;

    @Test
    void publishedTurnResultsAreNotResentAfterFullAssemblyRestart() throws Exception {
        Path factsFile = TestSupport.writeFacts(
                tempDir.resolve("world").resolve("facts.json"), """
                        {"facts": [
                          {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"},
                          {"factId": "director-1", "content": "推演专用秘密", "visibility": "DIRECTOR_ONLY"}
                        ]}
                        """);
        Path dataDir = tempDir.resolve("data");
        AppConfig config = TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot.json"),
                TestSupport.validConfigNode(dataDir, factsFile)));

        String turnId;
        FakeMessagingAdapter firstAdapter = new FakeMessagingAdapter();
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(), firstAdapter));
        try {
            first.start();
            turnId = publishTurnDirectly(first.engine());
            assertThat(first.submit(TestSupport.privateMessage(
                    "qq-director", "/状态", "before-restart-status"))).isTrue();
            assertThat(first.awaitIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(sentTexts(firstAdapter))
                    .contains("港口恢复了平静。", "你找到了航海日志。")
                    .anyMatch(text -> text.contains("任务流状态"));
        } finally {
            first.close();
        }

        Path ledgerFile = dataDir.resolve("delivery-ledger.json");
        assertThat(ledgerFile).isRegularFile();
        assertThat(Files.readString(ledgerFile))
                .contains("\"schemaVersion\":1",
                        "turn:" + turnId + ":public",
                        "turn:" + turnId + ":player:player-1")
                .doesNotContain("港口恢复了平静。", "你找到了航海日志。", "qq-p1", "napcat-secret-value");

        FakeMessagingAdapter secondAdapter = new FakeMessagingAdapter();
        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(), secondAdapter));
        try {
            second.start();
            assertThat(second.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.PUBLISHED);
            assertThat(second.submit(TestSupport.privateMessage(
                    "qq-director", "/状态", "after-restart-status"))).isTrue();
            assertThat(second.awaitIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(sentTexts(secondAdapter))
                    .anyMatch(text -> text.contains("任务流状态"))
                    .doesNotContain("港口恢复了平静。", "你找到了航海日志。");
        } finally {
            second.close();
        }
    }

    private static String publishTurnDirectly(TaskflowEngine engine) {
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        Actor player = new Actor("player-1", ActorRole.PLAYER);
        ok(engine.openTurn(director, engine.status().engineRevision(), "delivery-open",
                CLOCK.instant().plus(Duration.ofMinutes(30))));

        TurnView opening = engine.turnView().orElseThrow();
        CommandResult draftResult = engine.createDecisionDraft(player,
                new DecisionDraftInput("调查港口", List.of("询问船工"), Map.of("people", "1"),
                        "北码头", "遇险撤回", CLOCK.instant().plus(Duration.ofMinutes(20))),
                opening.revision(), "delivery-draft");
        ok(draftResult);
        DecisionDraft draft = engine.draft(draftResult.referenceId().orElseThrow()).orElseThrow();
        ok(engine.confirmDecision(player, draft.draftId(), draft.previewHash(),
                engine.turnView().orElseThrow().revision(), "delivery-confirm"));
        ok(engine.lockTurn(director, engine.turnView().orElseThrow().revision(),
                "delivery-lock"));
        ok(engine.startAdjudication(director, engine.turnView().orElseThrow().revision(),
                "delivery-start"));
        TurnView adjudicating = engine.turnView().orElseThrow();
        AdjudicationProposal proposal = AdjudicationProposal.create(
                AdjudicationProposal.CURRENT_SCHEMA_VERSION, adjudicating.turnId(),
                engine.status().worldVersion(), "港口恢复了平静。",
                Map.of("player-1", "你找到了航海日志。"),
                List.of(new WorldDelta("SET_FLAG", "harbor", "calm")),
                List.of("潮汐将在下一回合改变"));
        ok(engine.commitAdjudication(director, adjudicating.revision(),
                "delivery-commit", proposal));
        ok(engine.publishTurn(director, engine.turnView().orElseThrow().revision(),
                "delivery-publish"));
        assertThat(engine.turnView().orElseThrow().state()).isEqualTo(TurnState.PUBLISHED);
        return adjudicating.turnId();
    }

    private static List<String> sentTexts(FakeMessagingAdapter adapter) {
        return adapter.sent().stream()
                .flatMap(message -> message.textSegments().stream())
                .toList();
    }

    private static void ok(CommandResult result) {
        assertThat(result.ok()).as("command %s: %s", result.code(), result.message()).isTrue();
    }
}
