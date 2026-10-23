package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStatus;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.complaint.InMemoryComplaintStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 投诉 MCP 工具：列表摘要、读取正文、GM 回应、幂等与零副作用错误。 */
class ControlMcpServerComplaintToolsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor SUPERVISOR = new Actor("external-supervisor", ActorRole.SUPERVISOR);
    private static final String BODY_MARKER = "投诉私密正文-列表不得返回-b14c";
    private static final String RESPONSE_MARKER = "GM 私密回应-列表不得返回-2d07";

    @Test
    void toolsHaveSupervisorMetadataComplaintResourceAndRespondIsDestructive() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        List<AgentTool> tools = GmComplaintMcpTools.all(service, SUPERVISOR);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyElementsOf(GmComplaintMcpTools.NAMES);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly("supervisor")
                    .doesNotContain("*", "SYSTEM");
            assertThat(tool.resources().namespaces())
                    .containsExactly(ComplaintStore.RESOURCE_NAMESPACE);
            assertThat(tool.resources().policyFor(ComplaintStore.RESOURCE_NAMESPACE))
                    .isEqualTo(ResourcePolicy.DENY);
        });
        assertThat(find(tools, GmComplaintListTool.NAME).spec().destructive()).isFalse();
        assertThat(find(tools, GmComplaintGetTool.NAME).spec().destructive()).isFalse();
        assertThat(find(tools, GmComplaintRespondTool.NAME).spec().destructive()).isTrue();
        assertThat(find(tools, GmComplaintRespondTool.NAME).jsonSchema().toString())
                .contains("complaintId", "response", "expectedRevision", "idempotencyKey")
                .doesNotContain("playerId");
    }

    @Test
    void listReturnsSummariesWithoutBodiesAndGetReturnsBodyAndResponse() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        ComplaintCommandResult submitted = service.submit(PLAYER, "投诉标题", BODY_MARKER, "s-1");
        assertThat(service.respond(SUPERVISOR, submitted.complaintId(), 1, RESPONSE_MARKER,
                "r-1").ok()).isTrue();
        List<AgentTool> tools = GmComplaintMcpTools.all(service, SUPERVISOR);

        ToolResult list = call(tools, "supervisor", GmComplaintListTool.NAME, Map.of());
        assertThat(list.success()).isTrue();
        assertThat(list.message()).contains(submitted.complaintId(), "player-1", "RESPONDED")
                .doesNotContain(BODY_MARKER).doesNotContain(RESPONSE_MARKER);

        ToolResult filtered = call(tools, "supervisor", GmComplaintListTool.NAME,
                Map.of("status", "SUBMITTED"));
        assertThat(filtered.success()).isTrue();
        assertThat(filtered.message()).contains("\"count\":0");

        ToolResult get = call(tools, "supervisor", GmComplaintGetTool.NAME,
                Map.of("complaintId", submitted.complaintId()));
        assertThat(get.success()).isTrue();
        assertThat(get.message()).contains(BODY_MARKER, RESPONSE_MARKER, "PENDING");
    }

    @Test
    void respondWritesOnceIsIdempotentAndWrongRevisionOrStateHasNoSideEffects() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        ComplaintCommandResult submitted = service.submit(PLAYER, "投诉标题", BODY_MARKER, "s-1");
        List<AgentTool> tools = GmComplaintMcpTools.all(service, SUPERVISOR);
        Map<String, Object> args = Map.of(
                "complaintId", submitted.complaintId(),
                "response", RESPONSE_MARKER,
                "expectedRevision", 1L,
                "idempotencyKey", "respond-once");

        ToolResult responded = call(tools, "supervisor", GmComplaintRespondTool.NAME, args);
        assertThat(responded.success()).as(responded.message()).isTrue();
        assertThat(responded.message()).contains("RESPONDED", "\"revision\":2")
                .doesNotContain(RESPONSE_MARKER);
        Complaint after = service.getForGm(submitted.complaintId()).orElseThrow();
        assertThat(after.status()).isEqualTo(ComplaintStatus.RESPONDED);
        assertThat(after.revision()).isEqualTo(2);

        ToolResult replay = call(tools, "supervisor", GmComplaintRespondTool.NAME, args);
        assertThat(replay.success()).isTrue();
        assertThat(replay.message()).isEqualTo(responded.message());

        ToolResult wrongRevision = call(tools, "supervisor", GmComplaintRespondTool.NAME,
                Map.of("complaintId", submitted.complaintId(),
                        "response", "不应写入的回应",
                        "expectedRevision", 99L,
                        "idempotencyKey", "respond-wrong-revision"));
        assertThat(wrongRevision.success()).isFalse();
        assertThat(wrongRevision.code()).isEqualTo("REVISION_CONFLICT");

        ToolResult invalidStatus = call(tools, "supervisor", GmComplaintRespondTool.NAME,
                Map.of("complaintId", submitted.complaintId(),
                        "response", "不应写入的第二次回应",
                        "expectedRevision", 2L,
                        "idempotencyKey", "respond-invalid-status"));
        assertThat(invalidStatus.success()).isFalse();
        assertThat(invalidStatus.code()).isEqualTo("INVALID_STATUS");

        Complaint unchanged = service.getForGm(submitted.complaintId()).orElseThrow();
        assertThat(unchanged.revision()).isEqualTo(2);
        assertThat(unchanged.responseBody()).isEqualTo(RESPONSE_MARKER);
        assertThat(service.snapshot().idempotentResults())
                .doesNotContainKeys("respond-wrong-revision", "respond-invalid-status");
    }

    @Test
    void nonGmRolesCannotCallRespondOrReadTools() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        List<AgentTool> tools = GmComplaintMcpTools.all(service, SUPERVISOR);

        ToolResult secretary = call(tools, "secretary", GmComplaintRespondTool.NAME,
                Map.of("complaintId", "missing", "response", "非法回应",
                        "expectedRevision", 1L, "idempotencyKey", "bad"));
        assertThat(secretary.success()).isFalse();
        assertThat(secretary.code()).isEqualTo("PERMISSION_DENIED");
        ToolResult player = call(tools, "player", GmComplaintListTool.NAME, Map.of());
        assertThat(player.success()).isFalse();
        assertThat(player.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(service.snapshot().size()).isZero();
    }

    @Test
    void respondLedgerArgumentsDoNotContainResponseBody() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        AgentTool respond = find(GmComplaintMcpTools.all(service, SUPERVISOR),
                GmComplaintRespondTool.NAME);
        Map<String, Object> args = Map.of(
                "complaintId", "c-1",
                "response", RESPONSE_MARKER,
                "expectedRevision", 1L,
                "idempotencyKey", "ledger-respond");
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions("supervisor"),
                Map.of(), args, AgentIdentity.external());
        assertThat(respond.ledgerArgs(context))
                .containsEntry("complaintId", "c-1")
                .doesNotContainKey("response")
                .doesNotContainValue(RESPONSE_MARKER);
    }

    private static ToolResult call(List<AgentTool> tools, String role, String name,
                                   Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions(role),
                Map.of(), arguments, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, name, context);
    }

    private static AgentPermissionSet permissions(String role) {
        Set<String> allowed = new LinkedHashSet<>(GmComplaintMcpTools.NAMES);
        return AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allowed.toArray(String[]::new))
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        ComplaintStore.RESOURCE_NAMESPACE,
                        ResourceScope.of(ComplaintStore.RESOURCE_NAMESPACE))))
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .build();
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name()))
                .findFirst().orElseThrow();
    }
}
