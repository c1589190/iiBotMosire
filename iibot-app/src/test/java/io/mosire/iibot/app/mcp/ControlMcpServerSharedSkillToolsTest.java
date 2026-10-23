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
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 共享 Skill MCP 工具的 metadata、写入策略、幂等、路径与正文脱敏。 */
class ControlMcpServerSharedSkillToolsTest {

    private static final String BODY_MARKER = "共享正文-写入回执不得回显-93e1";

    @TempDir
    Path tempDir;

    @Test
    void toolsHaveSupervisorMetadataAndOnlyTheSharedSkillResource() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        List<AgentTool> tools = GmSharedSkillMcpTools.all(store);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactlyElementsOf(GmSharedSkillMcpTools.NAMES);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.roles()).containsExactly("supervisor")
                    .doesNotContain("*", "SYSTEM");
            assertThat(tool.resources().namespaces())
                    .containsExactly(SharedSkillStore.RESOURCE_NAMESPACE);
            assertThat(tool.resources().policyFor(SharedSkillStore.RESOURCE_NAMESPACE))
                    .isEqualTo(ResourcePolicy.DENY);
        });
        AgentTool write = find(tools, GmSharedSkillWriteTool.NAME);
        assertThat(write.spec().destructive()).isTrue();
        assertThat(find(tools, GmSharedSkillListTool.NAME).spec().destructive()).isFalse();
        assertThat(find(tools, GmSharedSkillGetTool.NAME).spec().destructive()).isFalse();
        assertThat(write.jsonSchema().toString())
                .contains("path", "content", "replace", "idempotencyKey", "reason")
                .doesNotContain("playerId");
    }

    @Test
    void writeListGetDefaultNoOverwriteReplaceAndIdempotencyWorkAsSpecified() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        List<AgentTool> tools = GmSharedSkillMcpTools.all(store);

        ToolResult created = call(tools, GmSharedSkillWriteTool.NAME, Map.of(
                "path", "skills/response-guide.md",
                "content", BODY_MARKER,
                "idempotencyKey", "shared-write-1",
                "reason", "初始化技能指南"));
        assertThat(created.success()).as(created.message()).isTrue();
        assertThat(created.message()).contains("created", "skills/response-guide.md")
                .doesNotContain(BODY_MARKER).doesNotContain(tempDir.toString());
        assertThat(store.read("skills/response-guide.md").orElseThrow())
                .isEqualTo(BODY_MARKER + "\n");

        ToolResult overwriteWithoutReplace = call(tools, GmSharedSkillWriteTool.NAME, Map.of(
                "path", "skills/response-guide.md",
                "content", "新正文不应覆盖",
                "idempotencyKey", "shared-write-2",
                "reason", "尝试覆盖"));
        assertThat(overwriteWithoutReplace.success()).isFalse();
        assertThat(overwriteWithoutReplace.code()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(overwriteWithoutReplace.message())
                .contains("默认拒绝覆盖").doesNotContain("新正文不应覆盖");
        assertThat(store.read("skills/response-guide.md").orElseThrow())
                .isEqualTo(BODY_MARKER + "\n");

        ToolResult replaced = call(tools, GmSharedSkillWriteTool.NAME, Map.of(
                "path", "skills/response-guide.md",
                "content", "替换后的技能正文",
                "replace", true,
                "idempotencyKey", "shared-write-3",
                "reason", "GM 更新指南"));
        assertThat(replaced.success()).as(replaced.message()).isTrue();
        assertThat(replaced.message()).contains("replaced")
                .doesNotContain("替换后的技能正文");
        assertThat(store.read("skills/response-guide.md").orElseThrow())
                .isEqualTo("替换后的技能正文\n");

        ToolResult first = call(tools, GmSharedSkillWriteTool.NAME, Map.of(
                "path", "public/notice.md",
                "content", "公共公告正文",
                "idempotencyKey", "shared-write-idem",
                "reason", "公告"));
        ToolResult replay = call(tools, GmSharedSkillWriteTool.NAME, Map.of(
                "path", "public/notice.md",
                "content", "重复键不得写入",
                "replace", true,
                "idempotencyKey", "shared-write-idem",
                "reason", "公告重放"));
        assertThat(first.success()).isTrue();
        assertThat(replay.success()).isTrue();
        assertThat(replay.message()).isEqualTo(first.message());
        assertThat(store.read("public/notice.md").orElseThrow()).isEqualTo("公共公告正文\n");

        ToolResult list = call(tools, GmSharedSkillListTool.NAME, Map.of());
        assertThat(list.success()).isTrue();
        assertThat(list.message()).contains("public", "skills")
                .doesNotContain(tempDir.toString());
        ToolResult publicList = call(tools, GmSharedSkillListTool.NAME,
                Map.of("path", "public"));
        assertThat(publicList.success()).isTrue();
        assertThat(publicList.message()).contains("notice.md")
                .doesNotContain(tempDir.toString());
        ToolResult get = call(tools, GmSharedSkillGetTool.NAME,
                Map.of("path", "public/notice.md"));
        assertThat(get.success()).isTrue();
        assertThat(get.message()).contains("公共公告正文").doesNotContain(tempDir.toString());
    }

    @Test
    void pathEscapeAbsoluteAndWrongRootAreRejectedWithoutWriting() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        List<AgentTool> tools = GmSharedSkillMcpTools.all(store);

        for (String path : List.of("../escape.md", "public/../escape.md", "/etc/passwd",
                "secret/value.md", "public/./x.md", "public\\\\x.md", "C:/x.md")) {
            ToolResult result = call(tools, GmSharedSkillWriteTool.NAME, Map.of(
                    "path", path,
                    "content", "越权正文",
                    "idempotencyKey", "escape-" + path.hashCode(),
                    "reason", "越权测试"));
            assertThat(result.success()).as("path=%s", path).isFalse();
            assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
            assertThat(result.message()).doesNotContain("越权正文");
        }
        assertThat(store.list("")).containsExactlyInAnyOrder("public", "skills");
        assertThat(store.read("public/escape.md")).isEmpty();
    }

    @Test
    void writeLedgerArgumentsDoNotContainDocumentBody() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        AgentTool write = GmSharedSkillWriteTool.class.cast(find(
                GmSharedSkillMcpTools.all(store), GmSharedSkillWriteTool.NAME));
        Map<String, Object> arguments = Map.of(
                "path", "skills/a.md",
                "content", BODY_MARKER,
                "idempotencyKey", "ledger-1",
                "reason", "审计");
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions("supervisor"),
                Map.of(), arguments, AgentIdentity.external());
        assertThat(write.ledgerArgs(context))
                .containsEntry("path", "skills/a.md")
                .doesNotContainKey("content")
                .doesNotContainValue(BODY_MARKER);
    }

    @Test
    void secretaryRoleIsDeniedByAuthorizer() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        List<AgentTool> tools = GmSharedSkillMcpTools.all(store);
        ToolResult denied = callAs(tools, "secretary", GmSharedSkillListTool.NAME, Map.of());
        assertThat(denied.success()).isFalse();
        assertThat(denied.code()).isEqualTo("PERMISSION_DENIED");
    }

    private static ToolResult call(List<AgentTool> tools, String name,
                                   Map<String, Object> arguments) {
        return callAs(tools, "supervisor", name, arguments);
    }

    private static ToolResult callAs(List<AgentTool> tools, String role, String name,
                                     Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions(role),
                Map.of(), arguments, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, name, context);
    }

    private static AgentPermissionSet permissions(String role) {
        Set<String> allowed = new LinkedHashSet<>(GmSharedSkillMcpTools.NAMES);
        return AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allowed.toArray(String[]::new))
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        SharedSkillStore.RESOURCE_NAMESPACE,
                        ResourceScope.of(SharedSkillStore.RESOURCE_NAMESPACE))))
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
