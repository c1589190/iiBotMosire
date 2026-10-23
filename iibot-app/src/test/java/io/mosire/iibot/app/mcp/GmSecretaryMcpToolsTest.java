package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.agent.BriefingResult;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.agent.SecretaryWorkspaceTools;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 侧四个秘书工作区 MCP 工具的 metadata、授权路径、幂等与隔离测试。 */
class GmSecretaryMcpToolsTest {

    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void toolsHaveExactSupervisorMetadataAndResourceNamespace() {
        Fixture fixture = fixture();
        List<AgentTool> tools = fixture.tools();
        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyElementsOf(GmSecretaryMcpTools.NAMES);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly("supervisor");
            assertThat(tool.roles()).doesNotContain("*", "SYSTEM");
            assertThat(tool.resources().namespaces())
                    .containsExactly(SecretaryWorkspaceStore.RESOURCE_NAMESPACE);
            assertThat(tool.resources().policyFor(
                    SecretaryWorkspaceStore.RESOURCE_NAMESPACE))
                    .isEqualTo(ResourcePolicy.DENY);
            assertThat(tool.spec().requiredLevel()).isNotEqualTo(AccessToken.SYSTEM);
            assertThat(tool.jsonSchema().toString()).doesNotContain("*", "SYSTEM");
        });

        AgentTool list = find(tools, GmSecretaryListTool.NAME);
        assertThat(list.spec()).isEqualTo(ToolSpec.DEFAULT);
        AgentTool read = find(tools, GmSecretaryReadTool.NAME);
        assertThat(read.spec().sensitive()).isTrue();
        assertThat(read.spec().destructive()).isFalse();
        AgentTool dispatch = find(tools, GmSecretaryDispatchTool.NAME);
        assertThat(dispatch.spec().destructive()).isTrue();
        AgentTool chat = find(tools, GmSecretaryChatTool.NAME);
        assertThat(chat.spec().sensitive()).isTrue();
        assertThat(chat.spec().destructive()).isFalse();
    }

    @Test
    void listReadDispatchAreAuthorizedAndIdempotentWithoutPhysicalPathLeak() throws Exception {
        Fixture fixture = fixture();

        ToolResult list = fixture.call(GmSecretaryListTool.NAME, Map.of());
        assertThat(list.success()).isTrue();
        assertThat(list.message())
                .contains("player-1", "player-2", "public", "player", "gm", "notes")
                .doesNotContain(fixture.store().rootFor("player-1").toString(),
                        fixture.store().rootFor("player-2").toString(), "qq-p1", "qq-p2");

        Map<String, Object> dispatchArgs = Map.of(
                "playerId", "player-1",
                "path", "gm/order.md",
                "content", "北码头档案正文",
                "idempotencyKey", "dispatch-once",
                "reason", "测试下发");
        ToolResult first = fixture.call(GmSecretaryDispatchTool.NAME, dispatchArgs);
        assertThat(first.success()).isTrue();
        assertThat(first.message()).doesNotContain("北码头档案正文",
                fixture.store().rootFor("player-1").toString());

        ToolResult replay = fixture.call(GmSecretaryDispatchTool.NAME, dispatchArgs);
        assertThat(replay.success()).isTrue();
        assertThat(replay.message()).isEqualTo(first.message());
        try (var files = Files.list(
                fixture.store().rootFor("player-1").resolve("gm"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
        }

        ToolResult duplicatePath = fixture.call(GmSecretaryDispatchTool.NAME, Map.of(
                "playerId", "player-1",
                "path", "gm/order.md",
                "content", "第二份正文",
                "idempotencyKey", "dispatch-second",
                "reason", "测试拒绝覆盖"));
        assertThat(duplicatePath.code()).isEqualTo("CONFLICT");

        ToolResult escaped = fixture.call(GmSecretaryReadTool.NAME, Map.of(
                "playerId", "player-1", "path", "gm/../player/escape.md"));
        assertThat(escaped.code()).isEqualTo("INVALID_ARGUMENTS");
        ToolResult unknown = fixture.call(GmSecretaryReadTool.NAME, Map.of(
                "playerId", "missing-player", "path", "gm/order.md"));
        assertThat(unknown.code()).isEqualTo("NOT_FOUND");

        ToolResult read = fixture.call(GmSecretaryReadTool.NAME, Map.of(
                "playerId", "player-1", "path", "gm/order.md"));
        assertThat(read.success()).isTrue();
        assertThat(read.message())
                .contains("北码头档案正文")
                .doesNotContain(fixture.store().rootFor("player-1").toString());

        ToolResult missing = fixture.call(GmSecretaryReadTool.NAME, Map.of(
                "playerId", "player-1", "path", "gm/missing.md"));
        assertThat(missing.code()).isEqualTo("NOT_FOUND");

        String longContent = "x".repeat(SecretaryWorkspaceTools.MAX_READ_RESULT_CHARS + 100);
        fixture.call(GmSecretaryDispatchTool.NAME, Map.of(
                "playerId", "player-2",
                "path", "player/long.md",
                "content", longContent,
                "idempotencyKey", "dispatch-long",
                "reason", "测试截断"));
        ToolResult longRead = fixture.call(GmSecretaryReadTool.NAME, Map.of(
                "playerId", "player-2", "path", "player/long.md"));
        assertThat(longRead.success()).isTrue();
        assertThat(longRead.message()).contains("内容已截断");

        ToolResult wrongScope = fixture.callWithPermissions(
                GmSecretaryListTool.NAME, Map.of(), AgentPermissionSet.builder(AccessToken.DEFAULT)
                        .allow(GmSecretaryListTool.NAME)
                        .roles(RoleSet.of("supervisor"))
                        .build());
        assertThat(wrongScope.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    }

    @Test
    void chatUsesRegisteredSecretaryAndReturnsOnlyFinalText() {
        Fixture fixture = fixture();

        ToolResult chat = fixture.call(GmSecretaryChatTool.NAME, Map.of(
                "playerId", "player-1", "message", "请核对北码头档案"));
        assertThat(chat.success()).isTrue();
        assertThat(chat.message()).isEqualTo("玩家秘书回复：已核对。");
        assertThat(fixture.llm().requests()).hasSize(1);

        ToolResult unknown = fixture.call(GmSecretaryChatTool.NAME, Map.of(
                "playerId", "missing-player", "message", "不应调用模型"));
        assertThat(unknown.code()).isEqualTo("NOT_FOUND");
        assertThat(fixture.llm().requests()).hasSize(1);
    }

    private Fixture fixture() {
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        TaskflowEngine engine = new TaskflowEngine(clock, new InMemoryEventStore());
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        ok(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "gm-secretary-register-director"));
        ok(engine.registerPlayer(director,
                new PlayerProfile("player-1", "qq-p1", "Player One"),
                engine.status().engineRevision(), "gm-secretary-register-p1"));
        ok(engine.registerPlayer(director,
                new PlayerProfile("player-2", "qq-p2", "Player Two"),
                engine.status().engineRevision(), "gm-secretary-register-p2"));

        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        FakeLlmClient llm = FakeLlmClient.with(
                LlmResponse.text("玩家秘书回复：已核对。"));
        for (String playerId : List.of("player-1", "player-2")) {
            registry.register(new SecretarySession(playerId,
                    new Actor(playerId, ActorRole.PLAYER), engine, llm,
                    (pid, question, facts) -> new BriefingResult("", Set.of()),
                    director, clock, store));
        }
        List<AgentTool> tools = GmSecretaryMcpTools.all(store, registry);
        return new Fixture(store, llm, tools);
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name()))
                .findFirst().orElseThrow();
    }

    private static void ok(CommandResult result) {
        if (!result.ok()) {
            throw new AssertionError("fixture 命令失败: " + result.code()
                    + " " + result.message());
        }
    }

    private record Fixture(SecretaryWorkspaceStore store, FakeLlmClient llm,
                           List<AgentTool> tools) {
        ToolResult call(String toolName, Map<String, Object> arguments) {
            return callWithPermissions(toolName, arguments, permissions());
        }

        ToolResult callWithPermissions(String toolName, Map<String, Object> arguments,
                                       AgentPermissionSet permissions) {
            ToolRegistry registry = new ToolRegistry();
            registry.registerAll(tools);
            ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                    Map.of(), arguments, AgentIdentity.external());
            return ToolCallAuthorizer.standard().execute(registry, toolName, context);
        }

        private static AgentPermissionSet permissions() {
            return AgentPermissionSet.builder(AccessToken.DEFAULT)
                    .allow(GmSecretaryMcpTools.NAMES.toArray(String[]::new))
                    .roles(RoleSet.of("supervisor"))
                    .resourceScopes(new ResourceScopeMap(Map.of(
                            SecretaryWorkspaceStore.RESOURCE_NAMESPACE,
                            ResourceScope.of("workspace"))))
                    .destructiveAllowed(true)
                    .sensitiveAllowed(true)
                    .readOnly(false)
                    .build();
        }
    }
}
