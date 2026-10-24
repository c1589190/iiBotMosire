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
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatHistoryMessage;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 消息定位：live cache 成功、历史回扫兜底、参数错误与安全失败。 */
class GmQqMessageGetToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void liveCacheHitReturnsResolvedByLiveCache() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.liveMessage = detail("m-live", "txt");
        GmQqMessageGetTool tool = new GmQqMessageGetTool(platform);

        ToolResult result = call(tool, Map.of("messageId", "m-live"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("resolvedBy").asText()).isEqualTo("LIVE_CACHE");
        assertThat(data.path("text").asText()).isEqualTo("txt");
    }

    @Test
    void olderMessageFallsBackToHistoryScan() throws Exception {
        FakePlatform platform = new FakePlatform();
        platform.liveMessage = null;
        platform.historyMessages = List.of(new ChatHistoryMessage(
                "m-old", "20002", "玩家", "旧消息", Instant.parse("2026-10-24T00:00:00Z")));
        GmQqMessageGetTool tool = new GmQqMessageGetTool(platform);

        ToolResult result = call(tool, Map.of(
                "messageId", "m-old", "chatType", "group", "targetId", "30003"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("resolvedBy").asText()).isEqualTo("HISTORY_SCAN");
        assertThat(data.path("scannedMessages").asInt()).isEqualTo(1);
        assertThat(platform.historyQueries).singleElement()
                .satisfies(query -> assertThat(query.targetId()).isEqualTo("30003"));
    }

    @Test
    void noTargetReturnsLiveCacheMissWithoutScanning() {
        FakePlatform platform = new FakePlatform();
        platform.liveMessage = null;
        GmQqMessageGetTool tool = new GmQqMessageGetTool(platform);

        ToolResult result = call(tool, Map.of("messageId", "m-missing"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MESSAGE_NOT_IN_LIVE_CACHE");
        assertThat(platform.historyQueries).isEmpty();
    }

    @Test
    void targetWithoutChatTypeFailsClosed() {
        FakePlatform platform = new FakePlatform();
        GmQqMessageGetTool tool = new GmQqMessageGetTool(platform);

        ToolResult result = call(tool, Map.of(
                "messageId", "m-missing", "targetId", "30003"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CHAT_TYPE_REQUIRED");
        assertThat(platform.historyQueries).isEmpty();
    }

    @Test
    void historyMissReturnsStableNotFoundCode() {
        FakePlatform platform = new FakePlatform();
        platform.liveMessage = null;
        platform.historyMessages = List.of();
        GmQqMessageGetTool tool = new GmQqMessageGetTool(platform);

        ToolResult result = call(tool, Map.of(
                "messageId", "m-missing", "chatType", "group", "targetId", "30003"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MESSAGE_NOT_FOUND_IN_SCAN");
    }

    private static ChatMessageDetail detail(String id, String text) {
        return new ChatMessageDetail(id, SessionType.GROUP, "30003", "20002",
                "玩家", text, Instant.parse("2026-10-24T00:00:00Z"), 0);
    }

    private static ToolResult call(GmQqMessageGetTool tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(GmQqMessageGetTool.NAME)
                .roles(RoleSet.of("supervisor"))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        GmQqMessageGetTool.RESOURCE_NAMESPACE,
                        ResourceScope.of("read"))))
                .sensitiveAllowed(true)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), args, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(
                registry, GmQqMessageGetTool.NAME, context);
    }

    private static final class FakePlatform implements QqPlatformCapability {
        private ChatMessageDetail liveMessage;
        private List<ChatHistoryMessage> historyMessages = List.of();
        private final java.util.List<ChatHistoryQuery> historyQueries =
                new java.util.ArrayList<>();

        @Override
        public CompletableFuture<List<ChatConversation>> recentConversations(int count) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<List<ChatConversation>> joinedGroups() {
            return CompletableFuture.completedFuture(List.of());
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
            return liveMessage == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("not in cache"))
                    : CompletableFuture.completedFuture(liveMessage);
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
            historyQueries.add(query);
            return CompletableFuture.completedFuture(new ChatHistoryPage(
                    historyMessages, null, true, 0));
        }
    }
}
