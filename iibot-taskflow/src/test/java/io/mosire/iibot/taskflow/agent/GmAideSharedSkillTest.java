package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.control.InMemoryEventReadPort;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 推演秘书共享 Skill 只读工具的装配、读取、截断与无写能力。 */
class GmAideSharedSkillTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyConstructorStillWorksWithoutSharedSkillTools() {
        AgentTestFixture f = AgentTestFixture.create();
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with(),
                new InMemoryEventReadPort(f.store));

        assertThat(agent.sharedSkillStore()).isNull();
        assertThat(agent.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrderElementsOf(GmAideTools.BASE_TOOL_NAMES);
        assertThat(agent.tools()).extracting(AgentTool::name)
                .doesNotContain(GmAideAgent.GM_SHARED_SKILLS,
                        GmAideAgent.GM_SHARED_SKILL_READ);
    }

    @Test
    void injectingSharedStoreAddsExactlyTwoReadOnlyTools() {
        AgentTestFixture f = AgentTestFixture.create();
        SharedSkillStore shared = SharedSkillStore.open(tempDir);
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with(), shared);

        assertThat(agent.sharedSkillStore()).isSameAs(shared);
        assertThat(agent.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                        GmAideTools.BASE_TOOL_NAMES.stream(),
                        GmAideTools.SHARED_SKILL_TOOL_NAMES.stream()).toList());
        assertThat(agent.tools())
                .filteredOn(tool -> GmAideTools.SHARED_SKILL_TOOL_NAMES.contains(tool.name()))
                .allSatisfy(tool -> {
                    assertThat(tool.roles()).containsExactly(GmAideAgent.ROLE);
                    assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
                    assertThat(tool.spec().destructive()).isFalse();
                    assertThat(tool.spec().sensitive()).isFalse();
                    assertThat(tool.resources().namespaces())
                            .containsExactly(SharedSkillStore.RESOURCE_NAMESPACE);
                    assertThat(tool.resources().policyFor(SharedSkillStore.RESOURCE_NAMESPACE))
                            .isEqualTo(ResourcePolicy.READ_ONLY);
                    assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
                });
    }

    @Test
    void allOptionalDependenciesProduceOnlyReadOnlyToolSurface() {
        AgentTestFixture f = AgentTestFixture.create();
        SecretaryWorkspaceStore workspace = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        PostService posts = new PostService(new InMemoryPostStore());
        SharedSkillStore shared = SharedSkillStore.open(tempDir.resolve("shared"));
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with(),
                new InMemoryEventReadPort(f.store), workspace, posts, shared);

        assertThat(agent.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrderElementsOf(GmAideTools.ALL_TOOL_NAMES);
        assertThat(agent.tools()).allSatisfy(tool -> {
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.spec().sensitive()).isFalse();
            assertThat(tool.name().toLowerCase())
                    .doesNotContain("write", "create", "update", "send", "approve",
                            "publish", "confirm", "shell", "exec");
        });
    }

    @Test
    void gmAideCanReadPublicAndSkillsSharedDocuments() {
        AgentTestFixture f = AgentTestFixture.create();
        SharedSkillStore shared = SharedSkillStore.open(tempDir);
        shared.writeGm("public/a-notice.md", "A 公共共享资料", false);
        shared.writeGm("skills/b-guide.md", "B 技能共享资料", false);
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("shared-list", GmAideAgent.GM_SHARED_SKILLS, Map.of()),
                LlmResponse.toolCall("shared-read-a", GmAideAgent.GM_SHARED_SKILL_READ,
                        Map.of("path", "public/a-notice.md")),
                LlmResponse.toolCall("shared-read-b", GmAideAgent.GM_SHARED_SKILL_READ,
                        Map.of("path", "skills/b-guide.md")),
                LlmResponse.text("共享资料整理完成。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, new InMemoryEventReadPort(f.store),
                null, null, shared);

        assertThat(agent.chat("读取 A/B 共享文档")).isEqualTo("共享资料整理完成。");
        assertThat(agent.toolInvocations()).hasSize(3).allSatisfy(invocation ->
                assertThat(invocation.success()).isTrue());
        String allResults = agent.toolInvocations().stream()
                .map(invocation -> invocation.result().message())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(allResults)
                .contains("public", "skills", "A 公共共享资料", "B 技能共享资料")
                .doesNotContain(shared.rootForDiagnostics().toString(), "qq-p1", "qq-p2");
        assertThat(llm.allPromptText()).contains("A 公共共享资料", "B 技能共享资料");
    }

    @Test
    void longSharedReadIsTruncatedWithStableMarker() {
        AgentTestFixture f = AgentTestFixture.create();
        SharedSkillStore shared = SharedSkillStore.open(tempDir);
        shared.writeGm("skills/long.md",
                "a".repeat(GmAideSharedSkillTools.MAX_READ_RESULT_CHARS + 100), false);
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("shared-long", GmAideAgent.GM_SHARED_SKILL_READ,
                        Map.of("path", "skills/long.md")),
                LlmResponse.text("截断结果已核对。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, shared);

        assertThat(agent.chat("读取长技能")).isEqualTo("截断结果已核对。");
        assertThat(agent.toolInvocations().getFirst().result().message())
                .contains("truncated=true", GmAideSharedSkillTools.TRUNCATION_MARKER)
                .doesNotContain(shared.rootForDiagnostics().toString());
    }
}
