package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.agent.MappedIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** NapCat 只读历史 MCP 工具的目标白名单、协议中立结果与游标测试。 */
class GmChatHistoryToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MappedIdentity PLAYER = new MappedIdentity(
            "20002", new Actor("player-1", ActorRole.PLAYER),
            new PlayerProfile("player-1", "20002", "Player One"));

    @Test
    void readsAllowedPrivateHistoryAndReturnsNeutralMessagesAndCursor() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        GmChatHistoryTool tool = tool(provider);
        ToolResult result = call(tool, Map.of(
                "chatType", "private", "targetId", "20002", "count", 2));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("chatType").asText()).isEqualTo("private");
        assertThat(data.path("targetId").asText()).isEqualTo("20002");
        assertThat(data.path("messages")).hasSize(1);
        assertThat(data.path("messages").get(0).path("text").asText())
                .isEqualTo("历史文本");
        assertThat(data.path("messages").get(0).path("senderId").asText())
                .isEqualTo("20002");
        assertThat(data.path("nextCursor").asText()).isEqualTo("101");
        assertThat(data.path("exhausted").asBoolean()).isFalse();
        assertThat(provider.queries).hasSize(1);
        assertThat(provider.queries.getFirst().sessionType())
                .isEqualTo(SessionType.PRIVATE);
        assertThat(provider.queries.getFirst().count()).isEqualTo(2);
        assertThat(provider.queries.getFirst().reverseOrder()).isTrue();
    }

    @Test
    void rejectsUnregisteredPrivateTargetAndNonPublicGroupBeforeProviderCall() {
        RecordingProvider provider = new RecordingProvider();
        GmChatHistoryTool tool = tool(provider);

        ToolResult unknown = call(tool, Map.of(
                "chatType", "private", "targetId", "99999"));
        assertThat(unknown.code()).isEqualTo("TARGET_NOT_ALLOWED");

        ToolResult wrongGroup = call(tool, Map.of(
                "chatType", "group", "targetId", "99999"));
        assertThat(wrongGroup.code()).isEqualTo("TARGET_NOT_ALLOWED");
        assertThat(provider.queries).isEmpty();
    }

    @Test
    void acceptsCurrentPublicGroupAndOlderDirection() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        GmChatHistoryTool tool = tool(provider);
        ToolResult result = call(tool, Map.of(
                "chatType", "group", "targetId", "30003",
                "cursor", "old", "direction", "older"));

        assertThat(result.success()).isTrue();
        assertThat(provider.queries.getFirst().sessionType())
                .isEqualTo(SessionType.GROUP);
        assertThat(provider.queries.getFirst().cursor()).isEqualTo("old");
        assertThat(provider.queries.getFirst().reverseOrder()).isTrue();
    }

    private static GmChatHistoryTool tool(RecordingProvider provider) {
        return new GmChatHistoryTool(provider,
                target -> "20002".equals(target) ? Optional.of(PLAYER) : Optional.empty(),
                () -> "30003");
    }

    private static ToolResult call(GmChatHistoryTool tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(GmChatHistoryTool.NAME)
                .roles(RoleSet.of("supervisor"))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        GmChatHistoryTool.RESOURCE_NAMESPACE,
                        ResourceScope.of("read"))))
                .sensitiveAllowed(true)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), args, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, GmChatHistoryTool.NAME, context);
    }

    private static final class RecordingProvider implements ChatHistoryProvider {
        private final List<ChatHistoryQuery> queries = new ArrayList<>();

        @Override
        public CompletableFuture<ChatHistoryPage> history(ChatHistoryQuery query) {
            queries.add(query);
            return CompletableFuture.completedFuture(new ChatHistoryPage(
                    List.of(new ChatHistoryMessage("101", "20002", "测试玩家",
                            "历史文本", Instant.parse("2026-10-24T00:00:00Z"))),
                    "101", false));
        }
    }
}
