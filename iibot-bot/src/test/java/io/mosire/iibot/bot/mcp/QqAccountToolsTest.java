package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class QqAccountToolsTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_TOKEN = "token-very-secret-7a3f";

    @Test
    void accountListNeverReturnsSecretsAndStatusSelectsByAccountId() throws Exception {
        AccountRegistry multi = new AccountRegistry();
        multi.register("account-b", new FakeAdapter("adapter-b",
                new AdapterStatus(AdapterStatus.ConnectionState.RECONNECTING, T0,
                        "AUTH_FAILED", "Bearer " + SECRET_TOKEN
                                + " https://example.invalid/ws?access_token=" + SECRET_TOKEN)));
        multi.register("account-a", new FakeAdapter("adapter-a",
                new AdapterStatus(AdapterStatus.ConnectionState.CONNECTED, T0, null, null)));

        ToolResult listResult = execute(new QqAccountListTool(multi), Map.of());
        assertThat(listResult.success()).isTrue();
        assertThat(listResult.message()).doesNotContain(SECRET_TOKEN, "https://", "token=");
        JsonNode list = JSON.readTree(listResult.message()).path("data");
        assertThat(list.path("count").asInt()).isEqualTo(2);
        assertThat(list.path("accounts")).extracting(node -> node.path("accountId").asText())
                .containsExactly("account-a", "account-b");
        assertThat(list.path("accounts").get(1).path("adapterId").asText())
                .isEqualTo("adapter-b");
        assertThat(list.path("accounts").get(1).path("state").asText())
                .isEqualTo("RECONNECTING");
        assertThat(list.path("accounts").get(1).path("changedAt").asText())
                .isEqualTo(T0.toString());

        ToolResult missing = execute(new QqAccountStatusTool(multi), Map.of());
        assertThat(missing.success()).isFalse();
        assertThat(missing.code()).isEqualTo("ACCOUNT_REQUIRED");

        ToolResult selected = execute(new QqAccountStatusTool(multi),
                Map.of("accountId", "account-b"));
        assertThat(selected.success()).isTrue();
        assertThat(selected.message()).doesNotContain(SECRET_TOKEN, "https://");
        JsonNode selectedData = JSON.readTree(selected.message()).path("data");
        assertThat(selectedData.path("accountId").asText()).isEqualTo("account-b");
        assertThat(selectedData.path("state").asText()).isEqualTo("RECONNECTING");
        assertThat(selectedData.path("lastErrorCode").asText()).isEqualTo("AUTH_FAILED");
        assertThat(selectedData.path("lastErrorMessage").asText())
                .contains("[URL已脱敏]");

        AccountRegistry single = new AccountRegistry();
        single.register("account-a", new FakeAdapter("adapter-a",
                new AdapterStatus(AdapterStatus.ConnectionState.CONNECTED, T0, null, null)));
        ToolResult defaulted = execute(new QqAccountStatusTool(single), Map.of());
        assertThat(defaulted.success()).isTrue();
        assertThat(JSON.readTree(defaulted.message()).path("data").path("accountId").asText())
                .isEqualTo("account-a");
    }

    @Test
    void loginInfoDefaultsForSingleAccountAndRequiresSelectionForMultiple() throws Exception {
        AccountRegistry single = new AccountRegistry();
        single.register("account-a", new InfoAdapter("adapter-a",
                () -> CompletableFuture.completedFuture(new AccountInfo("qq-10001", "Alice"))));

        ToolResult singleResult = execute(new QqAccountLoginInfoTool(single), Map.of());
        assertThat(singleResult.success()).isTrue();
        JsonNode singleData = JSON.readTree(singleResult.message()).path("data");
        assertThat(singleData.path("accountId").asText()).isEqualTo("account-a");
        assertThat(singleData.path("adapterId").asText()).isEqualTo("adapter-a");
        assertThat(singleData.path("loginAccountId").asText()).isEqualTo("qq-10001");
        assertThat(singleData.path("nickname").asText()).isEqualTo("Alice");

        AccountRegistry multi = new AccountRegistry();
        multi.register("account-a", new InfoAdapter("adapter-a",
                () -> CompletableFuture.completedFuture(new AccountInfo("qq-10001", "Alice"))));
        multi.register("account-b", new FakeAdapter("adapter-b", null));

        ToolResult missing = execute(new QqAccountLoginInfoTool(multi), Map.of());
        assertThat(missing.success()).isFalse();
        assertThat(missing.code()).isEqualTo("ACCOUNT_REQUIRED");

        ToolResult unsupported = execute(new QqAccountLoginInfoTool(multi),
                Map.of("accountId", "account-b"));
        assertThat(unsupported.success()).isFalse();
        assertThat(unsupported.code()).isEqualTo("UNSUPPORTED_ACCOUNT_INFO");

        AccountRegistry failing = new AccountRegistry();
        failing.register("account-a", new InfoAdapter("adapter-a", () -> {
            throw new IllegalStateException("Bearer " + SECRET_TOKEN
                    + " https://example.invalid/protocol");
        }));
        ToolResult failed = execute(new QqAccountLoginInfoTool(failing), Map.of());
        assertThat(failed.success()).isFalse();
        assertThat(failed.code()).isEqualTo("ACCOUNT_INFO_FAILED");
        assertThat(failed.message()).doesNotContain(SECRET_TOKEN, "https://");
    }

    @Test
    void accountSchemasOnlyAcceptAccountIdAndTimeoutIsMapped() throws Exception {
        AccountRegistry registry = new AccountRegistry();
        registry.register("account-a", new InfoAdapter("adapter-a",
                () -> new CompletableFuture<>()));
        AgentTool status = new QqAccountStatusTool(registry);
        AgentTool login = new QqAccountLoginInfoTool(registry, Duration.ofMillis(20));

        List<String> statusProperties = new java.util.ArrayList<>();
        JSON.valueToTree(status.jsonSchema()).path("properties").fieldNames()
                .forEachRemaining(statusProperties::add);
        List<String> loginProperties = new java.util.ArrayList<>();
        JSON.valueToTree(login.jsonSchema()).path("properties").fieldNames()
                .forEachRemaining(loginProperties::add);
        assertThat(statusProperties).containsExactly("accountId");
        assertThat(loginProperties).containsExactly("accountId");

        ToolResult timeout = execute(login, Map.of());
        assertThat(timeout.success()).isFalse();
        assertThat(timeout.code()).isEqualTo("ACCOUNT_INFO_TIMEOUT");
    }

    private static ToolResult execute(AgentTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }

    private static class FakeAdapter implements MessagingAdapter {
        private final String adapterId;
        private final AdapterStatus status;

        FakeAdapter(String adapterId, AdapterStatus status) {
            this.adapterId = adapterId;
            this.status = status;
        }

        @Override public String adapterId() { return adapterId; }

        @Override public void start(InboundHandler handler) { }

        @Override public CompletionStage<SendReceipt> send(OutboundMessage message) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("not used"));
        }

        @Override public AdapterStatus status() { return status; }

        @Override public void addEventListener(AdapterEventListener listener) { }

        @Override public void removeEventListener(AdapterEventListener listener) { }

        @Override public void close() { }
    }

    private static final class InfoAdapter extends FakeAdapter
            implements AccountInfoProvider {
        private final Supplier<CompletionStage<AccountInfo>> info;

        InfoAdapter(String adapterId, Supplier<CompletionStage<AccountInfo>> info) {
            super(adapterId, new AdapterStatus(
                    AdapterStatus.ConnectionState.CONNECTED, T0, null, null));
            this.info = info;
        }

        @Override public CompletionStage<AccountInfo> accountInfo() {
            return info.get();
        }
    }
}
