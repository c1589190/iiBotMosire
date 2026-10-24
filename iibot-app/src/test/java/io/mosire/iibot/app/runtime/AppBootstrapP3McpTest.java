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
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.mcp.ControlBootstrapMcpTools;
import io.mosire.iibot.app.mcp.ControlInitializeTool;
import io.mosire.iibot.app.mcp.ControlLoadGmDocTool;
import io.mosire.iibot.app.mcp.ControlLoadWorldTool;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.mcp.GmPublicationMcpTools;
import io.mosire.iibot.app.mcp.GmRecordMcpTools;
import io.mosire.iibot.app.mcp.ControlRegisterDirectorTool;
import io.mosire.iibot.app.mcp.ControlRegisterPlayerTool;
import io.mosire.iibot.app.mcp.ControlSetPublicGroupTool;
import io.mosire.iibot.app.mcp.GmChatHistoryTool;
import io.mosire.iibot.app.mcp.GmComplaintMcpTools;
import io.mosire.iibot.app.mcp.GmSecretaryCacheMcpTools;
import io.mosire.iibot.app.mcp.GmPrivateDocGetTool;
import io.mosire.iibot.app.mcp.GmPrivateDocListTool;
import io.mosire.iibot.app.mcp.GmPrivateDocWriteTool;
import io.mosire.iibot.app.mcp.GmPrivateDocsMcpTools;
import io.mosire.iibot.app.mcp.GmTicketMcpTools;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.StoredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** P3 完整装配 loopback MCP：55 工具、动态玩家路由/重启、控制负向与 GM 私有文档。 */
@Timeout(90)
class AppBootstrapP3McpTest {

    private static final String CONTROL_TOKEN = "p3-control-token-7c91a4";
    private static final String CONTROL_PATH = "/p3/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void dynamicPlayerRegistrationRoutesAckAndSecretaryWhileOldPlayerStillWorks()
            throws Exception {
        AppConfig config = config();
        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.text("动态秘书回复"), LlmResponse.text("旧玩家秘书回复"));
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(), llm, adapter));
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
                    .containsAll(ControlBootstrapMcpTools.NAMES)
                    .containsAll(GmPrivateDocsMcpTools.NAMES)
                    .containsAll(GmTicketMcpTools.NAMES)
                    .doesNotContain(GmComplaintMcpTools.NAMES.toArray(String[]::new))
                    .doesNotContain("tf_qq_send", "qq_send", "tf_confirm_decision", "shutdown")
                    .doesNotContain("tf_register_player", "tf_register_director",
                            "tf_load_world_facts");
            assertLegacyToolUnavailable(client, "tf_register_player", Map.of(
                    "playerId", "legacy-player",
                    "qqId", "qq-legacy",
                    "displayName", "Legacy"));
            assertLegacyToolUnavailable(client, "tf_register_director", Map.of(
                    "playerId", "legacy-director",
                    "qqId", "qq-legacy-d",
                    "displayName", "Legacy"));
            assertLegacyToolUnavailable(client, "tf_load_world_facts", Map.of(
                    "facts", List.of()));

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    ControlInitializeTool.NAME, Map.of())), "initialize");

            McpSchema.CallToolResult registered = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "player-3",
                            "qqId", "qq-p3",
                            "displayName", "动态玩家",
                            "idempotencyKey", "p3-register")));
            assertThat(registered.isError()).as(wireText(registered)).isFalse();
            assertThat(wireText(registered)).contains("\"ok\":true", "player-3");
            assertThat(runtime.controlBootstrapService().resolve("qq-p3")).isPresent();
            assertThat(runtime.engine().playerProfiles())
                    .extracting(profile -> profile.playerId()).contains("player-3");
            SecretarySessionRegistry sessions = secretarySessions(server);
            assertThat(sessions.find("player-3")).isPresent();

            String multiLineFact = "动态公开事实第一行\n第二行\tTab";
            McpSchema.CallToolResult world = client.callTool(
                    new McpSchema.CallToolRequest(ControlLoadWorldTool.NAME, Map.of(
                            "facts", List.of(
                                    Map.of("factId", "p3-public", "content",
                                            multiLineFact, "visibility", "PUBLIC"),
                                    Map.of("factId", "p3-director", "content",
                                            "动态推演秘密", "visibility", "DIRECTOR_ONLY"),
                                    Map.of("factId", "p3-player", "content",
                                            "动态玩家私有事实", "visibility",
                                            "PLAYERS:player-3")),
                            "idempotencyKey", "p3-world")));
            assertToolOk(world, "load world");
            assertThat(wireText(world))
                    .doesNotContain(multiLineFact, "动态推演秘密", "动态玩家私有事实");
            assertThat(runtime.engine().allWorldFacts())
                    .extracting(fact -> fact.factId())
                    .contains("p3-public", "p3-director", "p3-player");
            assertThat(runtime.engine().allWorldFacts().stream()
                    .filter(fact -> fact.factId().equals("p3-public"))
                    .findFirst().orElseThrow().content()).isEqualTo(multiLineFact);
            assertThat(runtime.engine().status().worldVersion()).isEqualTo(2);

            int before = adapter.sent().size();
            assertThat(runtime.submit(TestSupport.privateMessage(
                    "qq-p3", "你好，我是动态玩家", "p3-chat-1"))).isTrue();
            assertThat(runtime.awaitIdle(Duration.ofSeconds(5))).isTrue();
            await(() -> textsTo(adapter, "qq-p3").stream()
                            .anyMatch(text -> text.contains("动态秘书回复")),
                    "新注册玩家应收到秘书回复");
            assertThat(textsTo(adapter, "qq-p3"))
                    .anySatisfy(text -> assertThat(text).contains("消息已收到"));
            assertThat(adapter.sent()).hasSizeGreaterThan(before);

            // 旧配置玩家继续可用。
            assertThat(runtime.submit(TestSupport.privateMessage(
                    "qq-p1", "旧玩家你好", "p1-chat-after-dynamic"))).isTrue();
            assertThat(runtime.awaitIdle(Duration.ofSeconds(5))).isTrue();
            await(() -> textsTo(adapter, "qq-p1").stream()
                            .anyMatch(text -> text.contains("旧玩家秘书回复")),
                    "旧玩家秘书路由不应回归");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void publicGroupAndDynamicPlayerSessionRouteSurviveRestart() throws Exception {
        AppConfig config = config();
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), new FakeMessagingAdapter()));
        McpSyncClient client = null;
        try {
            first.start();
            client = client(controlServer(first));
            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "player-3",
                            "qqId", "qq-p3",
                            "displayName", "动态玩家",
                            "idempotencyKey", "p3-register-restart"))), "register player");
            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    ControlSetPublicGroupTool.NAME, Map.of(
                            "publicGroupId", "group-p3-new",
                            "idempotencyKey", "p3-group"))), "set public group");
            assertThat(first.controlBootstrapService().publicGroupId())
                    .isEqualTo("group-p3-new");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            first.close();
        }

        try (EventContentStore store = new EventContentStore(
                config.dataDir().resolve("events.db"))) {
            assertThat(store.readAfter(0, 10_000))
                    .extracting(StoredEvent::eventType)
                    .contains(DomainEventTypes.CONTROL_STATE,
                            DomainEventTypes.CONTROL_PLAYER_REGISTERED);
        }

        FakeMessagingAdapter secondAdapter = new FakeMessagingAdapter();
        FakeLlmClient secondLlm = FakeLlmClient.with(
                LlmResponse.text("重启后动态秘书回复"));
        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(), secondLlm, secondAdapter));
        try {
            second.start();
            assertThat(second.controlBootstrapService().publicGroupId())
                    .isEqualTo("group-p3-new");
            assertThat(second.controlBootstrapService().resolve("qq-p3")).isPresent();
            assertThat(second.engine().playerProfiles())
                    .extracting(profile -> profile.playerId()).contains("player-3");
            assertThat(secretarySessions(controlServer(second)).find("player-3")).isPresent();

            assertThat(second.submit(TestSupport.privateMessage(
                    "qq-p3", "重启后还能找到秘书吗", "p3-restart-chat"))).isTrue();
            assertThat(second.awaitIdle(Duration.ofSeconds(5))).isTrue();
            await(() -> textsTo(secondAdapter, "qq-p3").stream()
                            .anyMatch(text -> text.contains("重启后动态秘书回复")),
                    "动态玩家重启后私聊路由应恢复");
            assertThat(textsTo(secondAdapter, "qq-p3"))
                    .anySatisfy(text -> assertThat(text).contains("消息已收到"));
        } finally {
            second.close();
        }
    }

    @Test
    void controlMcpNegativesFailClosedWithoutIdentityOrGroupChanges() throws Exception {
        AppConfig config = config();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), new FakeMessagingAdapter()));
        McpSyncClient client = null;
        try {
            runtime.start();
            client = client(controlServer(runtime));
            long playersBefore = runtime.engine().playerProfiles().size();
            String groupBefore = runtime.controlBootstrapService().publicGroupId();

            McpSchema.CallToolResult badGroup = client.callTool(
                    new McpSchema.CallToolRequest(ControlSetPublicGroupTool.NAME, Map.of(
                            "publicGroupId", "bad group",
                            "idempotencyKey", "bad-group")));
            assertThat(badGroup.isError()).isTrue();
            assertThat(wireText(badGroup)).contains("INVALID_ARGUMENTS");
            assertThat(runtime.controlBootstrapService().publicGroupId()).isEqualTo(groupBefore);

            McpSchema.CallToolResult qqConflict = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "player-99",
                            "qqId", "qq-p1",
                            "displayName", "冲突玩家",
                            "idempotencyKey", "conflict-player")));
            assertThat(qqConflict.isError()).isTrue();
            assertThat(wireText(qqConflict)).contains("CONFLICT");

            McpSchema.CallToolResult badPlayer = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "bad id",
                            "qqId", "qq-bad",
                            "displayName", "非法玩家",
                            "idempotencyKey", "invalid-player")));
            assertThat(badPlayer.isError()).isTrue();
            assertThat(wireText(badPlayer)).contains("INVALID_ARGUMENTS");

            McpSchema.CallToolResult longQq = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "player-98",
                            "qqId", "q".repeat(65),
                            "displayName", "超长 QQ",
                            "idempotencyKey", "long-qq")));
            assertThat(longQq.isError()).isTrue();
            assertThat(wireText(longQq)).contains("INVALID_ARGUMENTS");

            McpSchema.CallToolResult badWorld = client.callTool(
                    new McpSchema.CallToolRequest(ControlLoadWorldTool.NAME, Map.of(
                            "facts", List.of(Map.of(
                                    "factId", "bad-fact",
                                    "content", "非法可见性",
                                    "visibility", "SECRET")),
                            "idempotencyKey", "bad-world")));
            assertThat(badWorld.isError()).isTrue();
            assertThat(wireText(badWorld)).contains("INVALID_ARGUMENTS");

            assertThat(runtime.engine().playerProfiles()).hasSize((int) playersBefore);
            assertThat(runtime.controlBootstrapService().publicGroupId()).isEqualTo(groupBefore);
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void sharedEngineIdempotencyKeyConflictCreatesNoDirectorRouteAndCannotOpenTurn()
            throws Exception {
        AppConfig config = config();
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            client = client(controlServer(runtime));
            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "player-3",
                            "qqId", "qq-p3",
                            "displayName", "先注册玩家",
                            "idempotencyKey", "shared-engine-key"))), "register player");

            McpSchema.CallToolResult roleConflict = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterDirectorTool.NAME, Map.of(
                            "playerId", "director-9",
                            "qqId", "qq-d9",
                            "displayName", "同 key 推演",
                            "idempotencyKey", "shared-engine-key")));
            assertThat(roleConflict.isError()).isTrue();
            assertThat(wireText(roleConflict)).contains("IDEMPOTENCY_KEY_CONFLICT");
            assertThat(runtime.engine().directorProfiles())
                    .extracting(profile -> profile.playerId()).doesNotContain("director-9");
            assertThat(runtime.controlBootstrapService().resolve("qq-d9")).isEmpty();
            assertThat(secretarySessions(controlServer(runtime)).find("director-9")).isEmpty();

            // 未注册成推演的 QQ 不能通过私聊打开回合。
            assertThat(runtime.submit(TestSupport.privateMessage(
                    "qq-d9", "/开回合 30", "d9-open-turn"))).isTrue();
            assertThat(runtime.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(runtime.engine().turnView()).isEmpty();
            assertThat(textsTo(adapter, "qq-d9"))
                    .isNotEmpty()
                    .allSatisfy(text -> assertThat(text).doesNotContain("回合已开启"))
                    .anySatisfy(text -> assertThat(text).contains("未授权"));

            // 同角色不同 profile 复用同一 engine key 也必须冲突。
            McpSchema.CallToolResult profileConflict = client.callTool(
                    new McpSchema.CallToolRequest(ControlRegisterPlayerTool.NAME, Map.of(
                            "playerId", "player-4",
                            "qqId", "qq-p4",
                            "displayName", "同 key 不同玩家",
                            "idempotencyKey", "shared-engine-key")));
            assertThat(profileConflict.isError()).isTrue();
            assertThat(wireText(profileConflict)).contains("IDEMPOTENCY_KEY_CONFLICT");
            assertThat(runtime.engine().playerProfiles())
                    .extracting(profile -> profile.playerId())
                    .contains("player-3").doesNotContain("player-4");
            assertThat(runtime.controlBootstrapService().resolve("qq-p4")).isEmpty();
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void gmPrivateDocsMcpRoundTripReplacePathIsolationAndAuditEvents() throws Exception {
        AppConfig config = config();
        Path dataDir = config.dataDir();
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), new FakeMessagingAdapter()));
        McpSyncClient client = null;
        String body = "世界秘密正文第一行\n第二行\tTab-a71c";
        String replacedBody = "覆盖后的世界秘密正文第一行\n第二行\tTab-b82d";
        String loadedBody = "初始化私有设定第一行\n第二行\tTab-c93e";
        try {
            runtime.start();
            ControlMcpServer server = controlServer(runtime);
            client = client(server);

            McpSchema.CallToolResult write = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocWriteTool.NAME, Map.of(
                            "path", "world/secret.md",
                            "content", body,
                            "idempotencyKey", "gm-doc-write-1")));
            assertThat(write.isError()).as(wireText(write)).isFalse();
            assertThat(wireText(write))
                    .contains("\"ok\":true", "world/secret.md", "created")
                    .doesNotContain("Tab-a71c", "第一行")
                    .doesNotContain(dataDir.toString());

            McpSchema.CallToolResult list = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocListTool.NAME,
                            Map.of("prefix", "world")));
            assertThat(list.isError()).isFalse();
            assertThat(wireText(list))
                    .contains("world/secret.md")
                    .doesNotContain("Tab-a71c", "第一行")
                    .doesNotContain(dataDir.toString());

            McpSchema.CallToolResult get = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocGetTool.NAME,
                            Map.of("path", "world/secret.md")));
            assertThat(get.isError()).isFalse();
            JsonNode getData = JSON.readTree(wireText(get)).path("data");
            assertThat(getData.path("content").asText()).isEqualTo(body);
            assertThat(getData.path("contentHash").asText()).hasSize(64);
            assertThat(wireText(get)).doesNotContain(dataDir.toString());

            McpSchema.CallToolResult conflict = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocWriteTool.NAME, Map.of(
                            "path", "world/secret.md",
                            "content", "不应覆盖",
                            "idempotencyKey", "gm-doc-write-conflict")));
            assertThat(conflict.isError()).isTrue();
            assertThat(wireText(conflict)).contains("CONFLICT");

            McpSchema.CallToolResult replaced = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocWriteTool.NAME, Map.of(
                            "path", "world/secret.md",
                            "content", replacedBody,
                            "replace", true,
                            "idempotencyKey", "gm-doc-write-replace")));
            assertThat(replaced.isError()).as(wireText(replaced)).isFalse();
            assertThat(wireText(replaced))
                    .contains("replaced")
                    .doesNotContain("Tab-b82d", "覆盖后的世界秘密正文第一行");
            McpSchema.CallToolResult replacedGet = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocGetTool.NAME,
                            Map.of("path", "world/secret.md")));
            assertThat(JSON.readTree(wireText(replacedGet)).path("data")
                    .path("content").asText()).isEqualTo(replacedBody);

            McpSchema.CallToolResult loaded = client.callTool(
                    new McpSchema.CallToolRequest(ControlLoadGmDocTool.NAME, Map.of(
                            "path", "settings/opening.md",
                            "content", loadedBody,
                            "idempotencyKey", "gm-doc-load")));
            assertThat(loaded.isError()).as(wireText(loaded)).isFalse();
            assertThat(wireText(loaded))
                    .contains("settings/opening.md")
                    .doesNotContain("Tab-c93e", "初始化私有设定第一行")
                    .doesNotContain(dataDir.toString());
            McpSchema.CallToolResult loadedGet = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocGetTool.NAME,
                            Map.of("path", "settings/opening.md")));
            assertThat(JSON.readTree(wireText(loadedGet)).path("data")
                    .path("content").asText()).isEqualTo(loadedBody);

            McpSchema.CallToolResult nul = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocWriteTool.NAME, Map.of(
                            "path", "notes/nul.md",
                            "content", "nul\u0000content",
                            "idempotencyKey", "gm-doc-nul")));
            assertThat(nul.isError()).isTrue();
            assertThat(wireText(nul)).contains("INVALID_ARGUMENTS");

            McpSchema.CallToolResult escape = client.callTool(
                    new McpSchema.CallToolRequest(GmPrivateDocWriteTool.NAME, Map.of(
                            "path", "../escape.md",
                            "content", "escape",
                            "idempotencyKey", "gm-doc-escape")));
            assertThat(escape.isError()).isTrue();
            assertThat(wireText(escape)).contains("INVALID_ARGUMENTS");

            SecretarySessionRegistry sessions = secretarySessions(server);
            assertThat(sessions.all()).isNotEmpty().allSatisfy(session ->
                    assertThat(session.tools().stream().map(tool -> tool.name()))
                            .noneMatch(name -> name.startsWith("tf_gm_doc_")));

            Path file = dataDir.resolve("gm-private").resolve("world/secret.md");
            assertThat(Files.isRegularFile(file)).isTrue();
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(file))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                assertThat(Files.getPosixFilePermissions(
                        dataDir.resolve("gm-private")))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            }
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }

        try (EventContentStore store = new EventContentStore(
                dataDir.resolve("events.db"))) {
            List<StoredEvent> docEvents = store.readAfter(0, 10_000).stream()
                    .filter(event -> event.eventType().equals(DomainEventTypes.GM_DOC_CREATED)
                            || event.eventType().equals(DomainEventTypes.GM_DOC_UPDATED))
                    .toList();
            assertThat(docEvents).extracting(StoredEvent::eventType)
                    .contains(DomainEventTypes.GM_DOC_CREATED, DomainEventTypes.GM_DOC_UPDATED);
            assertThat(docEvents).anySatisfy(event -> {
                // JSON 会把换行/制表符转义为 \n/\t，必须解析后比较正文原值。
                assertThat(JSON.readTree(event.content().json()).path("content").asText())
                        .isEqualTo(body);
                assertThat(event.payloadJson()).doesNotContain("Tab-a71c", "第一行");
            });
            assertThat(docEvents).anySatisfy(event -> {
                assertThat(JSON.readTree(event.content().json()).path("content").asText())
                        .isEqualTo(replacedBody);
                assertThat(event.payloadJson()).doesNotContain("Tab-b82d", "覆盖后的世界秘密正文第一行");
            });
        }
    }

    @Test
    void explicitChatHistoryProviderAddsReadOnlyQqHistoryTool() throws Exception {
        List<ChatHistoryQuery> queries = new java.util.ArrayList<>();
        ChatHistoryProvider provider = query -> {
            queries.add(query);
            return CompletableFuture.completedFuture(new ChatHistoryPage(
                    List.of(new ChatHistoryMessage("msg-1", "qq-p1", "Player One",
                            "历史文本", java.time.Instant.parse("2026-10-24T00:00:00Z"))),
                    null, true));
        };
        FakeMessagingAdapter adapter = new FakeMessagingAdapter();
        AppRuntime runtime = AppBootstrap.build(config(),
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), adapter)
                        .withChatHistoryProvider(provider));
        McpSyncClient client = null;
        try {
            runtime.start();
            ControlMcpServer server = controlServer(runtime);
            client = client(server);
            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(73).contains(GmChatHistoryTool.NAME, "tf_post_compose");

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(GmChatHistoryTool.NAME, Map.of(
                            "chatType", "group",
                            "targetId", "group-public",
                            "count", 5,
                            "direction", "older")));
            assertThat(result.isError()).as(wireText(result)).isFalse();
            assertThat(wireText(result)).contains("历史文本", "exhausted");
            assertThat(queries).hasSize(1);
            assertThat(queries.getFirst().targetId()).isEqualTo("group-public");
            assertThat(queries.getFirst().reverseOrder()).isTrue();

            McpSchema.CallToolResult arbitraryPrivate = client.callTool(
                    new McpSchema.CallToolRequest(GmChatHistoryTool.NAME, Map.of(
                            "chatType", "private", "targetId", "qq-not-registered")));
            assertThat(arbitraryPrivate.isError()).as(wireText(arbitraryPrivate)).isFalse();
            assertThat(wireText(arbitraryPrivate)).contains("历史文本", "qq-not-registered");
            assertThat(queries).hasSize(2);
            assertThat(queries.get(1).sessionType())
                    .isEqualTo(io.mosire.iibot.connect.core.InboundMessage.SessionType.PRIVATE);
            assertThat(queries.get(1).targetId()).isEqualTo("qq-not-registered");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void platformCapabilityAddsDirectoryMessageAndOnboardingTools() throws Exception {
        PlatformFakeAdapter adapter = new PlatformFakeAdapter();
        AppRuntime runtime = AppBootstrap.build(config(),
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            client = client(controlServer(runtime));
            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(77).contains(
                    GmChatHistoryTool.NAME,
                    "tf_qq_chat_list",
                    "tf_qq_group_members",
                    "tf_qq_message_get",
                    "tf_player_onboard_from_group",
                    "tf_post_compose")
                    .doesNotContain("tf_qq_send_private", "tf_qq_send_group");

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    "tf_qq_chat_list", Map.of("scope", "configured"))),
                    "chat list");
            assertThat(wireText(client.callTool(new McpSchema.CallToolRequest(
                    "tf_qq_chat_list", Map.of("scope", "configured")))))
                    .contains("group-public");

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    "tf_qq_group_members", Map.of("groupId", "group-public"))),
                    "group members");
            assertThat(wireText(client.callTool(new McpSchema.CallToolRequest(
                    "tf_qq_group_members", Map.of("groupId", "group-public")))))
                    .contains("qq-new-player");

            assertToolOk(client.callTool(new McpSchema.CallToolRequest(
                    "tf_qq_message_get", Map.of("messageId", "msg-join"))),
                    "message get");
            assertThat(wireText(client.callTool(new McpSchema.CallToolRequest(
                    "tf_qq_message_get", Map.of("messageId", "msg-join")))))
                    .contains("我想报名加入");

            McpSchema.CallToolResult composed = client.callTool(
                    new McpSchema.CallToolRequest("tf_post_compose", Map.of(
                            "mode", "DRAFT",
                            "visibility", "PUBLIC",
                            "title", "外部公告",
                            "body", "这是外部控制方直接撰写的公告。",
                            "idempotencyKey", "platform-mcp-compose-draft")));
            assertThat(composed.isError()).as(wireText(composed)).isFalse();
            assertThat(wireText(composed)).contains("PENDING_APPROVAL", "tf_post_compose");
            assertThat(adapter.delegate.sent()).isEmpty();

            McpSchema.CallToolResult onboard = client.callTool(
                    new McpSchema.CallToolRequest("tf_player_onboard_from_group", Map.of(
                            "sourceGroupId", "group-public",
                            "sourceMessageId", "msg-join",
                            "qqId", "qq-new-player",
                            "displayName", "新玩家",
                            "idempotencyKey", "platform-mcp-onboard-1")));
            assertThat(onboard.isError()).as(wireText(onboard)).isFalse();
            assertThat(wireText(onboard)).contains(
                    "player-", "secretaryBound", "SENT", "registrationCard");
            assertThat(adapter.delegate.sent()).hasSize(1);
            assertThat(adapter.delegate.sent().getFirst().targetId())
                    .isEqualTo("qq-new-player");
            assertThat(adapter.delegate.sent().getFirst().temporaryGroupId())
                    .isEqualTo("group-public");
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    @Test
    void platformSendToolsRequireExplicitEnableAndUseDeliveryLedger() throws Exception {
        PlatformFakeAdapter adapter = new PlatformFakeAdapter();
        AppRuntime runtime = AppBootstrap.build(config(true),
                BootstrapOptions.fake(secrets(), Clock.systemUTC(),
                        FakeLlmClient.with(), adapter));
        McpSyncClient client = null;
        try {
            runtime.start();
            client = client(controlServer(runtime));
            List<String> names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).hasSize(79)
                    .contains("tf_qq_send_private", "tf_qq_send_group");

            McpSchema.CallToolResult sent = client.callTool(
                    new McpSchema.CallToolRequest("tf_qq_send_group", Map.of(
                            "groupId", "99999",
                            "text", "平台级测试公告",
                            "idempotencyKey", "platform-send-group-1")));
            assertThat(sent.isError()).as(wireText(sent)).isFalse();
            assertThat(wireText(sent)).contains("SENT", "messageIds");
            assertThat(adapter.delegate.sent()).singleElement().satisfies(message -> {
                assertThat(message.targetType()).isEqualTo(OutboundMessage.TargetType.GROUP);
                assertThat(message.targetId()).isEqualTo("99999");
            });
        } finally {
            if (client != null) {
                client.closeGracefully();
            }
            runtime.close();
        }
    }

    private AppConfig config() throws Exception {
        return config(false);
    }

    private AppConfig config(boolean allowArbitrarySend) throws Exception {
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
        if (allowArbitrarySend) {
            control.putObject("platform").put("allowArbitrarySend", true);
        }
        return TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot-p3.json"), root));
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

    private static void assertLegacyToolUnavailable(McpSyncClient client, String toolName,
                                                     Map<String, Object> arguments) {
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        } catch (RuntimeException protocolRejection) {
            return;
        }
        // 未注册的工具返回协议错误；若服务端以工具结果返回，则必须是 error。
        assertThat(result.isError()).as("legacy tool " + toolName + " must not execute").isTrue();
    }

    private static void assertToolOk(McpSchema.CallToolResult result, String action) {
        assertThat(result.isError()).as(action + " -> " + wireText(result)).isFalse();
        assertThat(wireText(result)).contains("\"ok\":true");
    }

    private static List<String> textsTo(FakeMessagingAdapter adapter, String qqId) {
        return adapter.sent().stream()
                .filter(message -> message.targetType() == OutboundMessage.TargetType.PRIVATE
                        && qqId.equals(message.targetId()))
                .map(message -> String.join("\n", message.textSegments()))
                .toList();
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

    private static final class PlatformFakeAdapter
            implements MessagingAdapter, QqPlatformCapability {
        private final FakeMessagingAdapter delegate = new FakeMessagingAdapter();

        @Override
        public String adapterId() {
            return delegate.adapterId();
        }

        @Override
        public void start(InboundHandler handler) {
            delegate.start(handler);
        }

        @Override
        public java.util.concurrent.CompletionStage<SendReceipt> send(
                OutboundMessage message) {
            return delegate.send(message);
        }

        @Override
        public AdapterStatus status() {
            return delegate.status();
        }

        @Override
        public void addEventListener(AdapterEventListener listener) {
            delegate.addEventListener(listener);
        }

        @Override
        public void removeEventListener(AdapterEventListener listener) {
            delegate.removeEventListener(listener);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public CompletableFuture<List<ChatConversation>> recentConversations(int count) {
            return CompletableFuture.completedFuture(List.of(new ChatConversation(
                    SessionType.GROUP, "group-public", "测试推演群", "", null, null)));
        }

        @Override
        public CompletableFuture<List<ChatConversation>> joinedGroups() {
            return recentConversations(10);
        }

        @Override
        public CompletableFuture<List<ChatConversation>> friends(boolean noCache) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<GroupMember>> groupMembers(String groupId) {
            return CompletableFuture.completedFuture(List.of(new GroupMember(
                    groupId, "qq-new-player", "新玩家", "新玩家", "member")));
        }

        @Override
        public CompletableFuture<ChatMessageDetail> message(String messageId) {
            return CompletableFuture.completedFuture(new ChatMessageDetail(
                    messageId, SessionType.GROUP, "group-public", "qq-new-player",
                    "新玩家", "我想报名加入", java.time.Instant.parse(
                            "2026-10-24T00:00:00Z"), 0));
        }

        @Override
        public CompletableFuture<SendReceipt> sendPrivate(String targetQqId,
                                                          String temporaryGroupId,
                                                          List<String> textSegments,
                                                          String idempotencyKey) {
            return delegate.send(OutboundMessage.privateTemporaryMessage(
                    targetQqId, temporaryGroupId, textSegments, idempotencyKey))
                    .toCompletableFuture();
        }

        @Override
        public CompletableFuture<SendReceipt> sendGroup(String groupId,
                                                        List<String> textSegments,
                                                        String idempotencyKey) {
            return delegate.send(OutboundMessage.groupMessage(
                    groupId, textSegments, idempotencyKey))
                    .toCompletableFuture();
        }

        @Override
        public CompletableFuture<ChatHistoryPage> history(ChatHistoryQuery query) {
            return CompletableFuture.completedFuture(new ChatHistoryPage(
                    List.of(new ChatHistoryMessage("msg-1", "qq-p1", "Player One",
                            "历史文本", java.time.Instant.parse(
                                    "2026-10-24T00:00:00Z"))),
                    null, true, 0));
        }
    }
}
