package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.AccessToken;
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
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.complaint.InMemoryComplaintStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 秘书投诉工具的 metadata、身份绑定、可见性与资源授权。 */
class SecretaryComplaintToolsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_A = new Actor("player-a", ActorRole.PLAYER);
    private static final Actor PLAYER_B = new Actor("player-b", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void toolsHaveSecretaryMetadataComplaintResourceAndNoPlayerIdOrRespondTool() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        List<AgentTool> tools = SecretaryComplaintTools.all(service);

        assertThat(tools).extracting(AgentTool::name).containsExactly(
                SecretaryComplaintTools.SUBMIT,
                SecretaryComplaintTools.LIST,
                SecretaryComplaintTools.READ);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.spec()).isEqualTo(ToolSpec.level(AccessToken.DEFAULT, true, false));
            assertThat(tool.spec().sensitive()).isTrue();
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.roles()).isEqualTo(Set.of("secretary"));
            assertThat(tool.resources().namespaces())
                    .containsExactly(ComplaintStore.RESOURCE_NAMESPACE);
            assertThat(tool.jsonSchema()).doesNotContainKey("playerId");
            assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
        });
        assertThat(tools).extracting(AgentTool::name)
                .noneMatch(name -> name.contains("respond") || name.contains("close")
                        || name.contains("delete"));
    }

    @Test
    void submitUsesBoundPlayerWithoutTurnAndKeepsBodyOutOfLedger() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        Map<String, Object> arguments = Map.of(
                "title", "投诉标题", "body", "只应进入投诉存储的正文",
                "idempotencyKey", "tool-submit-1",
                "playerId", "player-b", "actor", GM);
        ToolResult result = execute(service, "player-a", SecretaryComplaintTools.SUBMIT,
                arguments, permissions("secretary", true));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("complaintId").contains("SUBMITTED")
                .doesNotContain("只应进入投诉存储的正文");
        assertThat(service.listForGm(null)).singleElement().satisfies(complaint -> {
            assertThat(complaint.playerId()).isEqualTo("player-a");
            assertThat(complaint.title()).isEqualTo("投诉标题");
            assertThat(complaint.body()).isEqualTo("只应进入投诉存储的正文");
        });

        AgentTool submit = SecretaryComplaintTools.submit(service);
        ToolContext context = new ToolContext(AccessToken.DEFAULT,
                permissions("secretary", true), Map.of(), arguments);
        assertThat(submit.ledgerArgs(context)).doesNotContainKey("body")
                .containsEntry("title", "投诉标题");
    }

    @Test
    void listOnlyReturnsOwnSummariesWithoutBodiesOrResponse() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        ComplaintCommandResult a = service.submit(PLAYER_A, "A 标题", "A 绝密正文", "a-s");
        service.submit(PLAYER_B, "B 标题", "B 绝密正文", "b-s");
        service.respond(GM, a.complaintId(), 1, "A 的绝密回应", "a-r");

        ToolResult list = execute(service, "player-a", SecretaryComplaintTools.LIST,
                Map.of(), permissions("secretary", true));

        assertThat(list.success()).isTrue();
        assertThat(list.message()).contains(a.complaintId()).contains("A 标题")
                .contains("RESPONDED").contains("respondedAt=")
                .doesNotContain("B 标题").doesNotContain("A 绝密正文")
                .doesNotContain("A 的绝密回应");
    }

    @Test
    void readReturnsOwnBodyAndGmResponseButForeignOrMissingIsStableNotFound() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);
        ComplaintCommandResult a = service.submit(PLAYER_A, "A 标题", "A 正文", "a-s");
        service.respond(GM, a.complaintId(), 1, "GM 回应正文", "a-r");
        ComplaintCommandResult b = service.submit(PLAYER_B, "B 标题", "B 正文", "b-s");

        ToolResult own = execute(service, "player-a", SecretaryComplaintTools.READ,
                Map.of("complaintId", a.complaintId()), permissions("secretary", true));
        assertThat(own.success()).isTrue();
        assertThat(own.message()).contains("A 正文").contains("GM 回应正文")
                .contains("respondedBy: gm-1");

        ToolResult foreign = execute(service, "player-a", SecretaryComplaintTools.READ,
                Map.of("complaintId", b.complaintId()), permissions("secretary", true));
        ToolResult missing = execute(service, "player-a", SecretaryComplaintTools.READ,
                Map.of("complaintId", "missing"), permissions("secretary", true));
        assertThat(foreign.success()).isFalse();
        assertThat(foreign.code()).isEqualTo(SecretaryComplaintTools.NOT_FOUND);
        assertThat(missing.code()).isEqualTo(SecretaryComplaintTools.NOT_FOUND);
        assertThat(foreign.message()).isEqualTo(missing.message());
        assertThat(foreign.message()).doesNotContain("B 正文").doesNotContain("B 标题");
    }

    @Test
    void authorizerRejectsWrongRoleAndMissingComplaintResourceScope() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);

        ToolResult wrongRole = execute(service, "player-a", SecretaryComplaintTools.LIST,
                Map.of(), permissions("gm", true));
        assertThat(wrongRole.code()).isEqualTo("PERMISSION_DENIED");

        ToolResult noResource = execute(service, "player-a", SecretaryComplaintTools.LIST,
                Map.of(), permissions("secretary", false));
        assertThat(noResource.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    }

    @Test
    void submitInvalidArgumentsReturnStableCodeWithoutWriting() {
        ComplaintService service = new ComplaintService(new InMemoryComplaintStore(), CLOCK);

        ToolResult blank = execute(service, "player-a", SecretaryComplaintTools.SUBMIT,
                Map.of("title", " ", "body", "正文", "idempotencyKey", "k"),
                permissions("secretary", true));

        assertThat(blank.success()).isFalse();
        assertThat(blank.code()).isEqualTo(SecretaryComplaintTools.INVALID_ARGUMENTS);
        assertThat(service.snapshot().size()).isZero();
    }

    private static ToolResult execute(ComplaintService service, String playerId, String toolName,
                                      Map<String, Object> arguments,
                                      AgentPermissionSet permissions) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(SecretaryComplaintTools.all(service));
        Map<String, Object> config = Map.of(
                SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, new Actor(playerId, ActorRole.PLAYER));
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                config, arguments);
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static AgentPermissionSet permissions(String role, boolean withComplaintScope) {
        AgentPermissionSet.Builder builder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryComplaintTools.SUBMIT, SecretaryComplaintTools.LIST,
                        SecretaryComplaintTools.READ)
                .sensitiveAllowed(true)
                .roles(RoleSet.of(role));
        if (withComplaintScope) {
            builder.resourceScopes(new ResourceScopeMap(Map.of(
                    ComplaintStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(ComplaintStore.RESOURCE_NAMESPACE))));
        }
        return builder.build();
    }
}
