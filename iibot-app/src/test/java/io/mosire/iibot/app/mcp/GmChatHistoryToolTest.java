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
import io.mosire.iibot.app.chat.QqChatDirectoryService;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 无白名单历史读取、默认推演群、自动类型解析与安全结果测试。 */
class GmChatHistoryToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void omittedTargetDefaultsToConfiguredPublicGroup() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        GmChatHistoryTool tool = new GmChatHistoryTool(provider, () -> "30003", null);
        ToolResult result = call(tool, Map.of("count", 2));

        assertThat(result.success()).isTrue();
        assertThat(provider.queries).singleElement().satisfies(query -> {
            assertThat(query.sessionType()).isEqualTo(SessionType.GROUP);
            assertThat(query.targetId()).isEqualTo("30003");
            assertThat(query.count()).isEqualTo(2);
        });
    }

    @Test
    void readsAnyPrivateOrGroupWithoutRegistrationAllowlist() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        GmChatHistoryTool tool = new GmChatHistoryTool(provider, () -> "30003", null);

        ToolResult privateResult = call(tool, Map.of(
                "chatType", "private", "targetId", "99999"));
        ToolResult groupResult = call(tool, Map.of(
                "chatType", "group", "targetId", "88888"));

        assertThat(privateResult.success()).isTrue();
        assertThat(groupResult.success()).isTrue();
        assertThat(provider.queries).extracting(ChatHistoryQuery::targetId)
                .containsExactly("99999", "88888");
    }

    @Test
    void autoResolvesPrivateAndGroupThroughDirectory() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        FakePlatform platform = new FakePlatform();
        platform.recent = List.of(conversation(SessionType.PRIVATE, "20002"));
        platform.groups = List.of(conversation(SessionType.GROUP, "40004"));
        QqChatDirectoryService directory =
                new QqChatDirectoryService(platform, () -> "30003");
        GmChatHistoryTool tool = new GmChatHistoryTool(provider, () -> "30003", directory);

        ToolResult privateResult = call(tool, Map.of("targetId", "20002"));
        ToolResult groupResult = call(tool, Map.of("targetId", "40004"));

        assertThat(privateResult.success()).isTrue();
        assertThat(groupResult.success()).isTrue();
        assertThat(provider.queries).extracting(ChatHistoryQuery::sessionType)
                .containsExactly(SessionType.PRIVATE, SessionType.GROUP);
    }

    @Test
    void unknownAutoTargetFailsClosedBeforeProviderCall() {
        RecordingProvider provider = new RecordingProvider();
        FakePlatform platform = new FakePlatform();
        QqChatDirectoryService directory =
                new QqChatDirectoryService(platform, () -> "30003");
        GmChatHistoryTool tool = new GmChatHistoryTool(provider, () -> "30003", directory);

        ToolResult result = call(tool, Map.of("targetId", "77777"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CHAT_TYPE_REQUIRED");
        assertThat(provider.queries).isEmpty();
    }

    @Test
    void returnsNonTextSkippedAndNeutralMessages() throws Exception {
        RecordingProvider provider = new RecordingProvider();
        GmChatHistoryTool tool = new GmChatHistoryTool(provider, () -> "30003", null);
        ToolResult result = call(tool, Map.of(
                "chatType", "group", "targetId", "30003", "count", 2));

        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("messages")).hasSize(1);
        assertThat(data.path("messages").get(0).path("text").asText()).isEqualTo("历史文本");
        assertThat(data.path("nonTextSkipped").asInt()).isEqualTo(2);
        assertThat(data.path("nextCursor").asText()).isEqualTo("101");
        assertThat(data.path("exhausted").asBoolean()).isFalse();
    }

    private static ChatConversation conversation(SessionType type, String targetId) {
        return new ChatConversation(type, targetId, "name", "", null, null);
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
                    List.of(new ChatHistoryMessage("101", "20002", "玩家",
                            "历史文本", Instant.parse("2026-10-24T00:00:00Z"))),
                    "101", false, 2));
        }
    }

    private static final class FakePlatform implements QqPlatformCapability {
        private List<ChatConversation> recent = List.of();
        private List<ChatConversation> groups = List.of();

        @Override
        public CompletableFuture<List<ChatConversation>> recentConversations(int count) {
            return CompletableFuture.completedFuture(recent);
        }

        @Override
        public CompletableFuture<List<ChatConversation>> joinedGroups() {
            return CompletableFuture.completedFuture(groups);
        }

        @Override
        public CompletableFuture<List<ChatConversation>> friends(boolean noCache) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<GroupMember>> groupMembers(String groupId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ChatMessageDetail> message(String messageId) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<SendReceipt> sendPrivate(String targetQqId,
                                                          String temporaryGroupId,
                                                          List<String> textSegments,
                                                          String idempotencyKey) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<SendReceipt> sendGroup(String groupId,
                                                        List<String> textSegments,
                                                        String idempotencyKey) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<ChatHistoryPage> history(ChatHistoryQuery query) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }
    }
}
