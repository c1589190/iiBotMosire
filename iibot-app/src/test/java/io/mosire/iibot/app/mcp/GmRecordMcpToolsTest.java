package io.mosire.iibot.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.LlmResponse;
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
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.records.RecordProjectionException;
import io.mosire.iibot.app.records.RecordQueryService;
import io.mosire.iibot.app.records.TurnRecordProjector;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.P6Fixture;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** P6 records MCP 工具：角色边界、过滤、安全相对 file 与无物理路径/正文摘要泄漏。 */
class GmRecordMcpToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void supervisorCanListGetAndQueryButSecretaryAndPlayerAreDenied() throws Exception {
        try (P6Fixture fixture = P6Fixture.open(tempDir.resolve("record-tools"))) {
            ActionBatch batch = fixture.commitBatch("记录工具批次", "record-tools",
                    List.of(P6Fixture.action("player-1", "记录工具行动一"),
                            P6Fixture.action("player-2", "记录工具行动二")));
            String publicBody = "记录工具公共正文-49ad";
            String privateOne = "记录工具玩家一私密-61fa";
            String privateTwo = "记录工具玩家二私密-cf30";
            FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text(json(publicBody,
                    Map.of("player-1", privateOne, "player-2", privateTwo))));
            PublicationOrchestrator orchestrator = fixture.publicationOrchestrator(llm);
            var record = orchestrator.generate(P6Fixture.SUPERVISOR, batch.batchId(),
                    "record-tools-generate");
            TurnRecordProjector projector = fixture.projector(orchestrator);
            projector.rebuildAll();
            RecordQueryService queryService = new RecordQueryService(fixture.dataDir, projector);
            List<AgentTool> tools = GmRecordMcpTools.all(queryService);

            assertThat(tools).extracting(AgentTool::name)
                    .containsExactlyElementsOf(GmRecordMcpTools.NAMES);
            assertThat(tools).allSatisfy(tool -> {
                assertThat(tool.roles()).containsExactly("supervisor")
                        .doesNotContain("*", "SYSTEM", "secretary", "player");
                assertThat(tool.resources().namespaces())
                        .containsExactly(GmRecordMcpTools.RESOURCE_NAMESPACE);
                assertThat(tool.resources().policyFor(GmRecordMcpTools.RESOURCE_NAMESPACE))
                        .isEqualTo(ResourcePolicy.DENY);
                assertThat(tool.jsonSchema().toString()).doesNotContain("*", "SYSTEM");
            });

            ToolResult list = callAsRole(tools, "supervisor",
                    GmRecordMcpTools.TURN_LIST, Map.of());
            assertThat(list.success()).isTrue();
            JsonNode listJson = JSON.readTree(list.message());
            assertThat(listJson.path("data").path("count").asInt()).isEqualTo(1);
            JsonNode turn = listJson.path("data").path("turns").get(0);
            assertThat(turn.path("turnId").asText()).isEqualTo(fixture.turnId());
            assertThat(turn.path("batchCount").asInt()).isEqualTo(1);
            assertThat(turn.path("postCount").asInt()).isEqualTo(3);
            assertThat(list.message()).contains(batch.batchId(), record.publicationId())
                    .doesNotContain(publicBody, privateOne, privateTwo,
                            fixture.dataDir.toString());

            ToolResult query = callAsRole(tools, "supervisor",
                    GmRecordMcpTools.QUERY, Map.of(
                            "kind", "post",
                            "turnId", fixture.turnId(),
                            "playerId", "player-1"));
            assertThat(query.success()).isTrue();
            JsonNode queryJson = JSON.readTree(query.message());
            assertThat(queryJson.path("data").path("count").asInt()).isEqualTo(1);
            JsonNode post = queryJson.path("data").path("records").get(0);
            assertThat(post.path("postId").asText())
                    .isEqualTo(record.privatePostIds().get("player-1"));
            assertThat(post.path("file").asText()).startsWith("posts/").endsWith("-PRIVATE.md");
            assertThat(query.message()).doesNotContain(publicBody, privateOne, privateTwo,
                    fixture.dataDir.toString());

            ToolResult get = callAsRole(tools, "supervisor",
                    GmRecordMcpTools.TURN_GET, Map.of(
                            "turnId", fixture.turnId(),
                            "file", "players/player-1"));
            assertThat(get.success()).isTrue();
            JsonNode getJson = JSON.readTree(get.message());
            assertThat(getJson.path("data").path("file").asText())
                    .isEqualTo("players/player-1.md");
            assertThat(getJson.path("data").path("content").asText()).contains(privateOne);
            assertThat(get.message()).doesNotContain(fixture.dataDir.toString());

            for (String role : List.of("secretary", "player", "gm", "director")) {
                for (String tool : GmRecordMcpTools.NAMES) {
                    ToolResult denied = callAsRole(tools, role, tool, Map.of(
                            "turnId", fixture.turnId(), "file", "index.md"));
                    assertThat(denied.code())
                            .as("role=%s tool=%s", role, tool)
                            .isEqualTo("PERMISSION_DENIED");
                }
            }

            ToolResult escape = callAsRole(tools, "supervisor",
                    GmRecordMcpTools.TURN_GET, Map.of(
                            "turnId", fixture.turnId(),
                            "file", "../outside.md"));
            assertThat(escape.success()).isFalse();
            assertThat(escape.code()).isEqualTo(RecordProjectionException.UNSAFE_PATH);
            assertThat(escape.message()).doesNotContain(fixture.dataDir.toString());
        }
    }

    private static ToolResult callAsRole(List<AgentTool> tools, String role,
                                         String toolName, Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(tools);
        Set<String> allowed = new LinkedHashSet<>(GmRecordMcpTools.NAMES);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(allowed.toArray(String[]::new))
                .roles(RoleSet.of(role))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        GmRecordMcpTools.RESOURCE_NAMESPACE,
                        ResourceScope.of(GmRecordMcpTools.RESOURCE_NAMESPACE))))
                .destructiveAllowed(true)
                .sensitiveAllowed(true)
                .readOnly(false)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), arguments, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static String json(String publicNarrative, Map<String, String> privateOutcomes) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("publicNarrative", publicNarrative);
            value.put("privateOutcomes", privateOutcomes);
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("测试 JSON 构造失败", failure);
        }
    }
}
