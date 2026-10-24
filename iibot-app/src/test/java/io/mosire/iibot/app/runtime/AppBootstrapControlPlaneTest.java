package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.mcp.ControlBootstrapMcpTools;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.GmPublicationMcpTools;
import io.mosire.iibot.app.mcp.GmRecordMcpTools;
import io.mosire.iibot.app.mcp.GmAideChatTool;
import io.mosire.iibot.app.mcp.GmPrivateDocsMcpTools;
import io.mosire.iibot.app.mcp.GmSecretaryChatTool;
import io.mosire.iibot.app.mcp.GmSecretaryDispatchTool;
import io.mosire.iibot.app.mcp.GmSecretaryListTool;
import io.mosire.iibot.app.mcp.GmSecretaryReadTool;
import io.mosire.iibot.app.mcp.GmPostDecideTool;
import io.mosire.iibot.app.mcp.GmPostGetTool;
import io.mosire.iibot.app.mcp.GmPostListTool;
import io.mosire.iibot.app.mcp.GmPostPublishTool;
import io.mosire.iibot.app.mcp.ControlInitializeTool;
import io.mosire.iibot.app.mcp.GmSharedSkillMcpTools;
import io.mosire.iibot.app.mcp.GmComplaintMcpTools;
import io.mosire.iibot.app.mcp.GmTicketMcpTools;
import io.mosire.iibot.app.mcp.RunAdjudicationAgentTool;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.agent.SubmitAdjudicationTool;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintDeliveryState;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AppBootstrap 控制面接线：48 工具 + 回合/推文经真实分发器出站。 */
@Timeout(60)
class AppBootstrapControlPlaneTest {

    private static final String CONTROL_TOKEN = "bootstrap-control-token-30c6e1ac";
    private static final String CONTROL_PATH = "/control/mcp";

    @TempDir
    Path tempDir;

    @Test
    void controlPlaneExposesAgentToolAndPublishesThroughResultDistributor() throws Exception {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"},
                  {"factId": "director-1", "content": "推演专用秘密", "visibility": "DIRECTOR_ONLY"}
                ]}
                """);
        Path dataDir = tempDir.resolve("data");
        ObjectNode configNode = TestSupport.validConfigNode(dataDir, factsFile);
        ObjectNode control = configNode.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        AppConfig config = TestSupport.loadConfig(
                TestSupport.writeConfig(tempDir.resolve("iibot.json"), configNode));

        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.toolCall("adj-call", SubmitAdjudicationTool.NAME, Map.of(
                        "publicNarrative", "船队失踪的线索指向北码头。",
                        "playerOutcomes", Map.of("player-1", "你找到了航海日志。"),
                        "worldDeltas", List.of(Map.of("type", "SET_FLAG",
                                "targetId", "north-pier", "value", "investigated")),
                        "nextHooks", List.of("航海日志提到暴风前夜"))),
                LlmResponse.text("中央推演完成。"),
                LlmResponse.text("GM 推演秘书只读摘要。"),
                LlmResponse.text("玩家秘书已收到 GM 指令。"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        SecretProvider secrets = SecretProvider.of(Map.of(
                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN));

        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets, Clock.systemUTC(), llm, adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer controlServer = controlServer(runtime);
            assertThat(controlServer.boundPort()).isPositive();

            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + controlServer.boundPort())
                    .endpoint(CONTROL_PATH)
                    .resumableStreams(false)
                    .connectTimeout(Duration.ofSeconds(5))
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                            request.header("Authorization", "Bearer " + CONTROL_TOKEN))
                    .build();
            client = McpClient.sync(transport).build();
            client.initialize();

            McpSchema.ListToolsResult list = client.listTools();
            assertThat(list.tools().stream().map(McpSchema.Tool::name))
                    .hasSize(65)
                    .containsAll(GmPublicationMcpTools.NAMES)
                    .containsAll(GmRecordMcpTools.NAMES)
                    .contains(RunAdjudicationAgentTool.NAME, GmAideChatTool.NAME,
                            GmSecretaryListTool.NAME, GmSecretaryReadTool.NAME,
                            GmSecretaryDispatchTool.NAME, GmSecretaryChatTool.NAME,
                            GmPostListTool.NAME, GmPostGetTool.NAME,
                            GmPostDecideTool.NAME, GmPostPublishTool.NAME)
                    .containsAll(GmSharedSkillMcpTools.NAMES)
                    .containsAll(GmTicketMcpTools.NAMES)
                    .containsAll(ControlBootstrapMcpTools.NAMES)
                    .containsAll(GmPrivateDocsMcpTools.NAMES)
                    .doesNotContain(GmComplaintMcpTools.NAMES.toArray(String[]::new))
                    .doesNotContain("tf_register_player", "tf_register_director",
                            "tf_load_world_facts");

            callOk(client, "tf_open_turn", Map.of(
                    "expectedRevision", runtime.engine().status().engineRevision(),
                    "idempotencyKey", "bootstrap-open",
                    "reason", "AppBootstrap 接线测试",
                    "minutes", 30));

            // 测试装置直接在领域层制造一个已确认决策，让快照包含一名玩家；
            // 生产玩家的硬确认路径仍由 QQ 入站与既有测试覆盖。MCP 不提供确认工具。
            Actor player = new Actor("player-1", ActorRole.PLAYER);
            CommandResult created = runtime.engine().createDecisionDraft(player,
                    new DecisionDraftInput("调查失踪船队", List.of("询问船工"),
                            Map.of("people", "1"), "北码头", "遇险撤回",
                            java.time.Instant.now().plus(Duration.ofMinutes(30))),
                    runtime.engine().turnView().orElseThrow().revision(), "bootstrap-draft");
            assertThat(created.ok()).isTrue();
            DecisionDraft draft = runtime.engine().draft(created.referenceId().orElseThrow())
                    .orElseThrow();
            assertThat(runtime.engine().confirmDecision(player, draft.draftId(),
                    draft.previewHash(), runtime.engine().turnView().orElseThrow().revision(),
                    "bootstrap-confirm").ok()).isTrue();

            long openRevision = runtime.engine().turnView().orElseThrow().revision();
            callOk(client, "tf_lock_turn", Map.of(
                    "expectedRevision", openRevision,
                    "idempotencyKey", "bootstrap-lock",
                    "reason", "AppBootstrap 接线测试"));

            long lockedRevision = runtime.engine().turnView().orElseThrow().revision();
            McpSchema.CallToolResult adjudication = client.callTool(
                    new McpSchema.CallToolRequest(RunAdjudicationAgentTool.NAME, Map.of(
                            "expectedRevision", lockedRevision,
                            "idempotencyKey", "bootstrap-adjudication",
                            "reason", "AppBootstrap 接线测试")));
            assertThat(adjudication.isError())
                    .as("中央推演工具结果: %s", wireText(adjudication)).isFalse();
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.COMMITTED);

            long committedRevision = runtime.engine().turnView().orElseThrow().revision();
            callOk(client, "tf_publish_turn", Map.of(
                    "expectedRevision", committedRevision,
                    "idempotencyKey", "bootstrap-publish",
                    "reason", "AppBootstrap 接线测试"));
            assertThat(runtime.engine().turnView().orElseThrow().state())
                    .isEqualTo(TurnState.PUBLISHED);
            long worldVersionAfterPublish = runtime.engine().status().worldVersion();
            McpSchema.CallToolResult gmChat = client.callTool(
                    new McpSchema.CallToolRequest(GmAideChatTool.NAME, Map.of(
                            "message", "请只读整理当前世界与推演动向")));
            assertThat(gmChat.isError()).as("GM 推演秘书结果: %s", wireText(gmChat)).isFalse();
            assertThat(wireText(gmChat)).isEqualTo("GM 推演秘书只读摘要。");
            assertThat(runtime.engine().status().worldVersion())
                    .isEqualTo(worldVersionAfterPublish);

            McpSchema.CallToolResult secretaryList = client.callTool(
                    new McpSchema.CallToolRequest(GmSecretaryListTool.NAME, Map.of()));
            assertThat(secretaryList.isError())
                    .as("秘书列表结果: %s", wireText(secretaryList)).isFalse();
            assertThat(wireText(secretaryList))
                    .contains("player-1", "player-2", "public", "player", "gm", "notes")
                    .doesNotContain(dataDir.toString(), "qq-p1", "qq-p2");

            Map<String, Object> dispatchArgs = Map.of(
                    "playerId", "player-1",
                    "path", "gm/order.md",
                    "content", "GM 下发：北码头档案在 gm/order.md",
                    "idempotencyKey", "bootstrap-secretary-dispatch",
                    "reason", "AppBootstrap 工作区接线测试");
            McpSchema.CallToolResult dispatched = client.callTool(
                    new McpSchema.CallToolRequest(
                            GmSecretaryDispatchTool.NAME, dispatchArgs));
            assertThat(dispatched.isError())
                    .as("工作区下发结果: %s", wireText(dispatched)).isFalse();
            McpSchema.CallToolResult replayed = client.callTool(
                    new McpSchema.CallToolRequest(
                            GmSecretaryDispatchTool.NAME, dispatchArgs));
            assertThat(replayed.isError()).isFalse();
            assertThat(wireText(replayed)).isEqualTo(wireText(dispatched));

            McpSchema.CallToolResult read = client.callTool(
                    new McpSchema.CallToolRequest(GmSecretaryReadTool.NAME, Map.of(
                            "playerId", "player-1", "path", "gm/order.md")));
            assertThat(read.isError())
                    .as("工作区读取结果: %s", wireText(read)).isFalse();
            assertThat(wireText(read))
                    .contains("GM 下发：北码头档案在 gm/order.md")
                    .doesNotContain(dataDir.toString());

            Path gmDirectory = dataDir.resolve("secretaries").resolve("player-1")
                    .resolve("gm");
            try (var files = Files.list(gmDirectory)) {
                assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
            }

            McpSchema.CallToolResult escapedPath = client.callTool(
                    new McpSchema.CallToolRequest(GmSecretaryReadTool.NAME, Map.of(
                            "playerId", "player-1", "path", "gm/../player/escape.md")));
            assertThat(escapedPath.isError()).isTrue();
            assertThat(wireText(escapedPath)).contains("INVALID_ARGUMENTS");

            McpSchema.CallToolResult unknownPlayer = client.callTool(
                    new McpSchema.CallToolRequest(GmSecretaryReadTool.NAME, Map.of(
                            "playerId", "player-missing", "path", "gm/order.md")));
            assertThat(unknownPlayer.isError()).isTrue();
            assertThat(wireText(unknownPlayer)).contains("NOT_FOUND");

            McpSchema.CallToolResult secretaryChat = client.callTool(
                    new McpSchema.CallToolRequest(GmSecretaryChatTool.NAME, Map.of(
                            "playerId", "player-1",
                            "message", "请核对 GM 下发的北码头档案")));
            assertThat(secretaryChat.isError())
                    .as("玩家秘书对话结果: %s", wireText(secretaryChat)).isFalse();
            assertThat(wireText(secretaryChat)).isEqualTo("玩家秘书已收到 GM 指令。")
                    .doesNotContain("prompt", "apiKey", CONTROL_TOKEN);

            McpSchema.CallToolResult unknownChat = client.callTool(
                    new McpSchema.CallToolRequest(GmSecretaryChatTool.NAME, Map.of(
                            "playerId", "player-missing", "message", "不应调用模型")));
            assertThat(unknownChat.isError()).isTrue();
            assertThat(wireText(unknownChat)).contains("NOT_FOUND");

            PostService postService = postService(controlServer);
            PostCommandResult postDraft = postService.createDraft(
                    new Actor("player-1", ActorRole.PLAYER), "MCP 推文标题",
                    "MCP 发布的公共推文正文", PostVisibility.PUBLIC,
                    postService.collectionRevision("player-1"), "bootstrap-post-create");
            assertThat(postDraft.ok()).isTrue();
            McpSchema.CallToolResult postList = client.callTool(
                    new McpSchema.CallToolRequest(GmPostListTool.NAME,
                            Map.of("status", "PENDING_APPROVAL")));
            assertThat(postList.isError()).as(wireText(postList)).isFalse();
            assertThat(wireText(postList)).contains(postDraft.postId())
                    .doesNotContain("MCP 发布的公共推文正文");
            McpSchema.CallToolResult postGet = client.callTool(
                    new McpSchema.CallToolRequest(GmPostGetTool.NAME,
                            Map.of("postId", postDraft.postId())));
            assertThat(postGet.isError()).as(wireText(postGet)).isFalse();
            assertThat(wireText(postGet)).contains("MCP 发布的公共推文正文");
            McpSchema.CallToolResult postApprove = client.callTool(
                    new McpSchema.CallToolRequest(GmPostDecideTool.NAME, Map.of(
                            "postId", postDraft.postId(),
                            "decision", "APPROVE",
                            "expectedRevision", 1L,
                            "idempotencyKey", "bootstrap-post-approve")));
            assertThat(postApprove.isError()).as(wireText(postApprove)).isFalse();
            McpSchema.CallToolResult postPublish = client.callTool(
                    new McpSchema.CallToolRequest(GmPostPublishTool.NAME, Map.of(
                            "postId", postDraft.postId(),
                            "expectedRevision", 2L,
                            "idempotencyKey", "bootstrap-post-publish")));
            assertThat(postPublish.isError()).as(wireText(postPublish)).isFalse();
            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.GROUP
                                    && message.textSegments().contains("MCP 发布的公共推文正文")),
                    "MCP 发布推文后应经 PostDistributor 发到公共群");
            assertThat(adapter.sent()).anySatisfy(message -> {
                if (message.textSegments().contains("MCP 发布的公共推文正文")) {
                    assertThat(message.idempotencyKey())
                            .isEqualTo("public:post:" + postDraft.postId() + ":public:reply:0");
                }
            });
            await(() -> postService.post(postDraft.postId()).orElseThrow().deliveryState()
                            == io.mosire.iibot.taskflow.post.DeliveryState.SENT,
                    "推文成功发送后应标记 SENT");

            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.GROUP
                                    && "group-public".equals(message.targetId())),
                    "MCP 发布后应通过 TurnResultDistributor 发公开叙事");
            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.PRIVATE
                                    && "qq-p1".equals(message.targetId())),
                    "MCP 发布后应通过 TurnResultDistributor 发玩家私聊结果");
            assertThat(adapter.sent()).anySatisfy(message -> {
                assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.PRIVATE);
                assertThat(message.targetId()).isEqualTo("qq-p1");
                assertThat(message.textSegments()).containsExactly("你找到了航海日志。");
            });

            // 同一次 afterMutation 组合里投诉回应也必须经 ComplaintDistributor 外发，
            // 且不会被回合/推文分发阻断。
            ComplaintService complaintService = complaintService(controlServer);
            ComplaintCommandResult complaint = complaintService.submit(
                    new Actor("player-1", ActorRole.PLAYER), "控制面投诉",
                    "控制面投诉私密正文", "bootstrap-control-complaint");
            assertThat(complaint.ok()).isTrue();
            String complaintResponse = "控制面 GM 回应-发到私聊-f52b";
            // 完整 Ticket 装配隐藏旧 complaint MCP 工具；旧投诉服务仍由宿主直接调用，
            // 再用任意成功 MCP POST 触发统一 afterMutation。
            ComplaintCommandResult complaintRespond = complaintService.respond(
                    new Actor("director-1", ActorRole.DIRECTOR), complaint.complaintId(), 1,
                    complaintResponse, "bootstrap-control-complaint-respond");
            assertThat(complaintRespond.ok()).as(complaintRespond.toString()).isTrue();
            McpSchema.CallToolResult mutationTrigger = client.callTool(
                    new McpSchema.CallToolRequest(ControlInitializeTool.NAME, Map.of()));
            assertThat(mutationTrigger.isError()).as(wireText(mutationTrigger)).isFalse();
            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.PRIVATE
                                    && "qq-p1".equals(message.targetId())
                                    && message.textSegments().contains(complaintResponse)),
                    "投诉回应后应经 ComplaintDistributor 发到投诉玩家私聊");
            await(() -> complaintService.getForGm(complaint.complaintId()).orElseThrow()
                            .deliveryState() == ComplaintDeliveryState.SENT,
                    "投诉回应发送成功后应标记 SENT");
            assertThat(llm.requests()).hasSize(4);
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
        assertThat(adapter.isClosed()).isTrue();
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        Object value = field.get(runtime);
        assertThat(value).isInstanceOf(ControlMcpServer.class);
        return (ControlMcpServer) value;
    }

    private static PostService postService(ControlMcpServer runtime) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("postService");
        field.setAccessible(true);
        Object value = field.get(runtime);
        assertThat(value).isInstanceOf(PostService.class);
        return (PostService) value;
    }

    private static ComplaintService complaintService(ControlMcpServer runtime) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("complaintService");
        field.setAccessible(true);
        Object value = field.get(runtime);
        assertThat(value).isInstanceOf(ComplaintService.class);
        return (ComplaintService) value;
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static void callOk(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("工具 %s 应成功: %s", tool, result).isFalse();
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, interrupted);
            }
        }
        throw new AssertionError(message);
    }
}
