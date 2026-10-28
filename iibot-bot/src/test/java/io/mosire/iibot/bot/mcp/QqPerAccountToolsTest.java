package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.friend.FriendRequestService;
import io.mosire.iibot.bot.friend.InMemoryFriendRequestStore;
import io.mosire.iibot.bot.note.InMemoryUserNoteStore;
import io.mosire.iibot.connect.core.FriendRequestCapability;
import io.mosire.iibot.connect.core.FriendRequestEvent;
import io.mosire.iibot.connect.core.FriendRequestHandler;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** P6b：直接 provider / 好友申请 / 用户备注的逐账号路由。 */
class QqPerAccountToolsTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void friendRequestsAreIsolatedPerAccountAndRequireSelection() throws Exception {
        InMemoryFriendRequestStore store = new InMemoryFriendRequestStore();
        FakeCapability capabilityA = new FakeCapability();
        FakeCapability capabilityB = new FakeCapability();
        FriendRequestService serviceA = new FriendRequestService("bot-a", store, capabilityA, CLOCK);
        FriendRequestService serviceB = new FriendRequestService("bot-b", store, capabilityB, CLOCK);
        var requestA = serviceA.receive(new FriendRequestEvent(
                "flag-shared", "qq-9", "Carol", "来自 A", T0));
        var requestB = serviceB.receive(new FriendRequestEvent(
                "flag-shared", "qq-9", "Carol", "来自 B", T0));
        assertThat(requestA.requestId()).isNotEqualTo(requestB.requestId());

        AgentTool list = new BotFriendRequestListTool(
                Map.of("bot-a", serviceA, "bot-b", serviceB));
        AgentTool decide = new BotFriendRequestDecideTool(
                Map.of("bot-a", serviceA, "bot-b", serviceB));

        assertThat(failure(list, Map.of()).code()).isEqualTo("ACCOUNT_REQUIRED");
        assertThat(failure(decide, Map.of("requestId", requestA.requestId(),
                "approve", true)).code()).isEqualTo("ACCOUNT_REQUIRED");
        assertThat(failure(list, Map.of("accountId", "bot-x")).code())
                .isEqualTo("ACCOUNT_UNAVAILABLE");

        JsonNode listedA = call(list, Map.of("accountId", "bot-a"));
        assertThat(listedA.path("data").path("accountId").asText()).isEqualTo("bot-a");
        assertThat(listedA.path("data").path("requests")).hasSize(1);
        assertThat(listedA.path("data").path("requests").get(0).path("comment").asText())
                .isEqualTo("来自 A");
        JsonNode listedB = call(list, Map.of("accountId", "bot-b"));
        assertThat(listedB.path("data").path("requests").get(0).path("comment").asText())
                .isEqualTo("来自 B");

        // A 账号的 requestId 不能用于 B 账号审批。
        assertThat(failure(decide, Map.of("accountId", "bot-b",
                "requestId", requestA.requestId(), "approve", true)).code())
                .isEqualTo("FRIEND_REQUEST_NOT_FOUND");

        JsonNode decidedA = call(decide, Map.of("accountId", "bot-a",
                "requestId", requestA.requestId(), "approve", true));
        assertThat(decidedA.path("data").path("accountId").asText()).isEqualTo("bot-a");
        assertThat(decidedA.path("data").path("status").asText()).isEqualTo("APPROVED");
        assertThat(capabilityA.calls).isEqualTo(1);
        assertThat(capabilityB.calls).isZero();
        assertThat(call(list, Map.of("accountId", "bot-a")).path("data").path("count").asInt())
                .isZero();
        assertThat(call(list, Map.of("accountId", "bot-b")).path("data").path("count").asInt())
                .isEqualTo(1);

        JsonNode decidedB = call(decide, Map.of("accountId", "bot-b",
                "requestId", requestB.requestId(), "approve", false));
        assertThat(decidedB.path("data").path("status").asText()).isEqualTo("REJECTED");
        assertThat(capabilityB.calls).isEqualTo(1);
    }

    @Test
    void friendRequestToolsReportUnsupportedAccountInsteadOfCrossRouting() throws Exception {
        InMemoryFriendRequestStore store = new InMemoryFriendRequestStore();
        FriendRequestService serviceA = new FriendRequestService(
                "bot-a", store, new FakeCapability(), CLOCK);
        Map<String, FriendRequestService> mixed = new java.util.LinkedHashMap<>();
        mixed.put("bot-a", serviceA);
        mixed.put("bot-b", null);

        AgentTool list = new BotFriendRequestListTool(mixed);
        assertThat(failure(list, Map.of("accountId", "bot-b")).code())
                .isEqualTo("UNSUPPORTED_FRIEND_REQUEST");
        JsonNode defaulted = call(list, Map.of());
        assertThat(defaulted.path("data").path("accountId").asText()).isEqualTo("bot-a");
    }

    @Test
    void userNotesStayIsolatedPerAccountAndRejectUnknownAccount() throws Exception {
        InMemoryUserNoteStore notes = new InMemoryUserNoteStore(CLOCK);
        BotAccountScope scope = BotAccountScope.of(List.of("bot-a", "bot-b"));
        AgentTool upsert = new BotUserNoteUpsertTool(notes, scope);
        AgentTool get = new BotUserNoteGetTool(notes, scope);
        AgentTool list = new BotUserNoteListTool(notes, scope);

        assertThat(failure(upsert, Map.of("qqId", "qq-1", "note", "写错账号")).code())
                .isEqualTo("ACCOUNT_REQUIRED");
        assertThat(failure(get, Map.of("qqId", "qq-1")).code())
                .isEqualTo("ACCOUNT_REQUIRED");
        assertThat(failure(list, Map.of()).code()).isEqualTo("ACCOUNT_REQUIRED");

        JsonNode writtenA = call(upsert, Map.of("accountId", "bot-a", "qqId", "qq-1",
                "displayName", "Alice", "tags", List.of("friend"), "note", "A 的备注",
                "priority", 10));
        assertThat(writtenA.path("data").path("accountId").asText()).isEqualTo("bot-a");
        JsonNode writtenB = call(upsert, Map.of("accountId", "bot-b", "qqId", "qq-1",
                "note", "B 的备注"));
        assertThat(writtenB.path("data").path("accountId").asText()).isEqualTo("bot-b");

        assertThat(call(get, Map.of("accountId", "bot-a", "qqId", "qq-1"))
                .path("data").path("note").asText()).isEqualTo("A 的备注");
        assertThat(call(get, Map.of("accountId", "bot-b", "qqId", "qq-1"))
                .path("data").path("note").asText()).isEqualTo("B 的备注");
        assertThat(call(list, Map.of("accountId", "bot-a")).path("data").path("count").asInt())
                .isEqualTo(1);
        assertThat(call(list, Map.of("accountId", "bot-b")).path("data").path("count").asInt())
                .isEqualTo(1);

        assertThat(failure(upsert, Map.of("accountId", "bot-c", "qqId", "qq-1",
                "note", "越权")).code()).isEqualTo("ACCOUNT_UNAVAILABLE");
        assertThat(failure(get, Map.of("accountId", "bot-c", "qqId", "qq-1")).code())
                .isEqualTo("ACCOUNT_UNAVAILABLE");
        assertThat(notes.find("bot-c", "qq-1")).isEmpty();
    }

    @Test
    void perAccountSchemasExposeAccountIdButNoInternalMarkers() throws Exception {
        InMemoryFriendRequestStore store = new InMemoryFriendRequestStore();
        FriendRequestService service = new FriendRequestService(
                "bot-a", store, new FakeCapability(), CLOCK);
        List<AgentTool> tools = List.of(
                new BotQqRecentTool("bot-a", limit -> CompletableFuture.completedFuture(List.of())),
                new BotQqHistoryTool("bot-a", query -> CompletableFuture.completedFuture(null)),
                new BotFriendRequestListTool("bot-a", service),
                new BotFriendRequestDecideTool("bot-a", service),
                new BotUserNoteGetTool(new InMemoryUserNoteStore(CLOCK), "bot-a"),
                new BotUserNoteListTool(new InMemoryUserNoteStore(CLOCK), "bot-a"),
                new BotUserNoteUpsertTool(new InMemoryUserNoteStore(CLOCK), "bot-a"));

        // Agent 不得被要求提供内部同步标记；可选的 expectedRevision 仍是可选项而非必填。
        List<String> forbiddenRequired = List.of("revision", "snapshot", "snapshotToSeq",
                "itemId", "seq", "expectedRevision", "afterSeq");
        List<String> forbiddenProperties = List.of("snapshot", "snapshotToSeq", "snapshotToSeq",
                "itemId", "localSeq", "afterSeq");
        for (AgentTool tool : tools) {
            JsonNode schema = JSON.valueToTree(tool.jsonSchema());
            List<String> properties = new ArrayList<>();
            schema.path("properties").fieldNames().forEachRemaining(properties::add);
            assertThat(properties).as(tool.name()).contains("accountId");
            List<String> required = new ArrayList<>();
            schema.path("required").forEach(node -> required.add(node.asText()));
            assertThat(required).as(tool.name()).doesNotContainAnyElementsOf(forbiddenRequired);
            assertThat(properties).as(tool.name())
                    .doesNotContainAnyElementsOf(forbiddenProperties);
            assertThat(tool.description()).as(tool.name()).isNotBlank();
        }
    }

    private static JsonNode call(AgentTool tool, Map<String, Object> args) throws Exception {
        ToolResult result = execute(tool, args);
        assertThat(result.success()).as(tool.name() + " failed: " + result.message()).isTrue();
        return JSON.readTree(result.message());
    }

    private static ToolResult failure(AgentTool tool, Map<String, Object> args) {
        ToolResult result = execute(tool, args);
        assertThat(result.success()).as(tool.name() + " 意外成功").isFalse();
        return result;
    }

    private static ToolResult execute(AgentTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }

    private static final class FakeCapability implements FriendRequestCapability {
        private int calls;

        @Override
        public void setFriendRequestHandler(FriendRequestHandler handler) {
        }

        @Override
        public CompletableFuture<SendReceipt> decideFriendRequest(
                String flag, boolean approve, String remark) {
            calls++;
            return CompletableFuture.completedFuture(
                    SendReceipt.sent(List.of("friend-request")));
        }
    }
}
