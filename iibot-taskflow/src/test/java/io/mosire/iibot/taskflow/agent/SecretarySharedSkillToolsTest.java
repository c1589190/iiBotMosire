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
import io.mosire.iibot.taskflow.skill.SharedSkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 秘书共享 Skill 工具的 metadata、授权、无 playerId 参数与截断行为。 */
class SecretarySharedSkillToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void toolsHaveSecretaryMetadataSharedResourceAndNoPlayerIdArgument() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        List<AgentTool> tools = SecretarySharedSkillTools.all(store);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactly(SecretarySharedSkillTools.LIST,
                        SecretarySharedSkillTools.READ);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.spec().sensitive()).isFalse();
            assertThat(tool.roles()).containsExactly("secretary");
            assertThat(tool.resources().namespaces())
                    .containsExactly(SharedSkillStore.RESOURCE_NAMESPACE);
            assertThat(tool.jsonSchema()).doesNotContainKey("playerId");
            assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
        });
        assertThat(tools.get(0).jsonSchema().toString()).doesNotContain("required=[path]");
    }

    @Test
    void authorizedListAndReadReturnOnlyLogicalPathsAndBody() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        store.writeGm("public/notice.md", "公共公告", false);
        store.writeGm("skills/guide.md", "秘书响应指南", false);

        ToolResult listed = execute(store, SecretarySharedSkillTools.LIST, Map.of(),
                permissions("secretary", true));
        assertThat(listed.success()).isTrue();
        assertThat(listed.message()).contains("public", "skills")
                .doesNotContain(store.rootForDiagnostics().toString());

        ToolResult read = execute(store, SecretarySharedSkillTools.READ,
                Map.of("path", "skills/guide.md"), permissions("secretary", true));
        assertThat(read.success()).isTrue();
        assertThat(read.message()).contains("秘书响应指南")
                .doesNotContain(store.rootForDiagnostics().toString());
    }

    @Test
    void modelSuppliedPlayerIdCannotChangeSharedReadTarget() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        store.writeGm("public/a.md", "A 公共内容", false);

        ToolResult read = execute(store, SecretarySharedSkillTools.READ,
                Map.of("path", "public/a.md", "playerId", "player-2"),
                permissions("secretary", true));

        assertThat(read.success()).isTrue();
        assertThat(read.message()).contains("A 公共内容")
                .doesNotContain("player-2")
                .doesNotContain(store.rootForDiagnostics().toString());
    }

    @Test
    void authorizerRejectsWrongRoleAndMissingSharedResourceScope() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        store.writeGm("public/notice.md", "公共公告", false);

        ToolResult wrongRole = execute(store, SecretarySharedSkillTools.LIST, Map.of(),
                permissions("gm", true));
        assertThat(wrongRole.code()).isEqualTo("PERMISSION_DENIED");

        ToolResult noScope = execute(store, SecretarySharedSkillTools.LIST, Map.of(),
                permissions("secretary", false));
        assertThat(noScope.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    }

    @Test
    void overlongReadIsTruncatedWithStableMarker() {
        SharedSkillStore store = SharedSkillStore.open(tempDir);
        store.writeGm("public/long.md",
                "a".repeat(SecretarySharedSkillTools.MAX_READ_RESULT_CHARS + 100), false);

        ToolResult read = execute(store, SecretarySharedSkillTools.READ,
                Map.of("path", "public/long.md"), permissions("secretary", true));

        assertThat(read.success()).isTrue();
        assertThat(read.message()).contains(SecretarySharedSkillTools.TRUNCATION_MARKER);
        assertThat(read.message().length())
                .isLessThan(SecretarySharedSkillTools.MAX_READ_RESULT_CHARS + 500);
    }

    private static ToolResult execute(SharedSkillStore store, String toolName,
                                      Map<String, Object> arguments,
                                      AgentPermissionSet permissions) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(SecretarySharedSkillTools.all(store));
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), arguments);
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static AgentPermissionSet permissions(String role, boolean withSharedScope) {
        AgentPermissionSet.Builder builder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretarySharedSkillTools.LIST, SecretarySharedSkillTools.READ)
                .roles(RoleSet.of(role));
        if (withSharedScope) {
            builder.resourceScopes(new ResourceScopeMap(Map.of(
                    SharedSkillStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(SharedSkillStore.RESOURCE_NAMESPACE))));
        }
        return builder.build();
    }
}
