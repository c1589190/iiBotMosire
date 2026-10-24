package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
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
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.ActionPoolStore;
import io.mosire.iibot.taskflow.action.ActionStatus;
import io.mosire.iibot.taskflow.action.PlayerAction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecretaryActionToolsTest {

    private static final Actor P1 = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor P2 = new Actor("player-2", ActorRole.PLAYER);

    @Test
    void toolsHaveSecretaryActionPoolMetadataAndNoPlayerIdParameter() {
        ActionPoolService pool = new ActionPoolService(Clock.systemUTC());
        List<AgentTool> tools = SecretaryActionTools.all(pool);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactly(SecretaryActionTools.SUBMIT, SecretaryActionTools.LIST,
                        SecretaryActionTools.WITHDRAW);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).isEqualTo(Set.of("secretary"));
            assertThat(tool.resources().namespaces())
                    .containsExactly(ActionPoolStore.RESOURCE_NAMESPACE);
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.jsonSchema()).doesNotContainKey("playerId");
            assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
        });
        AgentTool submit = tools.get(0);
        assertThat(submit.spec().sensitive()).isTrue();
        assertThat(tools.get(1).spec()).isEqualTo(ToolSpec.DEFAULT);
        assertThat(tools.get(2).spec()).isEqualTo(ToolSpec.DEFAULT);
    }

    @Test
    void authorizedSubmitListWithdrawBindHostPlayerAndHideBodyInSummary() {
        ActionPoolService pool = new ActionPoolService(Clock.systemUTC());
        AgentPermissionSet permissions = permissions("secretary");

        ToolResult submitted = execute(pool, "player-1", SecretaryActionTools.SUBMIT,
                Map.of("text", "只让 GM 看到的正文", "source", "private-chat",
                        "idempotencyKey", "k1"), permissions);
        assertThat(submitted.success()).isTrue();
        assertThat(submitted.message()).doesNotContain("只让 GM 看到的正文")
                .contains("QUEUED");

        PlayerAction action = pool.listForPlayer("player-1").get(0);
        ToolResult listed = execute(pool, "player-1", SecretaryActionTools.LIST,
                Map.of(), permissions);
        assertThat(listed.success()).isTrue();
        assertThat(listed.message()).contains(action.actionId())
                .doesNotContain("只让 GM 看到的正文");

        ToolResult withdrawn = execute(pool, "player-1",
                SecretaryActionTools.WITHDRAW,
                Map.of("actionId", action.actionId(), "idempotencyKey", "w1"), permissions);
        assertThat(withdrawn.success()).isTrue();
        assertThat(pool.getForGm(action.actionId()).orElseThrow().status())
                .isEqualTo(ActionStatus.WITHDRAWN);
    }

    @Test
    void modelSuppliedPlayerIdCannotOverrideHostBinding() {
        ActionPoolService pool = new ActionPoolService(Clock.systemUTC());
        ToolResult result = execute(pool, "player-1", SecretaryActionTools.SUBMIT,
                Map.of("text", "行动", "source", "chat", "idempotencyKey", "k",
                        "playerId", "player-2"), permissions("secretary"));

        assertThat(result.success()).isTrue();
        assertThat(pool.listForPlayer("player-1")).hasSize(1);
        assertThat(pool.listForPlayer("player-2")).isEmpty();
    }

    @Test
    void authorizerRejectsWrongRoleAndMissingResourceScope() {
        ActionPoolService pool = new ActionPoolService(Clock.systemUTC());
        ToolResult wrongRole = execute(pool, "player-1", SecretaryActionTools.LIST,
                Map.of(), permissions("gm"));
        assertThat(wrongRole.code()).isEqualTo("PERMISSION_DENIED");

        AgentPermissionSet noScope = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryActionTools.SUBMIT, SecretaryActionTools.LIST,
                        SecretaryActionTools.WITHDRAW)
                .roles(RoleSet.of("secretary"))
                .sensitiveAllowed(true)
                .build();
        ToolResult noResource = execute(pool, "player-1", SecretaryActionTools.LIST,
                Map.of(), noScope);
        assertThat(noResource.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    }

    @Test
    void ledgerArgsNeverContainActionText() {
        ActionPoolService pool = new ActionPoolService(Clock.systemUTC());
        AgentTool submit = SecretaryActionTools.submit(pool);
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions("secretary"),
                Map.of(SecretaryToolContext.PLAYER_ID, "player-1",
                        SecretaryToolContext.ACTOR, P1),
                Map.of("text", "绝不进审计的正文", "source", "chat",
                        "idempotencyKey", "k"));

        Map<String, Object> ledger = submit.ledgerArgs(context);

        assertThat(ledger).doesNotContainKey("text");
        assertThat(ledger.toString()).doesNotContain("绝不进审计的正文");
    }

    @Test
    void legacySessionHasNoActionToolsWhileInjectedSessionAddsExactlyThreeAndGmChatCanUseThem() {
        AgentTestFixture f = AgentTestFixture.create();
        ActionPoolService pool = new ActionPoolService(Clock.systemUTC());
        SecretarySession legacy = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("旧")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15));
        assertThat(legacy.tools()).noneMatch(tool ->
                tool.name().startsWith("secretary_action_"));
        assertThat(legacy.permissions().isToolAllowed(SecretaryActionTools.SUBMIT)).isFalse();
        assertThat(legacy.actionPoolService()).isNull();

        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("action-call-1", SecretaryActionTools.SUBMIT,
                        Map.of("text", "GM 代当前玩家登记的意图", "source", "gm-chat",
                                "idempotencyKey", "session-k")),
                LlmResponse.text("已为当前玩家登记行动。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, pool);

        assertThat(session.tools()).extracting(AgentTool::name)
                .contains(SecretaryActionTools.SUBMIT, SecretaryActionTools.LIST,
                        SecretaryActionTools.WITHDRAW);
        assertThat(session.permissions().isToolAllowed(SecretaryActionTools.SUBMIT)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryActionTools.LIST)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryActionTools.WITHDRAW)).isTrue();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(ActionPoolStore.RESOURCE_NAMESPACE)).isNotNull();
        assertThat(session.actionPoolService()).isSameAs(pool);

        assertThat(session.gmChat("替当前玩家提交行动")).isEqualTo("已为当前玩家登记行动。");
        assertThat(session.toolInvocations()).hasSize(1);
        assertThat(session.toolInvocations().get(0).result().success()).isTrue();
        assertThat(pool.listForPlayer("player-1")).hasSize(1);
        assertThat(pool.listForPlayer("player-1").get(0).text())
                .isEqualTo("GM 代当前玩家登记的意图");
    }

    private static ToolResult execute(ActionPoolService pool, String playerId, String toolName,
                                      Map<String, Object> arguments,
                                      AgentPermissionSet permissions) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(SecretaryActionTools.all(pool));
        Map<String, Object> config = Map.of(
                SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, playerId.equals("player-1") ? P1 : P2);
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions, config,
                arguments);
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static AgentPermissionSet permissions(String role) {
        return AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryActionTools.SUBMIT, SecretaryActionTools.LIST,
                        SecretaryActionTools.WITHDRAW)
                .roles(RoleSet.of(role))
                .sensitiveAllowed(true)
                .resourceScopes(new ResourceScopeMap(Map.of(
                        ActionPoolStore.RESOURCE_NAMESPACE,
                        ResourceScope.of(ActionPoolStore.RESOURCE_NAMESPACE))))
                .build();
    }
}
