package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.Visibility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 秘书工具身份绑定与 draft/确认隔离测试。 */
class SecretaryAgentTest {

    @Test
    void secretaryCreatesDraftAndToolResultContainsPreviewHashAndConfirmHint() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("pub", 1, "港口公开信息", Visibility.PUBLIC));
        f.openTurn();
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("draft-call", DecisionDraftCreateTool.NAME, Map.of(
                        "intent", "调查港口",
                        "actions", List.of("询问船工", "检查仓库"),
                        "resources", Map.of("actionPoints", "2"),
                        "target", "北码头",
                        "contingency", "天黑前撤回")),
                LlmResponse.text("草稿已生成，请确认。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, Duration.ofMinutes(15),
                Duration.ofMinutes(15));

        String finalText = session.chat("帮我起草调查港口的决策");

        assertThat(finalText).isEqualTo("草稿已生成，请确认。");
        assertThat(f.turn().drafts()).hasSize(1);
        DecisionDraft draft = f.turn().drafts().get(0);
        assertThat(draft.playerId()).isEqualTo("player-1");
        assertThat(draft.previewHash()).isNotBlank();
        assertThat(session.toolInvocations()).hasSize(1);
        ToolResult result = session.toolInvocations().get(0).result();
        assertThat(result.success()).isTrue();
        assertThat(result.message())
                .contains(draft.draftId())
                .contains(draft.previewHash())
                .contains(draft.turnId())
                .contains("`/确认 <draftId>`");
    }

    @Test
    void playerAToolContextCannotCreateOrUpdatePlayerBDraft() {
        AgentTestFixture f = AgentTestFixture.create();
        f.openTurn();
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(DecisionDraftCreateTool.NAME, DecisionDraftUpdateTool.NAME)
                .roles(RoleSet.of("secretary"))
                .build();
        ToolContext aContext = context(f, f.p1, permissions,
                Map.of("intent", "帮 B 创建", "playerId", "player-2"));

        ToolResult created = new DecisionDraftCreateTool(f.engine, f.clock).execute(aContext);

        assertThat(created.success()).isTrue();
        DecisionDraft aDraft = f.turn().drafts().get(0);
        assertThat(aDraft.playerId()).isEqualTo("player-1");

        ToolContext bContext = context(f, f.p2, permissions,
                Map.of("draftId", aDraft.draftId(), "intent", "篡改"));
        ToolResult updated = new DecisionDraftUpdateTool(f.engine).execute(bContext);
        assertThat(updated.code()).isEqualTo("DRAFT_NOT_OWNED");

        CommandResult confirmedByB = f.engine.confirmDecision(f.p2, aDraft.draftId(),
                aDraft.previewHash(), f.turn().revision(), "confirm-by-b");
        assertThat(confirmedByB.code()).isEqualTo(io.mosire.iibot.taskflow.ErrorCode.DRAFT_NOT_OWNED);
        assertThat(f.turn().decisions()).isEmpty();
    }

    private static ToolContext context(AgentTestFixture f,
                                       io.mosire.iibot.taskflow.Actor actor,
                                       AgentPermissionSet permissions,
                                       Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(
                SecretaryToolContext.PLAYER_ID, actor.actorId(),
                SecretaryToolContext.ACTOR, actor,
                SecretaryToolContext.TURN_ID, f.turn().turnId(),
                SecretaryToolContext.DRAFT_TTL, Duration.ofMinutes(15)),
                arguments);
    }
}
