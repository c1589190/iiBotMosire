package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.MockNapCatServer;
import io.mosire.iibot.app.support.MockNapCatServer.HttpAction;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.AdapterStatus.ConnectionState;
import io.mosire.iibot.connect.napcat.NapCatAdapter;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.agent.SubmitAdjudicationTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App 级真实链路 E2E：不使用 {@code FakeMessagingAdapter}，而是让组合根按配置构造真实
 * {@link NapCatAdapter}，连接本地 Mock NapCat 的 Java-WebSocket 事件通道，并通过 JDK
 * {@code HttpServer} 完成 OneBot 11 HTTP Action。验证 connect 工作线程、WS 事件解析、
 * 身份映射、LLM（Fake 脚本）、Outbox 与回合结果分发不是靠 Fake 拼出来的。
 *
 * <p>全程离线：两个 mock 只绑定 127.0.0.1 随机端口，不访问真实 QQ 或真实模型。</p>
 */
class AppRuntimeNapCatE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCESS_TOKEN = "napcat-token";
    private static final String TOKEN_ENV = "IIBOT_NAPCAT_TOKEN";

    @TempDir
    Path tempDir;

    @Test
    void realNapCatAdapterDrivesFullTurnThroughMockWebSocketAndHttpActions() throws Exception {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"},
                  {"factId": "director-1", "content": "推演专用秘密", "visibility": "DIRECTOR_ONLY"}
                ]}
                """);
        Path dataDir = tempDir.resolve("data");

        try (MockNapCatServer mock = new MockNapCatServer()) {
            ObjectNode root = TestSupport.validConfigNode(dataDir, factsFile);
            ObjectNode napcat = (ObjectNode) root.get("napcat");
            napcat.put("eventWebSocketUri", mock.eventWebSocketUri().toString());
            napcat.put("httpBaseUri", mock.httpBaseUri().toString());
            napcat.put("accessTokenEnv", TOKEN_ENV);
            AppConfig config = TestSupport.loadConfig(
                    TestSupport.writeConfig(tempDir.resolve("iibot.json"), root));

            // 只允许指向本地 mock：证明没有真实网络/真实 QQ 目标。
            assertThat(config.napcat().eventWebSocketUri().getHost()).isEqualTo("127.0.0.1");
            assertThat(config.napcat().httpBaseUri().getHost()).isEqualTo("127.0.0.1");
            assertThat(config.napcat().eventWebSocketUri().getPort())
                    .isEqualTo(mock.eventWebSocketUri().getPort());
            assertThat(config.napcat().httpBaseUri().getPort())
                    .isEqualTo(mock.httpBaseUri().getPort());

            FakeLlmClient llm = FakeLlmClient.with(
                    LlmResponse.text("行动草稿请通过工单提交给 GM。"),
                    LlmResponse.toolCall("submit-call", SubmitAdjudicationTool.NAME, Map.of(
                            "publicNarrative", "船队失踪的线索指向北码头。",
                            "playerOutcomes", Map.of("player-1", "你找到了航海日志。"),
                            "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                    "targetId", "north-pier", "value", "investigated")),
                            "nextHooks", List.of("航海日志提到暴风前夜"))),
                    LlmResponse.text("推演完成。"));
            SecretProvider secrets = SecretProvider.of(Map.of(TOKEN_ENV, ACCESS_TOKEN));

            // adapter = null：组合根必须按配置构造真实 NapCatAdapter。
            AppRuntime runtime = AppBootstrap.build(config, new BootstrapOptions(
                    secrets, Clock.systemUTC(), llm, null, NoopControlPlane.INSTANCE));
            try {
                runtime.start();
                assertThat(runtime.adapter()).isInstanceOf(NapCatAdapter.class);
                assertThat(await(
                        () -> runtime.adapter().status().state() == ConnectionState.CONNECTED,
                        Duration.ofSeconds(10)))
                        .as("真实 NapCatAdapter 应连上本地 mock WS")
                        .isTrue();
                assertThat(mock.awaitWebSocketConnection(Duration.ofSeconds(5))).isTrue();
                assertThat(mock.wsAuthorization()).as("WS 握手 Bearer 认证")
                        .isEqualTo("Bearer " + ACCESS_TOKEN);
                assertThat(mock.wsResourceDescriptor()).as("WS query token 认证")
                        .contains("access_token=" + ACCESS_TOKEN);

                // 1) 玩家 /帮助 —— 走 WS 事件 + 真实 HTTP 回复
                pushAndAwait(runtime, mock, "qq-p1", "/帮助", 1001L, 1);
                // 2) 推演 /开回合 30
                pushAndAwait(runtime, mock, "qq-director", "/开回合 30", 1002L, 1);
                // 3) 玩家自然语言聊天走 Ticket 默认面；旧 Turn 链路直接经引擎准备草稿。
                pushAndAwait(runtime, mock, "qq-p1", "帮我起草调查失踪船队的决策", 1003L, 1);
                var opened = runtime.engine().turnView().orElseThrow();
                var createdDraft = runtime.engine().createDecisionDraft(
                        new Actor("player-1", ActorRole.PLAYER),
                        new DecisionDraftInput("调查失踪船队", List.of("询问船工", "搜查仓库"),
                                Map.of("people", "3"), "北码头", "遇到危险立即撤回", opened.closesAt()),
                        opened.revision(), "napcat-e2e-draft");
                assertThat(createdDraft.ok()).isTrue();
                DecisionDraft draft = runtime.engine().draft(
                        createdDraft.referenceId().orElseThrow()).orElseThrow();
                assertThat(draft.playerId()).isEqualTo("player-1");

                // 4) 玩家硬确认，draftId 来自真实引擎
                pushAndAwait(runtime, mock, "qq-p1", "/确认 " + draft.draftId(), 1004L, 1);
                // 5) 推演冻结
                pushAndAwait(runtime, mock, "qq-director", "/冻结", 1005L, 1);
                assertThat(runtime.engine().turnView().orElseThrow().state())
                        .isEqualTo(TurnState.LOCKED);
                // 6) 推演（Fake LLM: submit_adjudication toolCall + final text）
                pushAndAwait(runtime, mock, "qq-director", "/推演", 1006L, 1);
                assertThat(runtime.engine().turnView().orElseThrow().state())
                        .isEqualTo(TurnState.COMMITTED);
                // 7) 发布：命令回复 + 公开群叙事 + 玩家私密结果 = 3 条 HTTP 动作
                pushAndAwait(runtime, mock, "qq-director", "/发布", 1007L, 3);

                assertThat(runtime.awaitIdle(Duration.ofSeconds(10))).isTrue();
                assertThat(runtime.engine().turnView().orElseThrow().state())
                        .isEqualTo(TurnState.PUBLISHED);
                assertThat(runtime.engine().status().worldVersion()).isEqualTo(2);
                assertThat(llm.requests()).as("Ticket 默认聊天一次 + 推演两次 LLM 往返").hasSize(3);

                List<HttpAction> sends = mock.sendActions();
                assertThat(sends).as("7 条命令回复 + 公开群结果 + 玩家私密结果")
                        .hasSizeGreaterThanOrEqualTo(9);
                assertThat(mock.allActions())
                        .allSatisfy(action -> assertThat(action.path()).isIn(
                                "/send_msg", "/send_private_msg", "/send_group_msg",
                                "/get_login_info"));

                // 真实 adapter 对每个 action 都带 Bearer；App 当前默认 SEND_MSG 模式。
                assertThat(sends).allSatisfy(action -> {
                    assertThat(action.authorization()).isEqualTo("Bearer " + ACCESS_TOKEN);
                    assertThat(action.path())
                            .as("AppBootstrap 未覆盖 sendActionMode，默认 NapCatConfig.SendActionMode.SEND_MSG")
                            .isEqualTo("/send_msg");
                    JsonNode message = action.payload().path("message");
                    assertThat(message.isArray()).as("message 必须是 OneBot array").isTrue();
                    assertThat(message).isNotEmpty();
                    assertThat(message.get(0).path("type").asText()).isEqualTo("text");
                    assertThat(message.get(0).path("data").path("text").asText()).isNotBlank();
                });

                // 7 条命令回复（私聊回原用户）。
                assertPrivateSend(sends, "qq-p1", "玩家命令");
                assertPrivateSend(sends, "qq-director", "回合已开启");
                assertPrivateSend(sends, "qq-p1", "行动草稿请通过工单提交给 GM");
                assertPrivateSend(sends, "qq-p1", "已确认决策");
                assertPrivateSend(sends, "qq-director", "回合已冻结");
                assertPrivateSend(sends, "qq-director", "推演已提交");
                assertPrivateSend(sends, "qq-director", "回合已发布");

                // 公开群叙事 + 玩家私密 outcome。
                assertThat(sends).anySatisfy(action -> {
                    assertThat(action.isGroupTarget()).isTrue();
                    assertThat(action.payload().path("group_id").asText())
                            .isEqualTo("group-public");
                    assertThat(action.messageText()).contains("船队失踪的线索指向北码头");
                });
                assertPrivateSend(sends, "qq-p1", "你找到了航海日志");
            } finally {
                runtime.close();
            }
            assertThat(runtime.adapter().status().state()).isEqualTo(ConnectionState.CLOSED);
        }
    }

    private static void pushAndAwait(AppRuntime runtime, MockNapCatServer mock, String qqId,
                                     String text, long messageId, int expectedNewSends)
            throws Exception {
        int before = mock.sendActions().size();
        mock.broadcastEvent(privateMessageEvent(qqId, text, messageId));
        assertThat(await(() -> mock.sendActions().size() >= before + expectedNewSends,
                Duration.ofSeconds(10)))
                .as("等待出站 HTTP 动作增加 %d 条（message_id=%d）", expectedNewSends, messageId)
                .isTrue();
        assertThat(runtime.awaitIdle(Duration.ofSeconds(10))).as("入站 mailbox 应清空").isTrue();
    }

    private static void assertPrivateSend(List<HttpAction> sends, String userId, String contains) {
        assertThat(sends).anySatisfy(action -> {
            assertThat(action.isPrivateTarget())
                    .as("应为私聊动作 path=%s message_type=%s", action.path(),
                            action.payload().path("message_type").asText())
                    .isTrue();
            assertThat(action.payload().path("user_id").asText()).isEqualTo(userId);
            assertThat(action.messageText()).contains(contains);
        });
    }

    /** 构造字段完整的 OneBot 11 私聊 message 事件。 */
    private static String privateMessageEvent(String userId, String text, long messageId) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("time", 1_700_000_000L + messageId);
        event.put("self_id", "bot-1");
        event.put("post_type", "message");
        event.put("message_type", "private");
        event.put("sub_type", "friend");
        event.put("message_id", messageId);
        event.put("user_id", userId);
        ArrayNode message = event.putArray("message");
        ObjectNode segment = message.addObject();
        segment.put("type", "text");
        segment.putObject("data").put("text", text);
        event.put("raw_message", text);
        event.put("font", 0);
        ObjectNode sender = event.putObject("sender");
        sender.put("user_id", userId);
        sender.put("nickname", "mock-" + userId);
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("构造 OneBot 事件失败", failure);
        }
    }

    private static boolean await(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
