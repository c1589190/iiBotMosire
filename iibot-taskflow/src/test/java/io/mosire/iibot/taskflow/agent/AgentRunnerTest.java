package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolDefs;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** AgentRunner 的工具调用必须经授权器，且 system prompt/历史顺序符合上下文契约。 */
class AgentRunnerTest {

    @Test
    void allowedToolRunsThroughAuthorizerAndToolResultIsWrittenBack() {
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("call-1", "allowed_tool", Map.of("value", "x")),
                LlmResponse.text("完成"));
        CountingTool tool = new CountingTool("allowed_tool");
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().build();
        ContextWindow window = new ContextWindow("runner-allowed");
        AgentRunner runner = new AgentRunner(llm);

        AgentRunResult result = runner.runTurn(window, "测试 system",
                List.of(tool), new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), Map.of()),
                "请调用工具");

        assertThat(result.text()).isEqualTo("完成");
        assertThat(tool.calls()).isEqualTo(1);
        assertThat(result.toolInvocations()).hasSize(1);
        assertThat(result.toolInvocations().get(0).success()).isTrue();
        assertThat(result.toolInvocations().get(0).arguments())
                .containsEntry("value", "x");
        assertThat(window.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.content().get(0)).isInstanceOf(ContentPart.ToolResult.class);
        });
        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().get(0).messages().get(0).role()).isEqualTo("system");
        assertThat(llm.requests().get(1).messages().get(0).role()).isEqualTo("system");
        assertThat(ToolDefs.ofAll(List.of(tool))).hasSize(1);
    }

    @Test
    void unauthorizedToolIsNeverExecutedButGetsToolErrorFeedback() {
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.toolCall("call-2", "forbidden_tool", Map.of()),
                LlmResponse.text("知道了"));
        CountingTool forbidden = new CountingTool("forbidden_tool");
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow("allowed_tool").build();
        ContextWindow window = new ContextWindow("runner-denied");

        AgentRunResult result = new AgentRunner(llm).runTurn(window, "测试 system",
                List.of(forbidden), new ToolContext(AccessToken.DEFAULT, permissions,
                        Map.of(), Map.of()), "试试");

        assertThat(forbidden.calls()).isZero();
        assertThat(result.toolInvocations()).hasSize(1);
        assertThat(result.toolInvocations().get(0).result().code())
                .isEqualTo("PERMISSION_DENIED");
        assertThat(window.messages()).filteredOn(message -> message.role().equals("tool"))
                .flatExtracting(message -> message.content())
                .filteredOn(ContentPart.ToolResult.class::isInstance)
                .extracting(part -> ((ContentPart.ToolResult) part).error())
                .anySatisfy(error -> assertThat(error).contains("PERMISSION_DENIED"));
    }

    private static final class CountingTool implements AgentTool {
        private final String name;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingTool(String name) {
            this.name = name;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "value", Map.of("type", "string")));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            calls.incrementAndGet();
            return ToolResult.ok("executed:" + context.arguments());
        }
    }
}
