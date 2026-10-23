package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SecretarySession 的可选推文工具注入与旧构造器兼容性。 */
class SecretarySessionPostToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyConstructorStillWorksWithoutPostTools() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("旧会话可用")), f.noopBriefing(),
                f.director, f.clock, Duration.ofMinutes(15), Duration.ofMinutes(15));

        assertThat(session.chat("你好")).isEqualTo("旧会话可用");
        assertThat(session.postService()).isNull();
        assertThat(session.tools()).extracting(tool -> tool.name())
                .doesNotContain(SecretaryPostTools.LIST, SecretaryPostTools.READ,
                        SecretaryPostTools.CREATE);
        assertThat(session.permissions().isToolAllowed(SecretaryPostTools.LIST)).isFalse();
    }

    @Test
    void injectingPostServiceAddsThreeToolsAndAllowListEntries() {
        AgentTestFixture f = AgentTestFixture.create();
        PostService postService = new PostService(new InMemoryPostStore(), f.clock);
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, postService);

        assertThat(session.postService()).isSameAs(postService);
        assertThat(session.tools()).extracting(tool -> tool.name())
                .contains(SecretaryPostTools.LIST, SecretaryPostTools.READ,
                        SecretaryPostTools.CREATE);
        assertThat(session.permissions().isToolAllowed(SecretaryPostTools.LIST)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryPostTools.READ)).isTrue();
        assertThat(session.permissions().isToolAllowed(SecretaryPostTools.CREATE)).isTrue();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(PostStore.RESOURCE_NAMESPACE)).isNotNull();
        assertThat(session.tools()).extracting(tool -> tool.name())
                .noneMatch(name -> name.contains("approve") || name.contains("publish"));
    }

    @Test
    void postAndWorkspaceToolsCanBeInjectedTogether() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore workspace = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        PostService postService = new PostService(new InMemoryPostStore(), f.clock);
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                ScriptedLlm.with(LlmResponse.text("ok")), f.noopBriefing(), f.director,
                f.clock, workspace, postService);

        assertThat(session.tools()).extracting(tool -> tool.name())
                .contains(SecretaryWorkspaceTools.LIST, SecretaryPostTools.LIST);
        assertThat(session.permissions().resourceScopes()
                .declaredScope(SecretaryWorkspaceStore.RESOURCE_NAMESPACE)).isNotNull();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(PostStore.RESOURCE_NAMESPACE)).isNotNull();
    }

    @Test
    void chatExecutesCreateToolWithSessionBoundPlayerAndNoBodyInResult() {
        AgentTestFixture f = AgentTestFixture.create();
        PostService postService = new PostService(new InMemoryPostStore(), f.clock);
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("post-call-1", SecretaryPostTools.CREATE, Map.of(
                        "title", "公告草稿",
                        "body", "正文只能保存在推文领域存储",
                        "visibility", "PUBLIC",
                        "expectedRevision", 0,
                        "idempotencyKey", "session-create-1")),
                LlmResponse.text("推文草稿已创建，等待 GM 审核。"));
        SecretarySession session = new SecretarySession("player-1", f.p1, f.engine,
                llm, f.noopBriefing(), f.director, f.clock, postService);

        String reply = session.chat("帮我起草一篇公告");

        assertThat(reply).isEqualTo("推文草稿已创建，等待 GM 审核。");
        assertThat(session.toolInvocations()).hasSize(1);
        assertThat(session.toolInvocations().get(0).result().success()).isTrue();
        assertThat(session.toolInvocations().get(0).result().message())
                .doesNotContain("正文只能保存在推文领域存储")
                .doesNotContain("公告草稿");
        assertThat(postService.postsForGm()).singleElement()
                .extracting(Post::authorPlayerId)
                .isEqualTo("player-1");
        assertThat(postService.postsForGm().get(0).visibility()).isEqualTo(PostVisibility.PUBLIC);
        assertThat(postService.postsForGm().get(0).status().name()).isEqualTo("PENDING_APPROVAL");
    }
}
