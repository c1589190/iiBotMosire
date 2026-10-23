package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.complaint.InMemoryComplaintStore;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SecretarySession 的投诉服务可选注入、旧构造器兼容与 GM 对话可用性。 */
class SecretarySessionComplaintTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyConstructorStillWorksWithoutComplaintTools() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("旧会话可用")), f.noopBriefing(),
                f.director, f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15));

        assertThat(session.chat("你好")).isEqualTo("旧会话可用");
        assertThat(session.complaintService()).isNull();
        assertThat(session.tools()).extracting(AgentTool::name)
                .doesNotContain(SecretaryComplaintTools.SUBMIT,
                        SecretaryComplaintTools.LIST, SecretaryComplaintTools.READ);
        assertThat(session.permissions().isToolAllowed(SecretaryComplaintTools.SUBMIT)).isFalse();
        assertThat(session.permissions().isToolAllowed(SecretaryComplaintTools.LIST)).isFalse();
        assertThat(session.permissions().isToolAllowed(SecretaryComplaintTools.READ)).isFalse();
    }

    @Test
    void injectingComplaintServiceAddsExactlyThreeToolsAllowListAndResourceScope() {
        AgentTestFixture f = AgentTestFixture.create();
        ComplaintService complaints = new ComplaintService(new InMemoryComplaintStore(), f.clock);
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, complaints);

        assertThat(session.complaintService()).isSameAs(complaints);
        assertThat(session.tools()).extracting(AgentTool::name).containsExactly(
                DecisionDraftCreateTool.NAME,
                DecisionDraftUpdateTool.NAME,
                RequestBriefTool.NAME,
                SecretaryComplaintTools.SUBMIT,
                SecretaryComplaintTools.LIST,
                SecretaryComplaintTools.READ);
        assertThat(session.permissions().isToolAllowed(SecretaryComplaintTools.SUBMIT)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryComplaintTools.LIST)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryComplaintTools.READ)).isTrue();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(ComplaintStore.RESOURCE_NAMESPACE))
                .isEqualTo(ResourceScope.of(ComplaintStore.RESOURCE_NAMESPACE));
        assertThat(session.tools()).extracting(AgentTool::name)
                .noneMatch(name -> name.contains("respond") || name.contains("close"));
    }

    @Test
    void complaintServiceCombinesWithWorkspacePostAndSharedSkillServices() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore workspace = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1"));
        PostService posts = new PostService(new InMemoryPostStore(), f.clock);
        SharedSkillStore shared = SharedSkillStore.open(tempDir.resolve("shared"));
        ComplaintService complaints = new ComplaintService(new InMemoryComplaintStore(), f.clock);
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, workspace, posts, shared, complaints);

        assertThat(session.workspaceStore()).isSameAs(workspace);
        assertThat(session.postService()).isSameAs(posts);
        assertThat(session.sharedSkillStore()).isSameAs(shared);
        assertThat(session.complaintService()).isSameAs(complaints);
        assertThat(session.tools()).extracting(AgentTool::name)
                .contains(SecretaryWorkspaceTools.LIST,
                        SecretarySharedSkillTools.LIST,
                        SecretaryPostTools.LIST,
                        SecretaryComplaintTools.SUBMIT,
                        SecretaryComplaintTools.LIST,
                        SecretaryComplaintTools.READ);
        assertThat(session.permissions().resourceScopes()
                .declaredScope(ComplaintStore.RESOURCE_NAMESPACE)).isNotNull();
    }

    @Test
    void chatCanSubmitComplaintWithoutAnActiveTurnUsingBoundPlayer() {
        AgentTestFixture f = AgentTestFixture.create();
        ComplaintService complaints = new ComplaintService(new InMemoryComplaintStore(), f.clock);
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("complaint-submit", SecretaryComplaintTools.SUBMIT,
                        Map.of("title", "回合冻结投诉",
                                "body", "这是只应写入投诉存储的正文",
                                "idempotencyKey", "session-submit-1")),
                LlmResponse.text("投诉已提交。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, complaints);

        assertThat(f.engine.turnView()).isEmpty();
        assertThat(session.chat("我要投诉")).isEqualTo("投诉已提交。");
        assertThat(session.toolInvocations()).singleElement()
                .satisfies(invocation -> assertThat(invocation.success()).isTrue());
        assertThat(complaints.listForGm(null)).singleElement().satisfies(complaint -> {
            assertThat(complaint.playerId()).isEqualTo("player-1");
            assertThat(complaint.title()).isEqualTo("回合冻结投诉");
        });
    }

    @Test
    void gmChatCanUseComplaintToolsForCurrentPlayer() {
        AgentTestFixture f = AgentTestFixture.create();
        ComplaintService complaints = new ComplaintService(new InMemoryComplaintStore(), f.clock);
        ComplaintCommandResult submitted = complaints.submit(f.p1, "玩家投诉",
                "玩家提交的正文", "direct-submit");
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("complaint-list", SecretaryComplaintTools.LIST, Map.of()),
                LlmResponse.text("已转述该玩家的投诉。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, complaints);

        assertThat(session.gmChat("请转述该玩家的投诉")).isEqualTo("已转述该玩家的投诉。");
        assertThat(session.toolInvocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.success()).isTrue();
            assertThat(invocation.result().message()).contains(submitted.complaintId())
                    .doesNotContain("玩家提交的正文");
        });
    }

}
