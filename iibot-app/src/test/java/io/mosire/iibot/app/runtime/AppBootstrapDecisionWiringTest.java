package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.decision.LlmActionBatchExecutor;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.agent.SecretaryActionTools;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.decision.ActionBatchExecutor;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 组合根把行动池/裁定包/批次执行器注入 MCP 与每个秘书会话。 */
@Timeout(60)
class AppBootstrapDecisionWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void bootstrapInjectsOneActionPoolIntoMcpAndEverySecretarySession() throws Exception {
        Path factsFile = TestSupport.writeFacts(tempDir.resolve("world").resolve("facts.json"), """
                {"facts": [
                  {"factId": "public-1", "content": "雾港公开事实", "visibility": "PUBLIC"}
                ]}
                """);
        AppConfig config = TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot.json"), configNode(tempDir.resolve("data"), factsFile)));
        FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("未调用"));
        AppRuntime runtime = AppBootstrap.build(config,
                BootstrapOptions.fake(SecretProvider.of(Map.of(
                                "IIBOT_NAPCAT_TOKEN", "napcat-token",
                                "IIBOT_CONTROL_TOKEN", "control-token")),
                        java.time.Clock.systemUTC(), llm, new FakeMessagingAdapter()));
        try {
            ControlMcpServer control = (ControlMcpServer) field(runtime, "controlPlane");
            ActionPoolService pool = (ActionPoolService) field(control, "actionPoolService");
            ActionPackageService packages = (ActionPackageService) field(
                    control, "actionPackageService");
            ActionBatchExecutor executor = (ActionBatchExecutor) field(
                    control, "actionBatchExecutor");
            assertThat(pool).isNotNull();
            assertThat(packages).isNotNull();
            assertThat(executor).isInstanceOf(LlmActionBatchExecutor.class);

            SecretarySessionRegistry sessions = (SecretarySessionRegistry) field(
                    control, "secretarySessions");
            assertThat(sessions).isNotNull();
            assertThat(sessions.all()).hasSize(2);
            for (SecretarySession session : sessions.all()) {
                assertThat(session.actionPoolService()).isSameAs(pool);
                assertThat(session.tools()).extracting(tool -> tool.name())
                        .contains(SecretaryActionTools.SUBMIT,
                                SecretaryActionTools.LIST,
                                SecretaryActionTools.WITHDRAW);
                assertThat(session.permissions().isToolAllowed(
                        SecretaryActionTools.SUBMIT)).isTrue();
            }
        } finally {
            runtime.close();
        }
    }

    private static ObjectNode configNode(Path dataDir, Path factsFile) {
        ObjectNode config = TestSupport.validConfigNode(dataDir, factsFile);
        ObjectNode control = config.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", "/decision-wiring/mcp");
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        return config;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
