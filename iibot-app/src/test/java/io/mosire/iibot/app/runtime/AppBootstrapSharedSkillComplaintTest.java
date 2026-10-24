package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.mcp.ControlBootstrapMcpTools;
import io.mosire.iibot.app.mcp.ControlInitializeTool;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.GmPublicationMcpTools;
import io.mosire.iibot.app.mcp.GmRecordMcpTools;
import io.mosire.iibot.app.mcp.GmPrivateDocsMcpTools;
import io.mosire.iibot.app.mcp.GmComplaintMcpTools;
import io.mosire.iibot.app.mcp.GmSharedSkillGetTool;
import io.mosire.iibot.app.mcp.GmTicketBoardTool;
import io.mosire.iibot.app.mcp.GmTicketMcpTools;
import io.mosire.iibot.app.mcp.GmSharedSkillListTool;
import io.mosire.iibot.app.mcp.GmSharedSkillMcpTools;
import io.mosire.iibot.app.mcp.GmSharedSkillWriteTool;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintDeliveryState;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 组合根共享 Skill 与投诉接线：48 工具、真实 loopback MCP、重启恢复与 afterMutation 隔离。 */
@Timeout(60)
class AppBootstrapSharedSkillComplaintTest {

    private static final String CONTROL_TOKEN = "bootstrap-shared-complaint-token-6d31";
    private static final String CONTROL_PATH = "/shared-complaint/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void fullAssemblyExposes48ToolsAndSharedSkillWriteGetThroughRealLoopbackMcp()
            throws Exception {
        AppConfig config = configWithControl();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(LlmResponse.text("只读摘要")), adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer server = controlServer(runtime);
            client = client(server);
            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(72)
            .containsAll(GmPublicationMcpTools.NAMES)
                    .contains(GmPublicationMcpTools.OPENING)
            .containsAll(GmRecordMcpTools.NAMES)
                    .containsAll(GmSharedSkillMcpTools.NAMES)
                    .containsAll(GmTicketMcpTools.NAMES)
                    .containsAll(ControlBootstrapMcpTools.NAMES)
                    .containsAll(GmPrivateDocsMcpTools.NAMES)
                    .doesNotContain(GmComplaintMcpTools.NAMES.toArray(String[]::new))
                    .doesNotContain("tf_qq_send", "qq_send", "tf_confirm_decision", "shutdown")
                    .doesNotContain("tf_register_player", "tf_register_director",
                            "tf_load_world_facts");

            String body = "共享技能正文-仅get可见-cd9e";
            McpSchema.CallToolResult write = client.callTool(new McpSchema.CallToolRequest(
                    GmSharedSkillWriteTool.NAME, Map.of(
                            "path", "skills/answer-guide.md",
                            "content", body,
                            "idempotencyKey", "bootstrap-shared-write",
                            "reason", "组合根接线测试")));
            assertThat(write.isError()).as(wireText(write)).isFalse();
            assertThat(wireText(write)).contains("created", "skills/answer-guide.md")
                    .doesNotContain(body)
                    .doesNotContain(config.dataDir().toString());

            McpSchema.CallToolResult get = client.callTool(new McpSchema.CallToolRequest(
                    GmSharedSkillGetTool.NAME, Map.of("path", "skills/answer-guide.md")));
            assertThat(get.isError()).as(wireText(get)).isFalse();
            assertThat(wireText(get)).contains(body)
                    .doesNotContain(config.dataDir().toString());

            McpSchema.CallToolResult list = client.callTool(new McpSchema.CallToolRequest(
                    GmSharedSkillListTool.NAME, Map.of("path", "skills")));
            assertThat(list.isError()).isFalse();
            assertThat(wireText(list)).contains("answer-guide.md");

            McpSchema.CallToolResult board = client.callTool(new McpSchema.CallToolRequest(
                    GmTicketBoardTool.NAME, Map.of()));
            assertThat(board.isError()).as(wireText(board)).isFalse();
            assertThat(wireText(board)).contains("\"count\":0");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void gmRespondTriggersComplaintDistributorAndComplaintSurvivesRestart() throws Exception {
        AppConfig config = configWithControl();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        String complaintId;
        String responseBody = "GM 回应-经 afterMutation 发到私聊-e8a3";

        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(LlmResponse.text("只读摘要")), adapter));
        McpSyncClient client = null;
        try {
            first.start();
            ControlMcpServer server = controlServer(first);
            ComplaintService complaintService = complaintService(server);
            ComplaintCommandResult submitted = complaintService.submit(
                    new Actor("player-1", ActorRole.PLAYER), "组合根投诉",
                    "组合根投诉私密正文-3a7f", "bootstrap-complaint-submit");
            assertThat(submitted.ok()).isTrue();
            complaintId = submitted.complaintId();

            client = client(server);
            // 完整 Ticket 装配隐藏旧 complaint MCP 工具；旧投诉服务仍兼容，由宿主直接调用，
            // 再用任意成功 MCP POST 触发统一 afterMutation，验证旧 ComplaintDistributor 仍工作。
            ComplaintCommandResult responded = complaintService.respond(
                    new Actor("director-1", ActorRole.DIRECTOR), complaintId, 1,
                    responseBody, "bootstrap-complaint-respond");
            assertThat(responded.ok()).isTrue();
            assertThat(responded.revision()).isEqualTo(2);
            McpSchema.CallToolResult trigger = client.callTool(
                    new McpSchema.CallToolRequest(ControlInitializeTool.NAME, Map.of()));
            assertThat(trigger.isError()).as(wireText(trigger)).isFalse();

            await(() -> adapter.sent().stream().anyMatch(message ->
                            message.targetType() == OutboundMessage.TargetType.PRIVATE
                                    && "qq-p1".equals(message.targetId())
                                    && message.textSegments().contains(responseBody)),
                    "GM 回应后应经 ComplaintDistributor 发到投诉玩家私聊");
            assertThat(adapter.sent()).anySatisfy(message -> {
                if (message.textSegments().contains(responseBody)) {
                    assertThat(message.idempotencyKey()).isEqualTo(
                            "private:complaint:" + complaintId + ":response:reply:0");
                }
            });
            await(() -> complaintService.getForGm(complaintId).orElseThrow()
                            .deliveryState() == ComplaintDeliveryState.SENT,
                    "投诉回应发送成功后应标记 SENT");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            first.close();
        }

        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(LlmResponse.text("只读摘要")),
                        new FakeMessagingAdapter()));
        try {
            second.start();
            ComplaintService recovered = complaintService(controlServer(second));
            Complaint complaint = recovered.getForGm(complaintId).orElseThrow();
            assertThat(complaint.status().name()).isEqualTo("RESPONDED");
            assertThat(complaint.responseBody()).isEqualTo(responseBody);
            assertThat(complaint.deliveryState()).isEqualTo(ComplaintDeliveryState.SENT);
        } finally {
            second.close();
        }
    }

    @Test
    void afterMutationComponentsAreIsolatedFromEachOther() {
        AtomicInteger completed = new AtomicInteger();
        AtomicBoolean afterFailureRan = new AtomicBoolean();
        AppBootstrap.runAfterMutationSafely("turn-result-distributor", () -> {
            throw new IllegalStateException("模拟回合分发失败");
        });
        AppBootstrap.runAfterMutationSafely("post-distributor", completed::incrementAndGet);
        AppBootstrap.runAfterMutationSafely("complaint-distributor", () -> {
            afterFailureRan.set(true);
            completed.incrementAndGet();
        });

        assertThat(completed).hasValue(2);
        assertThat(afterFailureRan).isTrue();
    }

    private AppConfig configWithControl() {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "港口迷雾与失踪船队", "visibility": "PUBLIC"}
                ]}
                """);
        ObjectNode root = TestSupport.validConfigNode(tempDir.resolve("data"), factsFile);
        ObjectNode control = root.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", CONTROL_PATH);
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        return TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot.json"), root));
    }

    private static SecretProvider secrets() {
        return SecretProvider.of(Map.of(
                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN));
    }

    private McpSyncClient client(ControlMcpServer server) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.boundPort())
                .endpoint(CONTROL_PATH)
                .resumableStreams(false)
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + CONTROL_TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        Object value = field.get(runtime);
        assertThat(value).isInstanceOf(ControlMcpServer.class);
        return (ControlMcpServer) value;
    }

    private static ComplaintService complaintService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("complaintService");
        field.setAccessible(true);
        return (ComplaintService) field.get(server);
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
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
