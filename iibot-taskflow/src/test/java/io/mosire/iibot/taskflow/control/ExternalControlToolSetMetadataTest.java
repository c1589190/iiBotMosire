package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolDefs;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 工具清单、schema 与权限元数据契约。 */
class ExternalControlToolSetMetadataTest {

    @Test
    void exposesCompleteControlSurfaceWithoutDecisionWrites() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        assertThat(control.size()).isEqualTo(18);
        assertThat(control.names()).containsExactly(
                ExternalControlToolSet.TF_STATUS,
                ExternalControlToolSet.TF_TURN_GET,
                ExternalControlToolSet.TF_EVENT_POLL,
                ExternalControlToolSet.TF_INVARIANT_CHECK,
                ExternalControlToolSet.TF_SETTING_LIST,
                ExternalControlToolSet.TF_REGISTER_PLAYER,
                ExternalControlToolSet.TF_REGISTER_DIRECTOR,
                ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                ExternalControlToolSet.TF_OPEN_TURN,
                ExternalControlToolSet.TF_LOCK_TURN,
                ExternalControlToolSet.TF_START_ADJUDICATION,
                ExternalControlToolSet.TF_COMMIT_ADJUDICATION,
                ExternalControlToolSet.TF_FAIL_ADJUDICATION,
                ExternalControlToolSet.TF_RETRY_ADJUDICATION,
                ExternalControlToolSet.TF_PUBLISH_TURN,
                ExternalControlToolSet.TF_ISSUE_BRIEF,
                ExternalControlToolSet.TF_SETTING_PROPOSE,
                ExternalControlToolSet.TF_SETTING_DECIDE);

        assertThat(control.names()).doesNotContain(
                "tf_submit_decision", "tf_confirm_decision", "decision_submit",
                "decision_confirm", "shutdown", "reload_config", "set_role",
                "qq_send_message", "shell", "write_file");

        Set<String> combined = new HashSet<>(ExternalControlToolSet.READ_TOOL_NAMES);
        combined.addAll(ExternalControlToolSet.WRITE_TOOL_NAMES);
        assertThat(combined).containsExactlyInAnyOrderElementsOf(control.names());
        assertThat(ExternalControlToolSet.READ_TOOL_NAMES)
                .doesNotContainAnyElementsOf(ExternalControlToolSet.WRITE_TOOL_NAMES);
    }

    @Test
    void everyToolDeclaresSupervisorRoleAndDenyDefaultTaskflowResources() {
        ExternalControlToolSet control = new ControlTestFixture().control();
        for (AgentTool tool : control) {
            assertThat(tool.roles()).containsExactly("supervisor");
            assertThat(tool.resources().namespaces()).containsExactly("taskflow");
            assertThat(tool.resources().policyFor("taskflow")).isEqualTo(ResourcePolicy.DENY);
            assertThat(tool.jsonSchema()).containsEntry("type", "object");
            assertThat(tool.jsonSchema()).containsKey("properties");
            assertThat(tool.jsonSchema()).containsKey("required");
            assertThat(tool.description()).isNotBlank();
        }
    }

    @Test
    void readToolsAreGuestAndWritesAreDefaultSensitive() {
        ExternalControlToolSet control = new ControlTestFixture().control();
        for (String name : ExternalControlToolSet.READ_TOOL_NAMES) {
            ToolSpec spec = control.find(name).orElseThrow().spec();
            assertThat(spec.requiredLevel()).isEqualTo(AccessToken.GUEST);
            assertThat(spec.sensitive()).isFalse();
            assertThat(spec.destructive()).isFalse();
        }
        for (String name : ExternalControlToolSet.WRITE_TOOL_NAMES) {
            ToolSpec spec = control.find(name).orElseThrow().spec();
            assertThat(spec.requiredLevel()).isEqualTo(AccessToken.DEFAULT);
            assertThat(spec.sensitive()).isTrue();
            assertThat(spec.destructive())
                    .as("只有 tf_setting_decide 是破坏性写工具")
                    .isEqualTo(ExternalControlToolSet.TF_SETTING_DECIDE.equals(name));
        }
    }

    @Test
    void writeSchemasRequireEnvelopeAndToolSpecificFields() {
        ExternalControlToolSet control = new ControlTestFixture().control();
        Map<String, List<String>> specific = Map.ofEntries(
                Map.entry(ExternalControlToolSet.TF_REGISTER_PLAYER,
                        List.of("qqId", "playerId", "displayName")),
                Map.entry(ExternalControlToolSet.TF_REGISTER_DIRECTOR,
                        List.of("qqId", "playerId", "displayName")),
                Map.entry(ExternalControlToolSet.TF_LOAD_WORLD_FACTS,
                        List.of("expectedWorldVersion", "facts")),
                Map.entry(ExternalControlToolSet.TF_OPEN_TURN, List.of("minutes")),
                Map.entry(ExternalControlToolSet.TF_LOCK_TURN, List.of()),
                Map.entry(ExternalControlToolSet.TF_START_ADJUDICATION, List.of()),
                Map.entry(ExternalControlToolSet.TF_COMMIT_ADJUDICATION,
                        List.of("publicNarrative", "playerOutcomes", "worldDeltas", "nextHooks")),
                Map.entry(ExternalControlToolSet.TF_FAIL_ADJUDICATION, List.of()),
                Map.entry(ExternalControlToolSet.TF_RETRY_ADJUDICATION, List.of()),
                Map.entry(ExternalControlToolSet.TF_PUBLISH_TURN, List.of()),
                Map.entry(ExternalControlToolSet.TF_ISSUE_BRIEF,
                        List.of("requestId", "content", "sourceFactIds")),
                Map.entry(ExternalControlToolSet.TF_SETTING_PROPOSE,
                        List.of("content", "visibility")),
                Map.entry(ExternalControlToolSet.TF_SETTING_DECIDE,
                        List.of("proposalId", "approve")));

        for (String name : ExternalControlToolSet.WRITE_TOOL_NAMES) {
            Map<String, Object> schema = control.find(name).orElseThrow().jsonSchema();
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertThat(required)
                    .as("write schema %s", name)
                    .contains("expectedRevision", "idempotencyKey", "reason")
                    .containsAll(specific.get(name));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> turnProps = (Map<String, Object>)
                control.find(ExternalControlToolSet.TF_TURN_GET).orElseThrow()
                        .jsonSchema().get("properties");
        assertThat(turnProps).containsKey("turnId");

        @SuppressWarnings("unchecked")
        Map<String, Object> eventProps = (Map<String, Object>)
                control.find(ExternalControlToolSet.TF_EVENT_POLL).orElseThrow()
                        .jsonSchema().get("properties");
        assertThat(eventProps).containsKeys("afterSeq", "limit");

        @SuppressWarnings("unchecked")
        Map<String, Object> settingListProps = (Map<String, Object>)
                control.find(ExternalControlToolSet.TF_SETTING_LIST).orElseThrow()
                        .jsonSchema().get("properties");
        assertThat(settingListProps).containsKey("proposalId");
        @SuppressWarnings("unchecked")
        List<String> settingListRequired = (List<String>)
                control.find(ExternalControlToolSet.TF_SETTING_LIST).orElseThrow()
                        .jsonSchema().get("required");
        assertThat(settingListRequired).isEmpty();

        @SuppressWarnings("unchecked")
        List<String> statusRequired = (List<String>)
                control.find(ExternalControlToolSet.TF_STATUS).orElseThrow()
                        .jsonSchema().get("required");
        assertThat(statusRequired).isEmpty();
    }

    @Test
    void schemasCanPassToolDefsOfAll() {
        ExternalControlToolSet control = new ControlTestFixture().control();
        assertThat(ToolDefs.ofAll(control.tools())).hasSize(control.size());
    }
}
