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
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 秘书工作区工具的 metadata、授权器路径与玩家绑定。 */
class SecretaryWorkspaceToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void toolsHaveSecretaryMetadataAndNoPlayerIdArgument() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        List<AgentTool> tools = SecretaryWorkspaceTools.all(store);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactly(SecretaryWorkspaceTools.LIST, SecretaryWorkspaceTools.READ,
                        SecretaryWorkspaceTools.NOTE);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.spec().sensitive()).isFalse();
            assertThat(tool.roles()).isEqualTo(Set.of("secretary"));
            assertThat(tool.resources().namespaces())
                    .containsExactly(SecretaryWorkspaceStore.RESOURCE_NAMESPACE);
            assertThat(tool.jsonSchema()).doesNotContainKey("playerId");
            assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
        });
        assertThat(tools.get(0).jsonSchema().toString()).doesNotContain("required=[path]");
    }

    @Test
    void authorizedListReadAndNoteUseBoundPlayer() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        store.writeGmMaterial("player-1", "gm/order.md", "给秘书的材料");

        ToolResult listed = execute(store, SecretaryWorkspaceTools.LIST,
                Map.of(), permissions("secretary"));
        assertThat(listed.success()).isTrue();
        assertThat(listed.message()).contains("gm").contains("notes").contains("public").contains("player");
        assertThat(listed.message()).doesNotContain(store.rootFor("player-1").toString());

        ToolResult read = execute(store, SecretaryWorkspaceTools.READ,
                Map.of("path", "gm/order.md"), permissions("secretary"));
        assertThat(read.success()).isTrue();
        assertThat(read.message()).contains("给秘书的材料");
        assertThat(read.message()).doesNotContain(store.rootFor("player-1").toString());

        ToolResult note = execute(store, SecretaryWorkspaceTools.NOTE,
                Map.of("text", "秘书自己的笔记"), permissions("secretary"));
        assertThat(note.success()).isTrue();
        assertThat(store.list("player-1", "notes")).hasSize(1);
        assertThat(store.list("player-1", "gm")).containsExactly("gm/order.md");
    }

    @Test
    void playerCannotReadAnotherPlayersWorkspaceEvenWhenModelSuppliesPlayerId() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir,
                List.of("player-1", "player-2"));
        store.writeGmMaterial("player-2", "player/private.md", "player-2-secret-content");

        ToolResult result = executeForPlayer(store, "player-1", SecretaryWorkspaceTools.READ,
                Map.of("path", "player/private.md", "playerId", "player-2"),
                permissions("secretary"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("WORKSPACE_NOT_FOUND");
        assertThat(result.message())
                .doesNotContain("player-2-secret-content")
                .doesNotContain("player-2");
    }

    @Test
    void authorizerRejectsWrongRoleAndMissingResourceScope() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));

        ToolResult wrongRole = execute(store, SecretaryWorkspaceTools.LIST, Map.of(),
                permissions("gm"));
        assertThat(wrongRole.code()).isEqualTo("PERMISSION_DENIED");

        AgentPermissionSet noScope = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryWorkspaceTools.LIST)
                .roles(RoleSet.of("secretary"))
                .build();
        ToolResult noResource = execute(store, SecretaryWorkspaceTools.LIST, Map.of(), noScope);
        assertThat(noResource.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    }

    @Test
    void overlongReadIsTruncatedWithStableMarker() {
        SecretaryWorkspaceStore store = SecretaryWorkspaceStore.open(tempDir, List.of("player-1"));
        store.writeGmMaterial("player-1", "gm/long.md",
                "a".repeat(SecretaryWorkspaceTools.MAX_READ_RESULT_CHARS + 100));

        ToolResult read = execute(store, SecretaryWorkspaceTools.READ,
                Map.of("path", "gm/long.md"), permissions("secretary"));

        assertThat(read.success()).isTrue();
        assertThat(read.message()).contains(SecretaryWorkspaceTools.TRUNCATION_MARKER);
        assertThat(read.message().length())
                .isLessThan(SecretaryWorkspaceTools.MAX_READ_RESULT_CHARS + 500);
    }

    private static ToolResult execute(SecretaryWorkspaceStore store, String toolName,
                                      Map<String, Object> arguments,
                                      AgentPermissionSet permissions) {
        return executeForPlayer(store, "player-1", toolName, arguments, permissions);
    }

    private static ToolResult executeForPlayer(SecretaryWorkspaceStore store, String playerId,
                                               String toolName,
                                               Map<String, Object> arguments,
                                               AgentPermissionSet permissions) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(SecretaryWorkspaceTools.all(store));
        Map<String, Object> config = Map.of(
                SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, new Actor(playerId, ActorRole.PLAYER));
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                config, arguments);
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static AgentPermissionSet permissions(String role) {
        return AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryWorkspaceTools.LIST, SecretaryWorkspaceTools.READ,
                        SecretaryWorkspaceTools.NOTE)
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        SecretaryWorkspaceStore.RESOURCE_NAMESPACE,
                        ResourceScope.of("workspace"))))
                .build();
    }
}
