package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.group.GroupCatalogueEntry;
import io.mosire.iibot.bot.group.InMemoryGroupCatalogueStore;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.GroupRequestCapability;
import io.mosire.iibot.connect.core.GroupSystemRequest;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** F-008：群邀请/入群申请工具与群目录检索。 */
class BotGroupToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GroupSystemRequest invite(long sequence, String groupId, String groupName) {
        return new GroupSystemRequest(sequence, GroupSystemRequest.Kind.INVITE,
                GroupSystemRequest.EVENT_TYPE_INVITE, groupId, groupName,
                "123456", "邀请人", "", "", "来吧", false);
    }

    private static GroupSystemRequest joinRequest(long sequence, String groupId,
                                                  String nickname, boolean handled) {
        return new GroupSystemRequest(sequence, GroupSystemRequest.Kind.JOIN,
                GroupSystemRequest.EVENT_TYPE_JOIN, groupId, "申请群",
                "", "", "3341796326", nickname, "想进群", handled);
    }

    @Test
    void requestListSkipsHandledByDefaultAndRemembersCatalogue() throws Exception {
        FakeCapability capability = new FakeCapability();
        capability.requests.add(invite(1793612836122334L, "938269591", "目标群"));
        capability.requests.add(joinRequest(1792837085640003L, "1104560326", "希望永恒", false));
        capability.requests.add(joinRequest(1792839371808078L, "1104560326", "二呱", true));
        InMemoryGroupCatalogueStore catalogue = new InMemoryGroupCatalogueStore();
        AgentTool list = new BotGroupRequestListTool(Map.of("bot", capability), catalogue);

        JsonNode data = call(list, Map.of()).path("data");

        assertThat(data.path("count").asInt()).isEqualTo(2);
        assertThat(data.path("pendingCount").asInt()).isEqualTo(2);
        JsonNode first = data.path("requests").get(0);
        assertThat(first.path("requestId").asLong()).isEqualTo(1793612836122334L);
        assertThat(first.path("kind").asText()).isEqualTo("INVITE");
        assertThat(first.path("groupId").asText()).isEqualTo("938269591");
        assertThat(first.path("inviterName").asText()).isEqualTo("邀请人");
        assertThat(data.path("requests").get(1).path("kind").asText()).isEqualTo("JOIN");
        assertThat(catalogue.search("938269591", 10)).hasSize(1);
        assertThat(catalogue.get("938269591").orElseThrow().source())
                .isEqualTo(GroupCatalogueEntry.SOURCE_INVITE);
    }

    @Test
    void requestListIncludesHandledWhenAsked() throws Exception {
        FakeCapability capability = new FakeCapability();
        capability.requests.add(joinRequest(11L, "1104560326", "二呱", true));
        AgentTool list = new BotGroupRequestListTool(Map.of("bot", capability),
                new InMemoryGroupCatalogueStore());

        JsonNode data = call(list, Map.of("includeHandled", true)).path("data");

        assertThat(data.path("count").asInt()).isEqualTo(1);
        assertThat(data.path("pendingCount").asInt()).isZero();
        assertThat(data.path("requests").get(0).path("handled").asBoolean()).isTrue();
    }

    @Test
    void requestListRequiresAccountWhenSeveralAreConfigured() {
        FakeCapability first = new FakeCapability();
        FakeCapability second = new FakeCapability();
        AgentTool list = new BotGroupRequestListTool(
                Map.of("bot-a", first, "bot-b", second), new InMemoryGroupCatalogueStore());

        assertThat(failure(list, Map.of()).code()).isEqualTo("ACCOUNT_REQUIRED");
        assertThat(failure(list, Map.of("accountId", "bot-x")).code())
                .isEqualTo("ACCOUNT_UNAVAILABLE");
    }

    @Test
    void decideSendsApproveAndReportsState() throws Exception {
        FakeCapability capability = new FakeCapability();
        AgentTool decide = new BotGroupRequestDecideTool(Map.of("bot", capability));

        JsonNode data = call(decide, Map.of("requestId", 1792837085640003L,
                "approve", true, "reason", "欢迎")).path("data");

        assertThat(data.path("state").asText()).isEqualTo("SENT");
        assertThat(data.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(capability.decided).containsExactly(1792837085640003L + ":true:欢迎");
    }

    @Test
    void decideRejectsNonPositiveRequestId() {
        AgentTool decide = new BotGroupRequestDecideTool(Map.of("bot", new FakeCapability()));

        assertThat(failure(decide, Map.of("requestId", 0, "approve", true)).code())
                .isEqualTo("INVALID_ARGUMENTS");
    }

    @Test
    void decideReportsUnknownWithoutClaimingSuccess() {
        FakeCapability capability = new FakeCapability();
        capability.decideReceipt = SendReceipt.unknown("GROUP_REQUEST_UNKNOWN", "结果未知");
        AgentTool decide = new BotGroupRequestDecideTool(Map.of("bot", capability));

        ToolResult result = failure(decide, Map.of("requestId", 42L, "approve", true));

        assertThat(result.code()).isEqualTo("GROUP_REQUEST_UNKNOWN");
    }

    @Test
    void inviteAcceptUsesGivenSequenceAndRecordsJoined() throws Exception {
        FakeCapability capability = new FakeCapability();
        InMemoryGroupCatalogueStore catalogue = new InMemoryGroupCatalogueStore();
        AgentTool accept = new BotGroupInviteAcceptTool(Map.of("bot", capability), catalogue);

        JsonNode data = call(accept, Map.of("groupId", "938269591",
                "sequence", 1793612836122334L)).path("data");

        assertThat(data.path("state").asText()).isEqualTo("SENT");
        assertThat(data.path("joined").asBoolean()).isTrue();
        assertThat(capability.accepted)
                .containsExactly("938269591:1793612836122334:2");
        assertThat(catalogue.get("938269591").orElseThrow().source())
                .isEqualTo(GroupCatalogueEntry.SOURCE_JOINED);
    }

    @Test
    void inviteAcceptFindsPendingInvitationByGroupId() throws Exception {
        FakeCapability capability = new FakeCapability();
        capability.requests.add(joinRequest(5L, "938269591", "别人", false));
        capability.requests.add(invite(1793612836122334L, "938269591", "目标群"));
        AgentTool accept = new BotGroupInviteAcceptTool(Map.of("bot", capability),
                new InMemoryGroupCatalogueStore());

        JsonNode data = call(accept, Map.of("groupId", "938269591")).path("data");

        assertThat(data.path("sequence").asLong()).isEqualTo(1793612836122334L);
        assertThat(data.path("groupName").asText()).isEqualTo("目标群");
        assertThat(capability.accepted)
                .containsExactly("938269591:1793612836122334:2");
    }

    @Test
    void inviteAcceptReportsNoInvitationWithoutSideEffects() {
        FakeCapability capability = new FakeCapability();
        capability.requests.add(joinRequest(5L, "938269591", "别人", false));
        AgentTool accept = new BotGroupInviteAcceptTool(Map.of("bot", capability),
                new InMemoryGroupCatalogueStore());

        ToolResult result = failure(accept, Map.of("groupId", "938269591"));

        assertThat(result.code()).isEqualTo("NO_INVITATION");
        assertThat(capability.accepted).isEmpty();
    }

    @Test
    void inviteAcceptReportsUnknownReceipt() {
        FakeCapability capability = new FakeCapability();
        capability.acceptReceipt = SendReceipt.unknown("GROUP_INVITE_UNKNOWN", "结果未知");
        AgentTool accept = new BotGroupInviteAcceptTool(Map.of("bot", capability),
                new InMemoryGroupCatalogueStore());

        ToolResult result = failure(accept, Map.of("groupId", "938269591", "sequence", 7L));

        assertThat(result.code()).isEqualTo("GROUP_INVITE_UNKNOWN");
    }

    @Test
    void searchMergesCatalogueJoinedAndInvitedGroups() throws Exception {
        FakeCapability capability = new FakeCapability();
        capability.groups.add(new ChatConversation(SessionType.GROUP, "915899297", "公共群",
                "", null, null));
        capability.groups.add(new ChatConversation(SessionType.PRIVATE, "20002", "某人",
                "", null, null));
        capability.requests.add(invite(1793612836122334L, "938269591", "目标群"));
        InMemoryGroupCatalogueStore catalogue = new InMemoryGroupCatalogueStore();
        catalogue.upsert(new GroupCatalogueEntry("1072031273", "老群",
                GroupCatalogueEntry.SOURCE_JOINED, java.time.Instant.parse("2026-10-01T00:00:00Z")));
        AgentTool search = new BotGroupSearchTool(Map.of("bot", capability), catalogue);

        JsonNode data = call(search, Map.of()).path("data");

        assertThat(data.path("liveChecked").asBoolean()).isTrue();
        JsonNode matches = data.path("matches");
        assertThat(matches).hasSize(3);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        matches.forEach(row -> byId.put(row.path("groupId").asText(), row));
        assertThat(byId.get("915899297").path("joined").asBoolean()).isTrue();
        assertThat(byId.get("938269591").path("invited").asBoolean()).isTrue();
        assertThat(byId.get("938269591").path("requestId").asLong())
                .isEqualTo(1793612836122334L);
        assertThat(byId).doesNotContainKey("20002");
        assertThat(byId.get("1072031273").path("groupName").asText()).isEqualTo("老群");
    }

    @Test
    void searchFiltersByKeywordAndReportsNoMatch() throws Exception {
        FakeCapability capability = new FakeCapability();
        capability.groups.add(new ChatConversation(SessionType.GROUP, "915899297", "公共群",
                "", null, null));
        AgentTool search = new BotGroupSearchTool(Map.of("bot", capability),
                new InMemoryGroupCatalogueStore());

        JsonNode data = call(search, Map.of("keyword", "公共")).path("data");
        assertThat(data.path("matches")).hasSize(1);

        ToolResult none = failure(search, Map.of("keyword", "不存在的群"));
        assertThat(none.code()).isEqualTo("NO_MATCH");
    }

    @Test
    void searchStillWorksWithoutAccountWhenSeveralAreConfigured() throws Exception {
        InMemoryGroupCatalogueStore catalogue = new InMemoryGroupCatalogueStore();
        catalogue.upsert(new GroupCatalogueEntry("938269591", "目标群",
                GroupCatalogueEntry.SOURCE_INVITE, java.time.Instant.parse("2026-10-02T00:00:00Z")));
        AgentTool search = new BotGroupSearchTool(
                Map.of("bot-a", new FakeCapability(), "bot-b", new FakeCapability()), catalogue);

        JsonNode data = call(search, Map.of("keyword", "938269591")).path("data");

        assertThat(data.path("liveChecked").asBoolean()).isFalse();
        assertThat(data.path("matches")).hasSize(1);
        assertThat(data.path("matches").get(0).path("invited").asBoolean()).isFalse();
    }

    @Test
    void toolsAdvertiseQqNamespaceAndSupervisorRole() {
        AgentTool list = new BotGroupRequestListTool(Map.of("bot", new FakeCapability()),
                new InMemoryGroupCatalogueStore());
        AgentTool search = new BotGroupSearchTool(Map.of("bot", new FakeCapability()),
                new InMemoryGroupCatalogueStore());

        assertThat(List.of(list.name(), search.name()))
                .containsExactly("qq_group_request_list", "qq_group_search");
        assertThat(list.roles()).containsExactly("supervisor");
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

    private static final class FakeCapability implements GroupRequestCapability {
        private final List<GroupSystemRequest> requests = new ArrayList<>();
        private final List<ChatConversation> groups = new ArrayList<>();
        private final List<String> decided = new ArrayList<>();
        private final List<String> accepted = new ArrayList<>();
        private final AtomicInteger listCalls = new AtomicInteger();
        private volatile SendReceipt decideReceipt = SendReceipt.sent(List.of("group-request-1"));
        private volatile SendReceipt acceptReceipt = SendReceipt.sent(List.of("group-invite-1"));

        @Override
        public CompletableFuture<List<ChatConversation>> joinedGroups() {
            return CompletableFuture.completedFuture(List.copyOf(groups));
        }

        @Override
        public CompletableFuture<List<GroupSystemRequest>> listGroupRequests(int limit) {
            listCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    requests.stream().limit(limit).toList());
        }

        @Override
        public CompletableFuture<SendReceipt> decideGroupRequest(
                long sequence, boolean approve, String reason) {
            decided.add(sequence + ":" + approve + ":" + (reason == null ? "" : reason));
            return CompletableFuture.completedFuture(decideReceipt);
        }

        @Override
        public CompletableFuture<SendReceipt> acceptGroupInvitation(
                String groupId, long sequence, int eventType) {
            accepted.add(groupId + ":" + sequence + ":" + eventType);
            return CompletableFuture.completedFuture(acceptReceipt);
        }
    }
}
