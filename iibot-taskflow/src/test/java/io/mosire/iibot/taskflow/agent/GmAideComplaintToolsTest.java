package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStore;
import io.mosire.iibot.taskflow.complaint.InMemoryComplaintStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GM 推演秘书投诉只读工具的装配、全量读取、截断与无写能力。 */
class GmAideComplaintToolsTest {

    private static final Actor PLAYER_A = new Actor("player-1", ActorRole.PLAYER);
    private static final Actor PLAYER_B = new Actor("player-2", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void legacyConstructorStillWorksWithoutComplaintTools() {
        AgentTestFixture f = AgentTestFixture.create();
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with());

        assertThat(agent.complaintService()).isNull();
        assertThat(agent.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrderElementsOf(GmAideTools.BASE_TOOL_NAMES)
                .doesNotContain(GmAideAgent.GM_COMPLAINTS, GmAideAgent.GM_COMPLAINT_READ);
    }

    @Test
    void injectingComplaintServiceAddsExactlyTwoReadOnlyTools() {
        AgentTestFixture f = AgentTestFixture.create();
        ComplaintService complaints = new ComplaintService(
                new InMemoryComplaintStore(), f.clock);
        GmAideAgent agent = new GmAideAgent(f.engine, ScriptedLlm.with(), complaints);

        assertThat(agent.complaintService()).isSameAs(complaints);
        assertThat(agent.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                        GmAideTools.BASE_TOOL_NAMES.stream(),
                        GmAideTools.COMPLAINT_TOOL_NAMES.stream()).toList());
        assertThat(agent.tools())
                .filteredOn(tool -> GmAideTools.COMPLAINT_TOOL_NAMES.contains(tool.name()))
                .allSatisfy(tool -> {
                    assertThat(tool.roles()).containsExactly(GmAideAgent.ROLE);
                    assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
                    assertThat(tool.spec().destructive()).isFalse();
                    assertThat(tool.spec().sensitive()).isFalse();
                    assertThat(tool.resources().namespaces())
                            .containsExactly(ComplaintStore.RESOURCE_NAMESPACE);
                    assertThat(tool.resources().policyFor(ComplaintStore.RESOURCE_NAMESPACE))
                            .isEqualTo(ResourcePolicy.READ_ONLY);
                    assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
                });
        assertThat(agent.tools()).extracting(AgentTool::name)
                .noneMatch(name -> name.contains("respond") || name.contains("write")
                        || name.contains("send") || name.contains("close"));
    }

    @Test
    void gmAideCanReadAllComplaintBodiesResponsesAndTruncatesLongContent() {
        AgentTestFixture f = AgentTestFixture.create();
        ComplaintService complaints = new ComplaintService(
                new InMemoryComplaintStore(), f.clock);
        ComplaintCommandResult a = complaints.submit(PLAYER_A, "A 标题", "A 正文-8a2c", "a-s");
        ComplaintCommandResult b = complaints.submit(PLAYER_B, "B 标题", "B 正文-1f4d", "b-s");
        assertThat(complaints.respond(GM, a.complaintId(), 1, "A 回应正文-47a1", "a-r").ok())
                .isTrue();
        String longBody = "x".repeat(GmAideComplaintTools.MAX_READ_RESULT_CHARS + 50);
        ComplaintCommandResult longComplaint = complaints.submit(
                PLAYER_A, "长投诉", longBody, "long-s");

        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("complaints-list", GmAideAgent.GM_COMPLAINTS, Map.of()),
                LlmResponse.toolCall("complaint-read-a", GmAideAgent.GM_COMPLAINT_READ,
                        Map.of("complaintId", a.complaintId())),
                LlmResponse.toolCall("complaint-read-b", GmAideAgent.GM_COMPLAINT_READ,
                        Map.of("complaintId", b.complaintId())),
                LlmResponse.toolCall("complaint-read-long", GmAideAgent.GM_COMPLAINT_READ,
                        Map.of("complaintId", longComplaint.complaintId())),
                LlmResponse.text("投诉整理完成。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, complaints);

        assertThat(agent.chat("读取全部投诉")).isEqualTo("投诉整理完成。");
        assertThat(agent.toolInvocations()).hasSize(4).allSatisfy(invocation ->
                assertThat(invocation.success()).isTrue());
        String allResults = agent.toolInvocations().stream()
                .map(invocation -> invocation.result().message())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(allResults)
                .contains(a.complaintId(), b.complaintId(), "A 正文-8a2c", "B 正文-1f4d",
                        "A 回应正文-47a1")
                .contains("bodyTruncated: true", GmAideComplaintTools.TRUNCATION_MARKER)
                .doesNotContain(longBody);
        assertThat(llm.allPromptText()).contains("A 正文-8a2c", "B 正文-1f4d");
    }

    @Test
    void missingComplaintReturnsStableNotFoundWithoutLeakingOtherBodies() {
        AgentTestFixture f = AgentTestFixture.create();
        ComplaintService complaints = new ComplaintService(
                new InMemoryComplaintStore(), f.clock);
        complaints.submit(PLAYER_A, "A 标题", "A 正文", "a-s");
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("missing", GmAideAgent.GM_COMPLAINT_READ,
                        Map.of("complaintId", "missing")),
                LlmResponse.text("未找到。"));
        GmAideAgent agent = new GmAideAgent(f.engine, llm, complaints);

        assertThat(agent.chat("读取不存在投诉")).isEqualTo("未找到。");
        assertThat(agent.toolInvocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.success()).isFalse();
            assertThat(invocation.result().code())
                    .isEqualTo(GmAideComplaintTools.COMPLAINT_NOT_FOUND);
            assertThat(invocation.result().message()).doesNotContain("A 正文");
        });
    }
}
