package io.mosire.iibot.app.runtime;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.event.SqliteEventReadPort;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.agent.DecisionDraftCreateTool;
import io.mosire.iibot.taskflow.agent.SubmitAdjudicationTool;
import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppRuntimeFakeFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void fullOfflineCommandsFlowThroughDispatcherOutboxAndPersistedAudit() throws Exception {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"},
                  {"factId": "director-1", "content": "推演专用秘密", "visibility": "DIRECTOR_ONLY"}
                ]}
                """);
        Path dataDir = tempDir.resolve("data");
        Path configFile = TestSupport.writeConfig(tempDir.resolve("iibot.json"),
                TestSupport.validConfigNode(dataDir, factsFile));
        AppConfig config = TestSupport.loadConfig(configFile);

        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.toolCall("draft-call", DecisionDraftCreateTool.NAME, Map.of(
                        "intent", "调查失踪船队",
                        "actions", List.of("询问船工", "搜查仓库"),
                        "resources", Map.of("people", "3"),
                        "target", "北码头",
                        "contingency", "遇到危险立即撤回")),
                LlmResponse.text("草稿已生成，请在私聊确认。"),
                LlmResponse.toolCall("submit-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "船队失踪的线索指向北码头。",
                        "playerOutcomes", Map.of("player-1", "你找到了航海日志。"),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "north-pier", "value", "investigated")),
                        "nextHooks", List.of("航海日志提到暴风前夜"))),
                LlmResponse.text("推演完成。"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        SecretProvider secrets = SecretProvider.of(Map.of("IIBOT_NAPCAT_TOKEN", "napcat-token"));

        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets, Clock.systemUTC(), llm, adapter));
        try {
            runtime.start();
            assertThat(runtime.engine().status().worldVersion()).isEqualTo(1);
            assertThat(runtime.engine().status().playerCount()).isEqualTo(2);

            send(runtime, adapter, "qq-p1", "/帮助", "m-help");
            assertThat(lastText(adapter)).contains("玩家命令");

            send(runtime, adapter, "qq-director", "/开回合 30", "m-open");
            assertThat(lastText(adapter)).contains("回合已开启");

            send(runtime, adapter, "qq-p1", "帮我起草调查失踪船队的决策", "m-chat");
            assertThat(lastText(adapter)).contains("草稿已生成");
            DecisionDraft draft = runtime.engine().turnView().orElseThrow().drafts().get(0);
            assertThat(draft.playerId()).isEqualTo("player-1");

            send(runtime, adapter, "qq-p1", "/确认 " + draft.draftId(), "m-confirm");
            assertThat(lastText(adapter)).contains("已确认决策");

            send(runtime, adapter, "qq-director", "/冻结", "m-lock");
            assertThat(lastText(adapter)).contains("回合已冻结");
            assertThat(runtime.engine().turnView().orElseThrow().state()).isEqualTo(TurnState.LOCKED);

            send(runtime, adapter, "qq-director", "/推演", "m-adjudicate");
            assertThat(lastText(adapter)).contains("推演已提交");
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.COMMITTED);
            assertThat(runtime.engine().status().worldVersion()).isEqualTo(2);

            send(runtime, adapter, "qq-director", "/发布", "m-publish");
            assertThat(allTexts(adapter)).anySatisfy(text -> assertThat(text).contains("回合已发布"));
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.PUBLISHED);

            // 7 条已登记私聊命令各产生 1 条 ACK；发布应答、公开叙事与玩家私密结果仍各 1 条。
            assertThat(adapter.sent()).hasSize(16);
            assertThat(adapter.sent()).filteredOn(message ->
                            message.textSegments().contains("消息已收到，秘书正在处理，请稍候。"))
                    .hasSize(3);
            assertThat(adapter.sent()).filteredOn(message ->
                            message.textSegments().contains("消息已收到，GM 正在处理，请稍候。"))
                    .hasSize(4);
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.GROUP);
                assertThat(message.targetId()).isEqualTo("group-public");
                assertThat(message.textSegments()).containsExactly("船队失踪的线索指向北码头。");
            });
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
                assertThat(message.targetId()).isEqualTo("qq-p1");
                assertThat(message.textSegments()).containsExactly("你找到了航海日志。");
            });

            // Docs 真实落盘：bible.md 存在且内容来自公开事实。
            Path bible = dataDir.resolve("world").resolve("bible.md");
            assertThat(bible).isRegularFile();
            assertThat(Files.readString(bible)).contains("港口迷雾").doesNotContain("推演专用秘密");
        } finally {
            runtime.close();
        }

        // 事件审计跨重启可读；本实现只做审计，不重建引擎。
        try (SqliteEventReadPort port = new SqliteEventReadPort(dataDir.resolve("events.db"))) {
            List<TaskflowEvent> events = port.readAfter(0, 1000);
            assertThat(events).extracting(TaskflowEvent::type)
                    .contains(EventType.ACTOR_REGISTERED, EventType.WORLD_FACTS_LOADED,
                            EventType.TURN_OPENED, EventType.DRAFT_CREATED,
                            EventType.DECISION_CONFIRMED, EventType.TURN_LOCKED,
                            EventType.ADJUDICATION_COMMITTED, EventType.TURN_PUBLISHED);
        }
        assertThat(adapter.isClosed()).isTrue();
    }

    private static void send(AppRuntime runtime, FakeMessagingAdapter adapter, String qqId,
                             String text, String dedupeKey) {
        int before = adapter.sent().size();
        assertThat(runtime.submit(TestSupport.privateMessage(qqId, text, dedupeKey))).isTrue();
        assertThat(runtime.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(adapter.sent()).as("每条命令应产生一条回复").hasSizeGreaterThan(before);
    }

    private static String lastText(FakeMessagingAdapter adapter) {
        List<OutboundMessage> sent = adapter.sent();
        return String.join("\n", sent.get(sent.size() - 1).textSegments());
    }

    private static List<String> allTexts(FakeMessagingAdapter adapter) {
        return adapter.sent().stream()
                .map(message -> String.join("\n", message.textSegments()))
                .toList();
    }
}
