package io.mosire.iibot.app.reply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.RecallCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotMessageRecallToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void routesRecallBySourceAndExplicitAccountWithoutConfirmation() throws Exception {
        AtomicReference<String> recalledByA = new AtomicReference<>();
        AtomicReference<String> recalledByB = new AtomicReference<>();
        BotMessageRecallTool tool = new BotMessageRecallTool(Map.of(
                "bot-a", fakeProvider(recalledByA),
                "bot-b", fakeProvider(recalledByB)));

        ToolResult bySource = execute(tool, Map.of(
                "messageId", "m-a",
                "source", "qq_group/bot-a/30003"));
        assertThat(bySource.success()).isTrue();
        assertThat(recalledByA).hasValue("m-a");
        assertThat(recalledByB.get()).isNull();
        JsonNode data = JSON.readTree(bySource.message());
        assertThat(data.path("accountId").asText()).isEqualTo("bot-a");
        assertThat(data.path("recalled").asBoolean()).isTrue();

        ToolResult byAccount = execute(tool, Map.of(
                "messageId", "m-b", "accountId", "bot-b"));
        assertThat(byAccount.success()).isTrue();
        assertThat(recalledByB).hasValue("m-b");
    }

    @Test
    void requiresAccountWhenMultipleRecallProvidersExist() {
        BotMessageRecallTool tool = new BotMessageRecallTool(Map.of(
                "bot-a", fakeProvider(new AtomicReference<>()),
                "bot-b", fakeProvider(new AtomicReference<>())));

        ToolResult result = execute(tool, Map.of("messageId", "m-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ACCOUNT_REQUIRED");
    }

    @Test
    void rejectsMismatchedSourceAndAccount() {
        BotMessageRecallTool tool = new BotMessageRecallTool(Map.of(
                "bot-a", fakeProvider(new AtomicReference<>()),
                "bot-b", fakeProvider(new AtomicReference<>())));

        ToolResult result = execute(tool, Map.of(
                "messageId", "m-1",
                "source", "qq_group/bot-a/30003",
                "accountId", "bot-b"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ACCOUNT_MISMATCH");
    }

    private static RecallCapability fakeProvider(AtomicReference<String> captured) {
        return messageId -> {
            captured.set(messageId);
            return CompletableFuture.completedFuture(
                    SendReceipt.sent(List.of(messageId)));
        };
    }

    private static ToolResult execute(BotMessageRecallTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
