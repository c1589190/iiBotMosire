package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.GmComplaintMcpTools;
import io.mosire.iibot.app.mcp.GmTicketBatchReplyTool;
import io.mosire.iibot.app.mcp.GmTicketBoardTool;
import io.mosire.iibot.app.mcp.GmTicketClaimTool;
import io.mosire.iibot.app.mcp.GmTicketCloseTool;
import io.mosire.iibot.app.mcp.GmTicketGetTool;
import io.mosire.iibot.app.mcp.GmTicketListTool;
import io.mosire.iibot.app.mcp.GmTicketMcpTools;
import io.mosire.iibot.app.mcp.GmTicketNoteCreateTool;
import io.mosire.iibot.app.mcp.GmTicketReplyTool;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.agent.SecretaryTicketTools;
import io.mosire.iibot.taskflow.agent.SecretaryToolContext;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** P4 完整装配 loopback：60 工具、秘书提交、GM 工单板全流程、旧 complaint 工具隐藏与重启恢复。 */
@Timeout(90)
class AppBootstrapTicketMcpTest {

    private static final String CONTROL_TOKEN = "p4-ticket-token-31d7e9";
    private static final String CONTROL_PATH = "/p4/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void fullAssemblyExposes60ToolsAndRunsSecretaryToGmTicketWorkflow() throws Exception {
        AppConfig config = config();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), new FakeMessagingAdapter()));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer server = controlServer(runtime);
            client = client(server);

            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(60)
                    .containsAll(GmTicketMcpTools.NAMES)
                    .doesNotContain(GmComplaintMcpTools.NAMES.toArray(String[]::new));

            assertTicketToolMetadata(ticketService(server));
            assertLegacyComplaintToolsUnavailable(client);

            SecretarySession session = secretarySessions(server).find("player-1").orElseThrow();
            List<String> secretaryToolNames = session.tools().stream()
                    .map(tool -> tool.name()).toList();
            assertThat(secretaryToolNames)
                    .contains(SecretaryTicketTools.SUBMIT, SecretaryTicketTools.LIST,
                            SecretaryTicketTools.READ)
                    .noneMatch(name -> name.startsWith("secretary_complaint_"));

            String complaintId = submitTicket(session, "player-1", TicketType.COMPLAINT,
                    "组合根工单投诉", "工单投诉私密正文-p4a", "ticket-complaint-1");

            McpSchema.CallToolResult board = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketBoardTool.NAME, Map.of()));
            assertToolOk(board, "ticket board");
            JsonNode boardData = data(board);
            assertThat(boardData.path("count").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(boardData.path("recent").toString())
                    .contains(complaintId, "COMPLAINT")
                    .doesNotContain("工单投诉私密正文-p4a");
            assertThat(wireText(board)).doesNotContain("工单投诉私密正文-p4a");

            McpSchema.CallToolResult list = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketListTool.NAME,
                            Map.of("type", "COMPLAINT")));
            assertToolOk(list, "ticket list");
            assertThat(wireText(list)).contains(complaintId)
                    .doesNotContain("工单投诉私密正文-p4a");

            McpSchema.CallToolResult detail = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketGetTool.NAME,
                            Map.of("ticketId", complaintId)));
            assertToolOk(detail, "ticket get");
            JsonNode detailData = data(detail);
            assertThat(detailData.path("body").asText()).isEqualTo("工单投诉私密正文-p4a");
            assertThat(detailData.path("status").asText()).isEqualTo("OPEN");
            assertThat(detailData.path("revision").asLong()).isEqualTo(1);

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketClaimTool.NAME, Map.of(
                            "ticketId", complaintId,
                            "expectedRevision", 1L,
                            "idempotencyKey", "ticket-claim-1"))), "ticket claim");
            JsonNode claimedData = data(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketGetTool.NAME, Map.of("ticketId", complaintId))));
            assertThat(claimedData.path("status").asText()).isEqualTo("CLAIMED");
            assertThat(claimedData.path("revision").asLong()).isEqualTo(2);

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketReplyTool.NAME, Map.of(
                            "ticketId", complaintId,
                            "expectedRevision", 2L,
                            "replyBody", "GM 工单回复正文-p4b",
                            "idempotencyKey", "ticket-reply-1"))), "ticket reply");
            JsonNode repliedData = data(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketGetTool.NAME, Map.of("ticketId", complaintId))));
            assertThat(repliedData.path("status").asText()).isEqualTo("RESOLVED");
            assertThat(repliedData.path("revision").asLong()).isEqualTo(3);
            assertThat(repliedData.path("replies").toString())
                    .contains("GM 工单回复正文-p4b");

            // 批量回复：两条同批成功。
            String batchOne = submitTicket(session, "player-1", TicketType.SETTING_QUERY,
                    "批量查询一", "批量查询正文一", "ticket-batch-1");
            String batchTwo = submitTicket(session, "player-2", TicketType.ACTION_DRAFT,
                    "批量草稿二", "批量草稿正文二", "ticket-batch-2");
            McpSchema.CallToolResult batch = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketBatchReplyTool.NAME, Map.of(
                            "items", List.of(
                                    Map.of("ticketId", batchOne, "expectedRevision", 1L,
                                            "replyBody", "批量回复一"),
                                    Map.of("ticketId", batchTwo, "expectedRevision", 1L,
                                            "replyBody", "批量回复二")),
                            "idempotencyKey", "ticket-batch-reply-1")));
            assertToolOk(batch, "ticket batch reply");
            assertThat(ticketStatus(client, batchOne)).isEqualTo("RESOLVED");
            assertThat(ticketStatus(client, batchTwo)).isEqualTo("RESOLVED");

            // 批量原子性：任一 stale => 整批零变更。
            String staleOne = submitTicket(session, "player-1", TicketType.COMPLAINT,
                    "原子一", "原子正文一", "ticket-stale-1");
            String staleTwo = submitTicket(session, "player-1", TicketType.COMPLAINT,
                    "原子二", "原子正文二", "ticket-stale-2");
            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketClaimTool.NAME, Map.of(
                            "ticketId", staleOne,
                            "expectedRevision", 1L,
                            "idempotencyKey", "ticket-stale-claim"))), "stale claim");
            McpSchema.CallToolResult failedBatch = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketBatchReplyTool.NAME, Map.of(
                            "items", List.of(
                                    Map.of("ticketId", staleOne, "expectedRevision", 1L,
                                            "replyBody", "stale 不应生效"),
                                    Map.of("ticketId", staleTwo, "expectedRevision", 1L,
                                            "replyBody", "valid 但整批回滚")),
                            "idempotencyKey", "ticket-batch-stale")));
            assertThat(failedBatch.isError()).isTrue();
            assertThat(wireText(failedBatch)).contains("REVISION_CONFLICT");
            assertThat(ticketStatus(client, staleTwo)).isEqualTo("OPEN");
            assertThat(ticketRevision(client, staleTwo)).isEqualTo(1);

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketCloseTool.NAME, Map.of(
                            "ticketId", complaintId,
                            "expectedRevision", 3L,
                            "idempotencyKey", "ticket-close-1",
                            "reason", "P4 close reason"))), "ticket close");
            JsonNode closed = data(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketGetTool.NAME, Map.of("ticketId", complaintId))));
            assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
            assertThat(closed.path("closeReason").asText()).isEqualTo("P4 close reason");

            McpSchema.CallToolResult note = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketNoteCreateTool.NAME, Map.of(
                            "title", "GM 私有备注",
                            "body", "GM_NOTE 正文-p4c",
                            "idempotencyKey", "ticket-note-1")));
            assertToolOk(note, "ticket note");
            String noteId = data(note).path("ticketId").asText();
            JsonNode noteDetail = data(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketGetTool.NAME, Map.of("ticketId", noteId))));
            assertThat(noteDetail.path("type").asText()).isEqualTo("GM_NOTE");
            assertThat(noteDetail.path("playerId").isNull()).isTrue();
            assertThat(noteDetail.path("body").asText()).isEqualTo("GM_NOTE 正文-p4c");

            // board 的最近摘要可包含 body 之外的标题，但不得包含正文。
            McpSchema.CallToolResult boardAfter = client.callTool(
                    new McpSchema.CallToolRequest(GmTicketBoardTool.NAME, Map.of()));
            assertThat(wireText(boardAfter))
                    .contains(noteId)
                    .doesNotContain("GM_NOTE 正文-p4c")
                    .doesNotContain("工单投诉私密正文-p4a");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void ticketServiceAndSecretaryTicketToolsSurviveRestart() throws Exception {
        AppConfig config = config();
        String ticketId;
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), new FakeMessagingAdapter()));
        McpSyncClient client = null;
        try {
            first.start();
            ControlMcpServer server = controlServer(first);
            client = client(server);
            SecretarySession session = secretarySessions(server).find("player-1").orElseThrow();
            ticketId = submitTicket(session, "player-1", TicketType.COMPLAINT,
                    "重启工单标题", "重启工单正文", "restart-ticket-submit");
            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    GmTicketReplyTool.NAME, Map.of(
                            "ticketId", ticketId,
                            "expectedRevision", 1L,
                            "replyBody", "重启前回复正文",
                            "idempotencyKey", "restart-ticket-reply"))), "restart reply");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            first.close();
        }

        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), new FakeMessagingAdapter()));
        McpSyncClient secondClient = null;
        try {
            second.start();
            ControlMcpServer server = controlServer(second);
            secondClient = client(server);
            SecretarySession session = secretarySessions(server).find("player-1").orElseThrow();
            assertThat(session.tools().stream().map(tool -> tool.name()))
                    .contains(SecretaryTicketTools.SUBMIT, SecretaryTicketTools.LIST,
                            SecretaryTicketTools.READ)
                    .noneMatch(name -> name.startsWith("secretary_complaint_"));
            Ticket recovered = ticketService(server).getForGm(ticketId).orElseThrow();
            assertThat(recovered.status()).isEqualTo(TicketStatus.RESOLVED);
            assertThat(recovered.body()).isEqualTo("重启工单正文");
            assertThat(recovered.replies()).singleElement()
                    .satisfies(reply -> assertThat(reply.body()).isEqualTo("重启前回复正文"));
            assertThat(wireText(secondClient.callTool(new McpSchema.CallToolRequest(
                    GmTicketListTool.NAME, Map.of("status", "RESOLVED"))))).contains(ticketId);
        } finally {
            if (secondClient != null) {
                secondClient.closeGracefully();
            }
            second.close();
        }
    }

    private static void assertTicketToolMetadata(TicketService ticketService) {
        Actor supervisor = new Actor(ControlBootstrapService.SUPERVISOR_ACTOR_ID,
                ActorRole.SUPERVISOR);
        List<AgentTool> tools = GmTicketMcpTools.all(ticketService, supervisor);
        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyElementsOf(GmTicketMcpTools.NAMES);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).isEqualTo(Set.of("supervisor"));
            assertThat(tool.jsonSchema()).containsEntry("additionalProperties", false);
        });
    }

    private static void assertLegacyComplaintToolsUnavailable(McpSyncClient client) {
        for (String name : GmComplaintMcpTools.NAMES) {
            McpSchema.CallToolResult result;
            try {
                result = client.callTool(new McpSchema.CallToolRequest(name, Map.of()));
            } catch (RuntimeException protocolRejection) {
                continue;
            }
            assertThat(result.isError()).as("hidden complaint tool " + name).isTrue();
        }
    }

    private static String submitTicket(SecretarySession session, String playerId,
                                       TicketType type, String title, String body,
                                       String idempotencyKey) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(session.tools());
        Map<String, Object> config = Map.of(
                SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, new Actor(playerId, ActorRole.PLAYER));
        ToolContext context = new ToolContext(AccessToken.DEFAULT, session.permissions(),
                config, Map.of(
                "type", type.name(), "title", title, "body", body,
                "idempotencyKey", idempotencyKey));
        ToolResult result = ToolCallAuthorizer.standard().execute(
                registry, SecretaryTicketTools.SUBMIT, context);
        assertThat(result.success()).as(result.code() + " " + result.message()).isTrue();
        return result.message().lines()
                .filter(line -> line.startsWith("ticketId: "))
                .map(line -> line.substring("ticketId: ".length()).trim())
                .findFirst().orElseThrow();
    }

    private static String ticketStatus(McpSyncClient client, String ticketId) {
        return data(client.callTool(new McpSchema.CallToolRequest(
                GmTicketGetTool.NAME, Map.of("ticketId", ticketId))))
                .path("status").asText();
    }

    private static long ticketRevision(McpSyncClient client, String ticketId) {
        return data(client.callTool(new McpSchema.CallToolRequest(
                GmTicketGetTool.NAME, Map.of("ticketId", ticketId))))
                .path("revision").asLong();
    }

    private static JsonNode data(McpSchema.CallToolResult result) {
        try {
            return JSON.readTree(wireText(result)).path("data");
        } catch (Exception failure) {
            throw new AssertionError("无法解析工具 JSON: " + wireText(result), failure);
        }
    }

    private static void assertToolOk(McpSchema.CallToolResult result, String action) {
        assertThat(result.isError()).as(action + " -> " + wireText(result)).isFalse();
        assertThat(wireText(result)).contains("\"ok\":true");
    }

    private AppConfig config() throws Exception {
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
                tempDir.resolve("iibot-p4.json"), root));
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
        return (ControlMcpServer) field.get(runtime);
    }

    private static SecretarySessionRegistry secretarySessions(ControlMcpServer server)
            throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("secretarySessions");
        field.setAccessible(true);
        return (SecretarySessionRegistry) field.get(server);
    }

    private static TicketService ticketService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("ticketService");
        field.setAccessible(true);
        return (TicketService) field.get(server);
    }

    private static String wireText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }
}
