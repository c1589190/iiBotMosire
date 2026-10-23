package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.ResourceScope;
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

/** SecretarySession 共享 Skill 可选注入、旧构造器兼容与 GM 对话只读行为。 */
class SecretarySessionSharedSkillTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyConstructorsStillWorkWithoutSharedSkillTools() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("旧会话可用")), f.noopBriefing(), f.director,
                f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15));

        assertThat(session.chat("你好")).isEqualTo("旧会话可用");
        assertThat(session.sharedSkillStore()).isNull();
        assertThat(session.tools()).extracting(tool -> tool.name())
                .doesNotContain(SecretarySharedSkillTools.LIST,
                        SecretarySharedSkillTools.READ);
        assertThat(session.permissions().isToolAllowed(
                SecretarySharedSkillTools.LIST)).isFalse();
    }

    @Test
    void sharedStoreAddsExactlyTwoToolsAndAllowListEntries() {
        AgentTestFixture f = AgentTestFixture.create();
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, store);

        assertThat(session.sharedSkillStore()).isSameAs(store);
        assertThat(session.tools()).extracting(tool -> tool.name())
                .containsExactly(
                        DecisionDraftCreateTool.NAME,
                        DecisionDraftUpdateTool.NAME,
                        RequestBriefTool.NAME,
                        SecretarySharedSkillTools.LIST,
                        SecretarySharedSkillTools.READ);
        assertThat(session.permissions().isToolAllowed(SecretarySharedSkillTools.LIST)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretarySharedSkillTools.READ)).isTrue();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(SharedSkillStore.RESOURCE_NAMESPACE))
                .isEqualTo(ResourceScope.of(SharedSkillStore.RESOURCE_NAMESPACE));
    }

    @Test
    void workspacePostAndSharedStoreCanBeCombinedWithoutAmbiguity() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore workspace = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1"));
        PostService posts = new PostService(new InMemoryPostStore());
        SharedSkillStore shared = SharedSkillStore.open(tempDir.resolve("shared"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, workspace, posts, shared);

        assertThat(session.workspaceStore()).isSameAs(workspace);
        assertThat(session.postService()).isSameAs(posts);
        assertThat(session.sharedSkillStore()).isSameAs(shared);
        assertThat(session.tools()).extracting(tool -> tool.name())
                .contains(SecretaryWorkspaceTools.LIST,
                        SecretaryWorkspaceTools.READ,
                        SecretaryWorkspaceTools.NOTE,
                        SecretarySharedSkillTools.LIST,
                        SecretarySharedSkillTools.READ,
                        SecretaryPostTools.LIST,
                        SecretaryPostTools.READ,
                        SecretaryPostTools.CREATE);
    }

    @Test
    void gmChatCanReadSharedSkillButCannotWriteSharedArea() {
        AgentTestFixture f = AgentTestFixture.create();
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        store.writeGm("skills/gm-guide.md", "GM 写入的共享响应指南", false);
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("shared-read", SecretarySharedSkillTools.READ,
                        Map.of("path", "skills/gm-guide.md")),
                LlmResponse.text("已依据共享指南回答 GM。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock, store);

        assertThat(session.gmChat("请阅读共享指南")).isEqualTo("已依据共享指南回答 GM。");
        assertThat(session.toolInvocations()).hasSize(1);
        assertThat(session.toolInvocations().getFirst().success()).isTrue();
        assertThat(session.toolInvocations().getFirst().result().message())
                .contains("GM 写入的共享响应指南")
                .doesNotContain(store.rootForDiagnostics().toString());
        assertThat(store.list("skills")).containsExactly("skills/gm-guide.md");
        assertThat(store.list("public")).isEmpty();
    }
}
