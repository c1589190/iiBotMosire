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
import io.mosire.iibot.taskflow.ticket.InMemoryTicketStore;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStore;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** P4 秘书工单工具：metadata、身份绑定、A/B 隔离、正文不回显到回执/ledger。 */
class SecretaryTicketToolsTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void toolsHaveSecretaryMetadataTicketResourceAndNoPlayerId() {
        TicketService service = new TicketService(new InMemoryTicketStore(), CLOCK);
        List<AgentTool> tools = SecretaryTicketTools.all(service);

        assertThat(tools).extracting(AgentTool::name).containsExactly(
                SecretaryTicketTools.SUBMIT, SecretaryTicketTools.LIST,
                SecretaryTicketTools.READ);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.spec()).isEqualTo(ToolSpec.level(AccessToken.DEFAULT, true, false));
            assertThat(tool.spec().sensitive()).isTrue();
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.roles()).isEqualTo(Set.of("secretary"));
            assertThat(tool.resources().namespaces())
                    .containsExactly(TicketStore.RESOURCE_NAMESPACE);
            assertThat(tool.jsonSchema()).doesNotContainKey("playerId");
            assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
        });
    }

    @Test
    void submitUsesBoundPlayerAndKeepsBodyOutOfLedgerAndReply() {
        TicketService service = new TicketService(new InMemoryTicketStore(), CLOCK);
        Map<String, Object> arguments = Map.of(
                "type", "COMPLAINT",
                "title", "工具投诉标题",
                "body", "只应进入工单存储的正文",
                "idempotencyKey", "ticket-tool-submit-1",
                "playerId", "player-b",
                "actor", GM);
        ToolResult result = execute(service, "player-a", SecretaryTicketTools.SUBMIT,
                arguments, permissions("secretary", true));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("ticketId", "COMPLAINT", "OPEN")
                .doesNotContain("只应进入工单存储的正文");
        assertThat(service.listForGm()).singleElement().satisfies(ticket -> {
            assertThat(ticket.playerId()).isEqualTo("player-a");
            assertThat(ticket.type()).isEqualTo(TicketType.COMPLAINT);
            assertThat(ticket.body()).isEqualTo("只应进入工单存储的正文");
        });

        AgentTool submit = SecretaryTicketTools.submit(service);
        ToolContext context = new ToolContext(AccessToken.DEFAULT,
                permissions("secretary", true), config("player-a"), arguments);
        assertThat(submit.ledgerArgs(context)).doesNotContainKey("body")
                .containsEntry("title", "工具投诉标题");
    }

    @Test
    void actionDraftSubmitReturnsOneShortConfirmationCodeWithoutBody() {
        TicketService service = new TicketService(new InMemoryTicketStore(), CLOCK);
        ToolResult result = execute(service, "player-a", SecretaryTicketTools.SUBMIT,
                Map.of("type", "ACTION_DRAFT",
                        "title", "行动草案标题",
                        "body", "行动正文只进领域存储",
                        "idempotencyKey", "ticket-tool-action"),
                permissions("secretary", true));

        assertThat(result.success()).isTrue();
        assertThat(result.message())
                .contains("玩家确认码", "/确认 ")
                .doesNotContain("行动正文只进领域存储");
        String ticketId = service.listForGm().getFirst().ticketId();
        assertThat(result.message()).contains(
                io.mosire.iibot.taskflow.ConfirmationCodes.forActionTicket(ticketId));
    }

    @Test
    void listAndReadArePlayerIsolatedAndForeignOrMissingShareStableError() {
        TicketService service = new TicketService(new InMemoryTicketStore(), CLOCK);
        TicketCommandResult a = service.submit(new Actor("player-a", ActorRole.PLAYER),
                TicketType.SETTING_QUERY, "A 标题", "A 绝密正文", "a-submit");
        TicketCommandResult b = service.submit(new Actor("player-b", ActorRole.PLAYER),
                TicketType.COMPLAINT, "B 标题", "B 绝密正文", "b-submit");
        service.reply(GM, a.ticketId(), 1, "A 的回复正文", "a-reply");

        ToolResult list = execute(service, "player-a", SecretaryTicketTools.LIST,
                Map.of(), permissions("secretary", true));
        assertThat(list.success()).isTrue();
        assertThat(list.message()).contains(a.ticketId()).contains("A 标题")
                .doesNotContain("A 绝密正文").doesNotContain("A 的回复正文")
                .doesNotContain("B 标题").doesNotContain(b.ticketId());

        ToolResult own = execute(service, "player-a", SecretaryTicketTools.READ,
                Map.of("ticketId", a.ticketId()), permissions("secretary", true));
        assertThat(own.success()).isTrue();
        assertThat(own.message()).contains("A 绝密正文", "A 的回复正文");

        ToolResult foreign = execute(service, "player-a", SecretaryTicketTools.READ,
                Map.of("ticketId", b.ticketId()), permissions("secretary", true));
        ToolResult missing = execute(service, "player-a", SecretaryTicketTools.READ,
                Map.of("ticketId", "missing"), permissions("secretary", true));
        assertThat(foreign.success()).isFalse();
        assertThat(foreign.code()).isEqualTo(TicketService.TICKET_NOT_FOUND);
        assertThat(missing.success()).isFalse();
        assertThat(missing.code()).isEqualTo(TicketService.TICKET_NOT_FOUND);

        ToolResult gmNote = execute(service, "player-a", SecretaryTicketTools.SUBMIT,
                Map.of("type", "GM_NOTE", "title", "t", "body", "b",
                        "idempotencyKey", "gm-note"), permissions("secretary", true));
        assertThat(gmNote.success()).isFalse();
        assertThat(gmNote.code()).isEqualTo(SecretaryTicketTools.INVALID_ARGUMENTS);
    }

    private static ToolResult execute(TicketService service, String playerId, String toolName,
                                      Map<String, Object> arguments,
                                      AgentPermissionSet permissions) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(SecretaryTicketTools.all(service));
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                config(playerId), arguments);
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static Map<String, Object> config(String playerId) {
        return Map.of(SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, new Actor(playerId, ActorRole.PLAYER));
    }

    private static AgentPermissionSet permissions(String role, boolean withTicketScope) {
        AgentPermissionSet.Builder builder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryTicketTools.SUBMIT, SecretaryTicketTools.LIST,
                        SecretaryTicketTools.READ)
                .sensitiveAllowed(true)
                .roles(RoleSet.of(role));
        if (withTicketScope) {
            builder.resourceScopes(new ResourceScopeMap(Map.of(
                    TicketStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(TicketStore.RESOURCE_NAMESPACE))));
        }
        return builder.build();
    }
}
