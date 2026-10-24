package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.cache.SqliteSecretaryCacheStore;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.agent.BriefingResult;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheStore;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 秘书 cache 工具的 metadata、隔离、只读渲染与 active 切换测试。 */
class GmSecretaryCacheMcpToolsTest {

    private static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void toolsHaveSupervisorMetadataAndCacheResourcePolicy() {
        Fixture fixture = fixture();
        assertThat(fixture.tools()).extracting(AgentTool::name)
                .containsExactlyElementsOf(GmSecretaryCacheMcpTools.NAMES);
        assertThat(fixture.tools()).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly("supervisor");
            assertThat(tool.resources().namespaces())
                    .containsExactly(SecretaryCacheStore.RESOURCE_NAMESPACE);
            assertThat(tool.spec().requiredLevel()).isNotEqualTo(AccessToken.SYSTEM);
            assertThat(tool.spec().sensitive()).isTrue();
            assertThat(tool.spec().destructive()).isFalse();
        });
        assertThat(find(fixture.tools(), GmSecretaryCacheMcpTools.READ).spec())
                .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, true, false));
    }

    @Test
    void listsReadsCreatesAndSwitchesOnlyRegisteredPlayerCaches() throws Exception {
        Fixture fixture = fixture();
        ToolResult listed = fixture.call(GmSecretaryCacheMcpTools.LIST,
                Map.of("playerId", "player-1"));
        assertThat(listed.success()).isTrue();
        JsonNode listData = JSON.readTree(listed.message()).path("data");
        String originalActive = listData.path("activeCacheId").asText();
        assertThat(originalActive).isNotBlank();
        assertThat(listData.path("count").asInt()).isEqualTo(1);

        ToolResult read = fixture.call(GmSecretaryCacheMcpTools.READ,
                Map.of("playerId", "player-1", "limit", 20));
        assertThat(read.success()).isTrue();
        String readText = read.message();
        assertThat(readText)
                .contains("玩家可见")
                .contains("秘书回复")
                .doesNotContain("工具内部正文", "其他玩家秘密", "qq-p1");

        ToolResult created = fixture.call(GmSecretaryCacheMcpTools.CREATE,
                Map.of("playerId", "player-1", "title", "旧对话"));
        assertThat(created.success()).isTrue();
        String createdId = JSON.readTree(created.message()).path("data").path("cacheId").asText();
        assertThat(createdId).isNotBlank();

        ToolResult switched = fixture.call(GmSecretaryCacheMcpTools.SWITCH,
                Map.of("playerId", "player-1", "cacheId", createdId,
                        "reason", "测试切换"));
        assertThat(switched.success()).isTrue();
        assertThat(JSON.readTree(switched.message()).path("data")
                .path("activeCacheId").asText()).isEqualTo(createdId);

        ToolResult missing = fixture.call(GmSecretaryCacheMcpTools.LIST,
                Map.of("playerId", "missing-player"));
        assertThat(missing.code()).isEqualTo("NOT_FOUND");

        ToolResult foreign = fixture.call(GmSecretaryCacheMcpTools.READ,
                Map.of("playerId", "player-2", "cacheId", originalActive));
        assertThat(foreign.code()).isEqualTo("CACHE_NOT_FOUND");
    }

    private Fixture fixture() {
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        TaskflowEngine engine = new TaskflowEngine(clock, new InMemoryEventStore());
        Actor director = new Actor("director-1", ActorRole.DIRECTOR);
        ok(engine.registerDirector(director,
                new PlayerProfile("director-1", "qq-director", "Director"),
                engine.status().engineRevision(), "cache-register-director"));
        for (String playerId : List.of("player-1", "player-2")) {
            ok(engine.registerPlayer(director,
                    new PlayerProfile(playerId, "qq-" + playerId, playerId),
                    engine.status().engineRevision(), "cache-register-" + playerId));
        }

        SqliteSecretaryCacheStore store = SqliteSecretaryCacheStore.open(
                tempDir.resolve("secretary-cache"));
        SecretarySessionRegistry registry = new SecretarySessionRegistry();
        FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("ok"));
        for (String playerId : List.of("player-1", "player-2")) {
            registry.register(new SecretarySession(playerId,
                    new Actor(playerId, ActorRole.PLAYER), engine, llm,
                    (pid, question, facts) -> new BriefingResult("", Set.of()),
                    director, clock, Duration.ofMinutes(15), Duration.ofMinutes(15),
                    null, null, null, null, null, null, store));
        }
        CacheSummary own = store.ensureDefaultCache("player-1");
        store.appendMessages("player-1", own.cacheId(), List.of(
                LlmMessage.user("玩家可见"),
                LlmMessage.tool(new ContentPart.ToolResult(
                        "call-1", "tool", "工具内部正文", null)),
                LlmMessage.assistant(List.of(new ContentPart.Text("秘书回复")))));
        CacheSummary other = store.ensureDefaultCache("player-2");
        store.appendMessages("player-2", other.cacheId(), List.of(
                LlmMessage.user("其他玩家秘密")));

        return new Fixture(store, GmSecretaryCacheMcpTools.all(registry));
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

    private record Fixture(SqliteSecretaryCacheStore store, List<AgentTool> tools) {
        ToolResult call(String toolName, Map<String, Object> arguments) {
            ToolRegistry registry = new ToolRegistry();
            registry.registerAll(tools);
            AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                    .allow(GmSecretaryCacheMcpTools.NAMES.toArray(String[]::new))
                    .roles(RoleSet.of("supervisor"))
                    .resourceScopes(new ResourceScopeMap(Map.of(
                            SecretaryCacheStore.RESOURCE_NAMESPACE,
                            ResourceScope.of("conversation"))))
                    .sensitiveAllowed(true)
                    .readOnly(false)
                    .build();
            ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                    Map.of(), arguments, AgentIdentity.external());
            return ToolCallAuthorizer.standard().execute(registry, toolName, context);
        }
    }
}
