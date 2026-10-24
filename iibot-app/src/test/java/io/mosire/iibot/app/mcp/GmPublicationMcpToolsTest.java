package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** P8-A 开场推文 MCP 工具：supervisor/strict 元数据、参数边界与零外发。 */
class GmPublicationMcpToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void openingToolDeclaresSupervisorRoleStrictSchemaAndDestructiveSensitiveSpec()
            throws Exception {
        try (P6Fixture fixture = P6Fixture.openForOpening(tempDir.resolve("opening-tool-metadata"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            List<AgentTool> tools = GmPublicationMcpTools.all(
                    orchestrator, P6Fixture.SUPERVISOR);

            assertThat(tools).extracting(AgentTool::name)
                    .containsExactlyElementsOf(GmPublicationMcpTools.NAMES)
                    .contains(GmPublicationMcpTools.OPENING);
            AgentTool opening = find(tools, GmPublicationMcpTools.OPENING);
            assertThat(opening.roles()).containsExactly("supervisor")
                    .doesNotContain("*", "SYSTEM", "gm", "director", "player");
            assertThat(opening.spec()).isEqualTo(
                    ToolSpec.level(AccessToken.DEFAULT, true, true));
            assertThat(opening.spec().requiredLevel()).isEqualTo(AccessToken.DEFAULT);
            assertThat(opening.spec().sensitive()).isTrue();
            assertThat(opening.spec().destructive()).isTrue();
            assertThat(opening.spec().noExport()).isFalse();
            assertThat(opening.resources().namespaces())
                    .containsExactly(GmPublicationMcpTools.RESOURCE_NAMESPACE);
            assertThat(opening.resources().policyFor(
                    GmPublicationMcpTools.RESOURCE_NAMESPACE)).isEqualTo(ResourcePolicy.DENY);

            @SuppressWarnings("unchecked")
            Map<String, Object> schema = opening.jsonSchema();
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat(schema.get("required"))
                    .isEqualTo(List.of("title", "body", "idempotencyKey"));
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties).containsOnlyKeys("title", "body", "idempotencyKey");
            assertThat(opening.jsonSchema().toString()).doesNotContain("*", "SYSTEM");

            String title = "MCP 开场标题-20c4";
            String body = "MCP 开场正文-b7e1";
            ToolResult result = callAsRole(tools, "supervisor",
                    GmPublicationMcpTools.OPENING, Map.of(
                            "title", title,
                            "body", body,
                            "idempotencyKey", "opening-mcp-success"));
            assertThat(result.success()).as(result.message()).isTrue();
            JsonNode data = JSON.readTree(result.message()).path("data");
            String publicationId = data.path("publicationId").asText();
            String publicPostId = data.path("publicPostId").asText();
            assertThat(publicationId).isNotBlank();
            assertThat(publicPostId).isNotBlank();
            assertThat(data.path("kind").asText()).isEqualTo("OPENING");
            assertThat(result.message()).doesNotContain(body, title,
                    fixture.dataDir.toString());
            Post post = fixture.postService.post(publicPostId).orElseThrow();
            assertThat(post.visibility()).isEqualTo(PostVisibility.PUBLIC);
            assertThat(post.status()).isEqualTo(PostStatus.PENDING_APPROVAL);
            assertThat(post.authorPlayerId())
                    .isEqualTo(PostService.SYSTEM_PUBLICATION_AUTHOR);
            assertThat(post.body()).isEqualTo(body);
            assertThat(fixture.postService.postsForGm()).hasSize(1);
            assertThat(fixture.publicationEvents()).hasSize(1);
        }
    }

    @Test
    void openingToolRejectsNonSupervisorAndInvalidArgumentsWithZeroPost() throws Exception {
        try (P6Fixture fixture = P6Fixture.openForOpening(tempDir.resolve("opening-tool-denied"))) {
            PublicationOrchestrator orchestrator =
                    fixture.publicationOrchestrator(FakeLlmClient.with());
            List<AgentTool> tools = GmPublicationMcpTools.all(
                    orchestrator, P6Fixture.SUPERVISOR);

            for (String role : List.of("player", "gm", "director", "secretary")) {
                ToolResult denied = callAsRole(tools, role,
                        GmPublicationMcpTools.OPENING, Map.of(
                                "title", "被拒绝标题",
                                "body", "被拒绝正文",
                                "idempotencyKey", "opening-denied-" + role));
                assertThat(denied.code()).as("role=%s", role)
                        .isEqualTo("PERMISSION_DENIED");
            }
            for (Map<String, Object> arguments : List.of(
                    Map.<String, Object>of("title", " ", "body", "正文", "idempotencyKey", "key"),
                    Map.<String, Object>of("title", "标题", "body", " ", "idempotencyKey", "key"),
                    Map.<String, Object>of("title", "标题", "body", "正文", "idempotencyKey", " "))) {
                ToolResult invalid = callAsRole(tools, "supervisor",
                        GmPublicationMcpTools.OPENING, arguments);
                assertThat(invalid.success()).isFalse();
                assertThat(invalid.code()).isEqualTo("INVALID_ARGUMENTS");
            }

            assertThat(fixture.postService.postsForGm()).isEmpty();
            assertThat(fixture.publicationEvents()).isEmpty();
            assertThat(fixture.packageService.batches()).isEmpty();
        }
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少工具 " + name));
    }

    private static ToolResult callAsRole(List<AgentTool> tools, String role,
                                         String toolName, Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        Set<String> allowed = new LinkedHashSet<>(GmPublicationMcpTools.NAMES);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allowed.toArray(String[]::new))
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        GmPublicationMcpTools.RESOURCE_NAMESPACE,
                        ResourceScope.of(GmPublicationMcpTools.RESOURCE_NAMESPACE))))
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), arguments, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }
}
