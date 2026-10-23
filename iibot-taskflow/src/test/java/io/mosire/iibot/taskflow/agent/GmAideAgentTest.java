package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostSnapshot;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 推演秘书的只读工具面、全量事实与会话隔离测试。 */
class GmAideAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void toolSurfaceIsReadOnlyAndEveryToolIsGmAideDefaultSpec() {
        AgentTestFixture f = AgentTestFixture.create();
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with(),
                new InMemoryEventReadPort(f.store));

        List<AgentTool> tools = agent.tools();
        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyInAnyOrder(
                        GmAideAgent.GM_WORLD_FACTS,
                        GmAideAgent.GM_TURN_VIEW,
                        GmAideAgent.GM_PROFILES,
                        GmAideAgent.GM_BRIEFS,
                        GmAideAgent.GM_EVENT_POLL);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly(GmAideAgent.ROLE);
            assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
            assertThat(tool.spec().sensitive()).isFalse();
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.name()).doesNotContain("send", "write", "create", "update",
                    "confirm", "shell", "exec", "secret", "key");
        });
    }

    @Test
    void chatCanSeeEveryWorldFactThroughAuthorizedToolAndKeepsSeparatePlayerPath() {
        AgentTestFixture f = AgentTestFixture.create();
        WorldFact publicFact = new WorldFact("pub", 1, "港口公开信息", Visibility.PUBLIC);
        WorldFact privateFact = new WorldFact("p1-only", 1, "玩家一私密线索",
                Visibility.forPlayers(Set.of("player-1")));
        WorldFact directorFact = new WorldFact("director-only", 1, "推演专用秘密",
                Visibility.DIRECTOR_ONLY);
        f.loadFacts(publicFact, privateFact, directorFact);
        long revisionBefore = f.engine.status().engineRevision();
        int factsBefore = f.engine.allWorldFacts().size();

        ScriptedLlm gmLlm = ScriptedLlm.with(
                LlmResponse.toolCall("facts-call", GmAideAgent.GM_WORLD_FACTS, Map.of()),
                LlmResponse.text("GM 分析：三条事实都可见。"));
        GmAideAgent gm = new GmAideAgent(f.engine, gmLlm);

        String gmResponse = gm.chat("整理全部世界资料");

        assertThat(gmResponse).isEqualTo("GM 分析：三条事实都可见。");
        assertThat(gm.toolInvocations()).hasSize(1);
        assertThat(gm.toolInvocations().getFirst().success()).isTrue();
        assertThat(gm.toolInvocations().getFirst().result().message())
                .contains("港口公开信息", "玩家一私密线索", "推演专用秘密")
                .contains("DIRECTOR_ONLY");
        assertThat(gmLlm.allPromptText())
                .contains("港口公开信息", "玩家一私密线索", "推演专用秘密");
        assertThat(f.engine.status().engineRevision()).isEqualTo(revisionBefore);
        assertThat(f.engine.allWorldFacts()).hasSize(factsBefore);

        ScriptedLlm playerLlm = ScriptedLlm.with(LlmResponse.text("玩家秘书回复"));
        SecretarySession secretary = new SecretarySession("player-1", f.p1, f.engine,
                playerLlm, f.noopBriefing(), f.director, f.clock);
        String playerResponse = secretary.chat("你好");

        assertThat(playerResponse).isEqualTo("玩家秘书回复");
        assertThat(playerLlm.allPromptText())
                .doesNotContain(gmResponse)
                .doesNotContain("推演专用秘密");
        assertThat(gm.contextWindow()).isNotSameAs(secretary.contextWindow());
    }

    @Test
    void turnViewToolReturnsDraftsAndConfirmedDecisions() {
        AgentTestFixture f = AgentTestFixture.create();
        f.openTurn();
        DecisionDraft draft = f.createDraftDirect(f.p1, "调查北码头");
        f.confirmDirect(f.p1, draft);
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("turn-call", GmAideAgent.GM_TURN_VIEW, Map.of()),
                LlmResponse.text("回合分析完成。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm);

        assertThat(agent.chat("看看当前回合")).isEqualTo("回合分析完成。");

        assertThat(agent.toolInvocations()).hasSize(1);
        assertThat(agent.toolInvocations().getFirst().result().message())
                .contains(draft.draftId())
                .contains("调查北码头")
                .contains("OPEN")
                .contains("decisions")
                .contains("committedProposal");
    }

    @Test
    void eventPollUsesExclusiveCursorAndLimit() {
        AgentTestFixture f = AgentTestFixture.create();
        f.loadFacts(new WorldFact("pub", 1, "一条公开事实", Visibility.PUBLIC));
        long lastSeq = f.store.lastSeq();
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("event-call", GmAideAgent.GM_EVENT_POLL,
                        Map.of("afterSeq", 0, "limit", 2)),
                LlmResponse.text("事件摘要完成。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, new InMemoryEventReadPort(f.store));

        assertThat(agent.chat("读取事件")).isEqualTo("事件摘要完成。");

        assertThat(agent.toolInvocations()).hasSize(1);
        String eventResult = agent.toolInvocations().getFirst().result().message();
        assertThat(eventResult)
                .contains("\"hasMore\":true")
                .contains("\"nextCursor\":2")
                .contains(EventType.ACTOR_REGISTERED.name());
        assertThat(f.store.lastSeq()).isEqualTo(lastSeq);
    }

    @Test
    void llmFailureReturnsStableChineseSummaryWithoutExceptionDetails() {
        AgentTestFixture f = AgentTestFixture.create();
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with());

        String response = agent.chat("请分析");

        assertThat(response)
                .isEqualTo("GM 推演秘书暂时无法回答，请稍后重试。")
                .doesNotContain("测试脚本已耗尽")
                .doesNotContain("Exception");
    }

    @Test
    void sameWindowIsSerializedAndReusedAcrossTurns() {
        AgentTestFixture f = AgentTestFixture.create();
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.text("第一轮"),
                LlmResponse.text("第二轮"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm);

        assertThat(agent.chat("第一问")).isEqualTo("第一轮");
        assertThat(agent.chat("第二问")).isEqualTo("第二轮");

        assertThat(llm.requests()).hasSize(2);
        assertThat(agent.contextWindow().messages().toString())
                .contains("第一问", "第一轮", "第二问", "第二轮");
    }

    @Test
    void injectedWorkspaceAndPostServicesAddExactlyFourReadOnlyTools() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        PostService postService = new PostService(new InMemoryPostStore());
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with(),
                new InMemoryEventReadPort(f.store), store, postService);

        List<AgentTool> tools = agent.tools();
        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyInAnyOrderElementsOf(GmAideTools.TOOL_NAMES);
        assertThat(agent.workspaceStore()).isSameAs(store);
        assertThat(agent.postService()).isSameAs(postService);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly(GmAideAgent.ROLE);
            assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
            assertThat(tool.spec().sensitive()).isFalse();
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.name()).doesNotContain("send", "write", "create", "update",
                    "confirm", "approve", "publish", "shell", "exec", "secret", "key");
        });
        assertThat(tools).filteredOn(tool -> GmAideTools.WORKSPACE_TOOL_NAMES.contains(tool.name()))
                .allSatisfy(tool -> {
                    assertThat(tool.resources().namespaces())
                            .containsExactly(SecretaryWorkspaceStore.RESOURCE_NAMESPACE);
                    assertThat(tool.resources().policyFor(SecretaryWorkspaceStore.RESOURCE_NAMESPACE))
                            .isEqualTo(ResourcePolicy.READ_ONLY);
                });
        assertThat(tools).filteredOn(tool -> GmAideTools.POST_TOOL_NAMES.contains(tool.name()))
                .allSatisfy(tool -> {
                    assertThat(tool.resources().namespaces())
                            .containsExactly(io.mosire.iibot.taskflow.post.PostStore.RESOURCE_NAMESPACE);
                    assertThat(tool.resources().policyFor(
                                    io.mosire.iibot.taskflow.post.PostStore.RESOURCE_NAMESPACE))
                            .isEqualTo(ResourcePolicy.READ_ONLY);
                });
    }

    @Test
    void gmAideCanReadBothPlayerWorkspacesAndAllPostsThroughTools() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        store.writeGmMaterial("player-1", "gm/a.md", "A 玩家工作区材料");
        store.writeGmMaterial("player-2", "gm/b.md", "B 玩家私密工作区材料");
        PostService postService = new PostService(new InMemoryPostStore());
        PostCommandResult publicPost = postService.createDraft(f.p1, "公共标题",
                "公共推文正文", PostVisibility.PUBLIC, 0L, "gm-aide-public-post");
        PostCommandResult privatePost = postService.createDraft(f.p2, "B 私人标题",
                "B 玩家私密推文正文", PostVisibility.PRIVATE, 0L, "gm-aide-private-post");
        assertThat(publicPost.ok()).isTrue();
        assertThat(privatePost.ok()).isTrue();

        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("ws-list", GmAideAgent.GM_WORKSPACE_LIST, Map.of()),
                LlmResponse.toolCall("ws-read-a", GmAideAgent.GM_WORKSPACE_READ,
                        Map.of("playerId", "player-1", "path", "gm/a.md")),
                LlmResponse.toolCall("ws-read-b", GmAideAgent.GM_WORKSPACE_READ,
                        Map.of("playerId", "player-2", "path", "gm/b.md")),
                LlmResponse.toolCall("posts", GmAideAgent.GM_POSTS, Map.of()),
                LlmResponse.toolCall("post-read", GmAideAgent.GM_POST_READ,
                        Map.of("postId", privatePost.postId())),
                LlmResponse.text("全量资料整理完成。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, new InMemoryEventReadPort(f.store),
                store, postService);

        assertThat(agent.chat("读取 A/B 工作区与全部推文")).isEqualTo("全量资料整理完成。");

        assertThat(agent.toolInvocations()).hasSize(5).allSatisfy(invocation ->
                assertThat(invocation.success()).isTrue());
        String allResults = agent.toolInvocations().stream()
                .map(invocation -> invocation.result().message())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(allResults)
                .contains("player-1", "player-2", "public", "player", "gm", "notes")
                .contains("A 玩家工作区材料", "B 玩家私密工作区材料")
                .contains(publicPost.postId(), privatePost.postId())
                .contains("PRIVATE", "PENDING_APPROVAL")
                .contains("B 玩家私密推文正文");
        assertThat(agent.toolInvocations()).filteredOn(invocation ->
                        GmAideAgent.GM_POSTS.equals(invocation.toolName()))
                .allSatisfy(invocation -> assertThat(invocation.result().message())
                        .doesNotContain("B 玩家私密推文正文", "公共推文正文"));
        assertThat(llm.allPromptText())
                .contains("A 玩家工作区材料", "B 玩家私密工作区材料", "B 玩家私密推文正文");
        assertThat(allResults).doesNotContain(store.rootFor("player-1").toString(),
                store.rootFor("player-2").toString(), "qq-p1", "qq-p2");
    }

    @Test
    void workspaceAndPostFailuresAreStableAndDoNotLeakPhysicalPaths() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        PostService postService = new PostService(new InMemoryPostStore());
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("unknown-player", GmAideAgent.GM_WORKSPACE_READ,
                        Map.of("playerId", "player-missing", "path", "gm/missing.md")),
                LlmResponse.toolCall("illegal-path", GmAideAgent.GM_WORKSPACE_READ,
                        Map.of("playerId", "player-1", "path", "gm/../player/escape.md")),
                LlmResponse.toolCall("unknown-post", GmAideAgent.GM_POST_READ,
                        Map.of("postId", "post-missing")),
                LlmResponse.text("失败情况已核对。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, new InMemoryEventReadPort(f.store),
                store, postService);

        assertThat(agent.chat("检查失败路径")).isEqualTo("失败情况已核对。");

        assertThat(agent.toolInvocations()).hasSize(3);
        assertThat(agent.toolInvocations().get(0).result().code())
                .isEqualTo(GmAideWorkspacePostTools.WORKSPACE_NOT_FOUND);
        assertThat(agent.toolInvocations().get(1).result().code())
                .isEqualTo(GmAideWorkspacePostTools.INVALID_ARGUMENTS);
        assertThat(agent.toolInvocations().get(2).result().code())
                .isEqualTo(GmAideWorkspacePostTools.POST_NOT_FOUND);
        assertThat(agent.toolInvocations().stream()
                .map(invocation -> invocation.result().message())
                .reduce("", (left, right) -> left + "\n" + right))
                .doesNotContain(store.rootFor("player-1").toString(),
                        tempDir.toString(), "qq-p1", "qq-p2");
    }

    @Test
    void longWorkspaceAndPostReadsAreTruncatedWithStableMarker() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        store.writeGmMaterial("player-1", "gm/long.md",
                "a".repeat(GmAideWorkspacePostTools.MAX_READ_RESULT_CHARS + 100));
        Post longPost = new Post("long-post", "player-1", PostVisibility.PRIVATE,
                "长推文", "b".repeat(GmAideWorkspacePostTools.MAX_READ_RESULT_CHARS + 200),
                PostStatus.PENDING_APPROVAL, 1L, AgentTestFixture.BASE,
                AgentTestFixture.BASE, null, null);
        PostService postService = new PostService(new InMemoryPostStore(
                new PostSnapshot(List.of(longPost), Map.of(), Map.of(), List.of())));
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("long-workspace", GmAideAgent.GM_WORKSPACE_READ,
                        Map.of("playerId", "player-1", "path", "gm/long.md")),
                LlmResponse.toolCall("long-post", GmAideAgent.GM_POST_READ,
                        Map.of("postId", longPost.postId())),
                LlmResponse.text("截断结果已核对。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, new InMemoryEventReadPort(f.store),
                store, postService);

        assertThat(agent.chat("读取超长资料")).isEqualTo("截断结果已核对。");

        assertThat(agent.toolInvocations()).hasSize(2).allSatisfy(invocation -> {
            assertThat(invocation.success()).isTrue();
            assertThat(invocation.result().message())
                    .contains(GmAideWorkspacePostTools.TRUNCATION_MARKER)
                    .contains("truncated=true");
            assertThat(invocation.result().message().length())
                    .isLessThan(GmAideWorkspacePostTools.MAX_READ_RESULT_CHARS + 500);
        });
    }
}
