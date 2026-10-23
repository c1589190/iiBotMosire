package io.mosire.iibot.connect.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
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
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AdapterEventListener;
import io.mosire.iibot.connect.core.AdapterStatus.ConnectionState;
import io.mosire.iibot.connect.core.InboundHandler;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.SendReceipt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * QQ 连接扩展层契约测试：权限、资源闸、三态回执、参数健壮性与脱敏。
 */
class ConnectAgentToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADAPTER_ID = "fake";
    private static final String TARGET_ID = "10001";
    private static final String TEXT = "你好，世界";

    @Test
    void toolsHaveUniqueNamesAndCompleteSchemas() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        assertThat(toolSet.names()).hasSize(4).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(
                        ConnectAgentTools.LIST_ADAPTERS,
                        ConnectAgentTools.ADAPTER_STATUS,
                        ConnectAgentTools.GET_LOGIN_INFO,
                        ConnectAgentTools.SEND_MESSAGE);

        for (AgentTool tool : toolSet) {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.jsonSchema()).containsKeys("type", "properties", "required");
            assertThat(tool.jsonSchema().get("type")).isEqualTo("object");
            assertThat(tool.resources().policyFor("qq")).isEqualTo(ResourcePolicy.DENY);
        }

        AgentTool send = toolSet.find(ConnectAgentTools.SEND_MESSAGE).orElseThrow();
        assertThat(send.spec().requiredLevel()).isEqualTo(AccessToken.DEFAULT);
        assertThat(send.spec().sensitive()).isTrue();
        assertThat(send.spec().destructive()).isFalse();
        assertThat(send.roles()).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) send.jsonSchema().get("properties");
        assertThat(properties).containsKeys("adapterId", "targetType", "targetId", "text", "idempotencyKey");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) send.jsonSchema().get("required");
        assertThat(required).containsExactlyInAnyOrder("adapterId", "targetType", "targetId", "text");
        assertThat(properties.values()).allSatisfy(property -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> description = (Map<String, Object>) property;
            assertThat(description.get("description")).asString().isNotBlank();
        });

        AgentTool list = toolSet.find(ConnectAgentTools.LIST_ADAPTERS).orElseThrow();
        AgentTool status = toolSet.find(ConnectAgentTools.ADAPTER_STATUS).orElseThrow();
        AgentTool loginInfo = toolSet.find(ConnectAgentTools.GET_LOGIN_INFO).orElseThrow();
        assertThat(list.spec().requiredLevel()).isEqualTo(AccessToken.GUEST);
        assertThat(status.spec().requiredLevel()).isEqualTo(AccessToken.GUEST);
        assertThat(loginInfo.spec().requiredLevel()).isEqualTo(AccessToken.GUEST);
        assertThat(list.spec().sensitive()).isFalse();
        assertThat(status.spec().sensitive()).isFalse();
        assertThat(loginInfo.spec().sensitive()).isFalse();
        assertThat(loginInfo.spec().destructive()).isFalse();
        assertThat(loginInfo.roles()).isEmpty();
        assertThat(loginInfo.jsonSchema()).containsKeys("type", "properties", "required");
    }

    @Test
    void toolRegistryAndAuthorizerDispatchAllowedCall() throws Exception {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));
        ToolRegistry registry = new ToolRegistry();
        toolSet.registerInto(registry);
        assertThat(registry.size()).isEqualTo(4);

        ToolResult result = ToolCallAuthorizer.standard().execute(
                registry,
                ConnectAgentTools.SEND_MESSAGE,
                context(defaultPermissions(ResourceScope.unlimited()), sendArguments()));

        assertThat(result.success()).isTrue();
        JsonNode resultJson = JSON.readTree(result.message());
        assertThat(resultJson.path("state").asText()).isEqualTo("SENT");
        assertThat(resultJson.path("adapterMessageIds")).hasSize(1);
        assertThat(adapter.sent).hasSize(1);
        assertThat(adapter.sent.get(0).targetId()).isEqualTo(TARGET_ID);
        assertThat(adapter.sent.get(0).textSegments()).containsExactly(TEXT);
        assertThat(adapter.sent.get(0).idempotencyKey()).isNotBlank();
        assertThatCode(() -> UUID.fromString(adapter.sent.get(0).idempotencyKey())).doesNotThrowAnyException();
    }

    @Test
    void guestCannotSendAndDefaultWithoutSensitiveAllowanceIsDenied() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        AgentPermissionSet guest = AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.unlimited()))
                .build();
        ToolResult guestResult = executeSend(toolSet, guest);
        assertThat(guestResult.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(adapter.sent).isEmpty();

        AgentPermissionSet defaultWithoutSensitive = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(false).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.unlimited()))
                .build();
        ToolResult defaultResult = executeSend(toolSet, defaultWithoutSensitive);
        assertThat(defaultResult.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(adapter.sent).isEmpty();
    }

    @Test
    void noQqScopeOrExplicitNoneIsRejectedBeforeFakeAdapterSends() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        AgentPermissionSet noScope = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .build();
        ToolResult noScopeResult = executeSend(toolSet, noScope);
        assertThat(noScopeResult.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(adapter.sent).isEmpty();

        AgentPermissionSet noneScope = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.none()))
                .build();
        ToolResult noneScopeResult = executeSend(toolSet, noneScope);
        assertThat(noneScopeResult.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(adapter.sent).isEmpty();
    }

    @Test
    void explicitQqScopeAllowsAndOutOfScopeTargetIsRejected() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        AgentPermissionSet allowedTarget = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of(
                        "qq", ResourceScope.of(ADAPTER_ID + "/PRIVATE:" + TARGET_ID)))
                .build();
        ToolResult allowed = executeSend(toolSet, allowedTarget);
        assertThat(allowed.success()).isTrue();
        assertThat(adapter.sent).hasSize(1);

        FakeAdapter secondAdapter = new FakeAdapter("fake2");
        ConnectToolSet secondToolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(secondAdapter)));
        AgentPermissionSet outsideTarget = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of(
                        "qq", ResourceScope.of("fake2/PRIVATE:99999")))
                .build();
        Map<String, Object> secondArguments = sendArguments();
        secondArguments.put("adapterId", "fake2");
        ToolResult denied = executeSend(secondToolSet, outsideTarget, secondArguments);
        assertThat(denied.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(secondAdapter.sent).isEmpty();
    }

    @Test
    void roleMismatchIsDeniedAndFakeAdapterDoesNotSend() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.builder(new AdapterRegistry(List.of(adapter)))
                .sendMessageRoles("admin")
                .build();
        AgentPermissionSet userRole = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.unlimited()))
                .roles(RoleSet.of("user"))
                .build();

        ToolResult result = executeSend(toolSet, userRole);
        assertThat(result.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(adapter.sent).isEmpty();
    }

    @Test
    void getLoginInfoSuccessReturnsStructuredJsonThroughReadScope() throws Exception {
        ProviderFakeAdapter adapter = new ProviderFakeAdapter(ADAPTER_ID);
        adapter.accountInfoResponder =
                () -> CompletableFuture.completedFuture(new AccountInfo("123123", "mock-bot"));
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        ToolResult result = executeLoginInfo(
                toolSet, guestPermissions(ResourceScope.of(ADAPTER_ID)));

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.message());
        assertThat(json.path("adapterId").asText()).isEqualTo(ADAPTER_ID);
        assertThat(json.path("accountId").asText()).isEqualTo("123123");
        assertThat(json.path("nickname").asText()).isEqualTo("mock-bot");
        assertThat(json.size()).isEqualTo(3);
        assertThat(adapter.accountInfoCalls.get()).isEqualTo(1);
    }

    @Test
    void getLoginInfoMissingAdapterAndUnsupportedProviderHaveStableCodes() {
        FakeAdapter unsupported = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet unsupportedTools = ConnectAgentTools.create(
                new AdapterRegistry(List.of(unsupported)));
        ToolResult unsupportedResult = executeLoginInfo(
                unsupportedTools, guestPermissions(ResourceScope.of(ADAPTER_ID)));
        assertThat(unsupportedResult.code()).isEqualTo(QqGetLoginInfoTool.UNSUPPORTED_ACCOUNT_INFO);
        assertThat(unsupportedResult.message()).doesNotContain("Exception", "token", "http://", "https://");

        ToolResult missing = executeLoginInfo(
                unsupportedTools,
                guestPermissions(ResourceScope.of(ADAPTER_ID)),
                Map.of("adapterId", "missing"));
        assertThat(missing.code()).isEqualTo("ADAPTER_NOT_FOUND");
    }

    @Test
    void getLoginInfoTimeoutAndProviderExceptionsAreStableAndRedacted() {
        ProviderFakeAdapter timeoutAdapter = new ProviderFakeAdapter(ADAPTER_ID);
        CompletableFuture<AccountInfo> never = new CompletableFuture<>();
        timeoutAdapter.accountInfoResponder = () -> never;
        ConnectToolSet timeoutTools = ConnectAgentTools.builder(
                        new AdapterRegistry(List.of(timeoutAdapter)))
                .accountInfoTimeout(Duration.ofMillis(60))
                .build();

        long started = System.nanoTime();
        ToolResult timeout = executeLoginInfo(
                timeoutTools, guestPermissions(ResourceScope.of(ADAPTER_ID)));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(timeout.code()).isEqualTo(QqGetLoginInfoTool.ACCOUNT_INFO_TIMEOUT);
        assertThat(elapsedMillis).isLessThan(2_000L);

        ProviderFakeAdapter thrownAdapter = new ProviderFakeAdapter(ADAPTER_ID);
        thrownAdapter.accountInfoResponder = () -> {
            throw new IllegalStateException(
                    "Authorization: Bearer top-secret token=abc https://example.test/login");
        };
        ToolResult thrown = executeLoginInfo(
                ConnectAgentTools.create(new AdapterRegistry(List.of(thrownAdapter))),
                guestPermissions(ResourceScope.of(ADAPTER_ID)));
        assertThat(thrown.code()).isEqualTo(QqGetLoginInfoTool.ACCOUNT_INFO_FAILED);
        assertThat(thrown.message()).doesNotContain("top-secret", "token=abc", "https://", "Exception");

        ProviderFakeAdapter failedAdapter = new ProviderFakeAdapter(ADAPTER_ID);
        failedAdapter.accountInfoResponder = () -> CompletableFuture.failedFuture(
                new IllegalStateException("Authorization: Bearer top-secret https://example.test/login"));
        ToolResult failed = executeLoginInfo(
                ConnectAgentTools.create(new AdapterRegistry(List.of(failedAdapter))),
                guestPermissions(ResourceScope.of(ADAPTER_ID)));
        assertThat(failed.code()).isEqualTo(QqGetLoginInfoTool.ACCOUNT_INFO_FAILED);
        assertThat(failed.message()).doesNotContain("top-secret", "https://", "Exception");
    }

    @Test
    void getLoginInfoRequiresAdapterReadScopeAndConfiguredRole() {
        ProviderFakeAdapter adapter = new ProviderFakeAdapter(ADAPTER_ID);
        adapter.accountInfoResponder =
                () -> CompletableFuture.completedFuture(new AccountInfo("123123", "mock-bot"));
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        ToolResult noScope = executeLoginInfo(
                toolSet, guestPermissionsWithoutScope());
        assertThat(noScope.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(adapter.accountInfoCalls.get()).isZero();

        ToolResult outsideScope = executeLoginInfo(
                toolSet, guestPermissions(ResourceScope.of("other-adapter")));
        assertThat(outsideScope.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(adapter.accountInfoCalls.get()).isZero();

        ConnectToolSet roleRestricted = ConnectAgentTools.builder(
                        new AdapterRegistry(List.of(adapter)))
                .getLoginInfoRoles("admin")
                .build();
        AgentPermissionSet userRole = AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll()
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.unlimited()))
                .roles(RoleSet.of("user"))
                .build();
        ToolResult roleDenied = executeLoginInfo(roleRestricted, userRole);
        assertThat(roleDenied.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(adapter.accountInfoCalls.get()).isZero();
    }

    @Test
    void getLoginInfoSuccessRedactsSensitiveProviderText() throws Exception {
        ProviderFakeAdapter adapter = new ProviderFakeAdapter(ADAPTER_ID);
        adapter.accountInfoResponder = () -> CompletableFuture.completedFuture(
                new AccountInfo("123123", "token=top-secret https://example.test/profile"));
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        ToolResult result = executeLoginInfo(
                toolSet, guestPermissions(ResourceScope.of(ADAPTER_ID)));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).doesNotContain("top-secret", "https://example.test");
        assertThat(JSON.readTree(result.message()).path("nickname").asText())
                .doesNotContain("top-secret", "https://");
    }

    @Test
    void failedAndUnknownReceiptsMapToStableCodesAndRedactedMessages() throws Exception {
        FakeAdapter failedAdapter = new FakeAdapter(ADAPTER_ID);
        failedAdapter.responder = ignored -> CompletableFuture.completedFuture(
                SendReceipt.failed("AUTH_FAILED", "Authorization: Bearer super-secret token=abc https://example.test/api?token=abc"));
        ConnectToolSet failedTools = ConnectAgentTools.create(new AdapterRegistry(List.of(failedAdapter)));

        ToolResult failed = executeSend(failedTools, defaultPermissions(ResourceScope.unlimited()));
        assertThat(failed.code()).isEqualTo(QqSendMessageTool.SEND_FAILED);
        assertThat(failed.message()).contains("FAILED_BEFORE_SEND");
        assertThat(failed.message()).doesNotContain("super-secret", "abc", "https://example.test");
        assertThat(JSON.readTree(failed.message()).path("detail").asText())
                .doesNotContain("https://", "super-secret");

        FakeAdapter unknownAdapter = new FakeAdapter(ADAPTER_ID);
        unknownAdapter.responder = ignored -> CompletableFuture.completedFuture(
                SendReceipt.unknown("TIMEOUT", "wss://example.test/socket?access_token=top-secret"));
        ConnectToolSet unknownTools = ConnectAgentTools.create(new AdapterRegistry(List.of(unknownAdapter)));

        ToolResult unknown = executeSend(unknownTools, defaultPermissions(ResourceScope.unlimited()));
        assertThat(unknown.code()).isEqualTo(QqSendMessageTool.SEND_UNKNOWN);
        assertThat(unknown.message()).contains("UNKNOWN", "不应自动重发");
        assertThat(unknown.message()).doesNotContain("top-secret", "wss://example.test");
    }

    @Test
    void providedIdempotencyKeyIsPassedThroughAndOmittedKeyGeneratesUuid() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        Map<String, Object> arguments = sendArguments();
        arguments.put("idempotencyKey", "fixed-key-123");
        ToolResult provided = executeSend(toolSet, defaultPermissions(ResourceScope.unlimited()), arguments);
        assertThat(provided.success()).isTrue();
        assertThat(adapter.sent).hasSize(1);
        assertThat(adapter.sent.get(0).idempotencyKey()).isEqualTo("fixed-key-123");

        ToolResult generated = executeSend(toolSet, defaultPermissions(ResourceScope.unlimited()));
        assertThat(generated.success()).isTrue();
        assertThat(adapter.sent).hasSize(2);
        assertThat(adapter.sent.get(1).idempotencyKey()).isNotBlank().isNotEqualTo("fixed-key-123");
        assertThatCode(() -> UUID.fromString(adapter.sent.get(1).idempotencyKey()))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownAdapterAndInvalidArgumentsReturnStableCodesWithoutThrowing() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        Map<String, Object> unknownArguments = sendArguments();
        unknownArguments.put("adapterId", "missing");
        ToolResult unknownAdapter = executeSend(
                toolSet, defaultPermissions(ResourceScope.unlimited()), unknownArguments);
        assertThat(unknownAdapter.code()).isEqualTo("ADAPTER_NOT_FOUND");
        assertThat(adapter.sent).isEmpty();

        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("adapterId", ADAPTER_ID, "targetType", "CHANNEL", "targetId", TARGET_ID, "text", TEXT),
                Map.of("adapterId", ADAPTER_ID, "targetType", "PRIVATE", "targetId", "../escape", "text", TEXT),
                Map.of("adapterId", ADAPTER_ID, "targetType", "PRIVATE", "targetId", TARGET_ID, "text", "  "),
                Map.of("adapterId", ADAPTER_ID, "targetType", "PRIVATE", "targetId", TARGET_ID,
                        "text", TEXT, "idempotencyKey", " "))) {
            assertThatCode(() -> {
                ToolResult result = executeSend(
                        toolSet, defaultPermissions(ResourceScope.unlimited()), invalid);
                assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
            }).doesNotThrowAnyException();
        }
        assertThat(adapter.sent).isEmpty();
    }

    @Test
    void listAndStatusAreReadableButDoNotLeakSecretsOrUrls() throws Exception {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        adapter.status.set(new io.mosire.iibot.connect.core.AdapterStatus(
                ConnectionState.CONNECTED,
                Instant.parse("2026-10-24T00:00:00Z"),
                "AUTH_FAILED",
                "Authorization: Bearer super-secret token=abc https://example.test/api?token=abc"));
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll()
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.unlimited()))
                .build();
        ToolRegistry registry = new ToolRegistry();
        toolSet.registerInto(registry);
        ToolCallAuthorizer authorizer = ToolCallAuthorizer.standard();
        ToolContext context = context(permissions, Map.of());

        ToolResult list = authorizer.execute(registry, ConnectAgentTools.LIST_ADAPTERS, context);
        assertThat(list.success()).isTrue();
        assertThat(list.message()).contains(ADAPTER_ID, "CONNECTED");
        assertThat(list.message()).doesNotContain("super-secret", "token=abc", "https://");

        ToolResult status = authorizer.execute(
                registry, ConnectAgentTools.ADAPTER_STATUS, context(permissions, Map.of("adapterId", ADAPTER_ID)));
        assertThat(status.success()).isTrue();
        assertThat(status.message()).contains(ADAPTER_ID, "CONNECTED");
        assertThat(status.message()).doesNotContain("super-secret", "token=abc", "https://");
        assertThat(JSON.readTree(status.message()).path("lastErrorMessage").asText())
                .contains("[URL已脱敏]");
    }

    @Test
    void listFiltersAdaptersToCallerReachableScope() throws Exception {
        FakeAdapter visible = new FakeAdapter(ADAPTER_ID);
        FakeAdapter hidden = new FakeAdapter("hidden");
        hidden.status.set(new io.mosire.iibot.connect.core.AdapterStatus(
                ConnectionState.CONNECTED,
                Instant.parse("2026-10-24T00:00:00Z"),
                "HIDDEN_ERROR",
                "token=hidden-secret https://hidden.test/private"));
        ConnectToolSet toolSet = ConnectAgentTools.create(
                new AdapterRegistry(List.of(visible, hidden)));
        AgentPermissionSet adapterScope = AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll()
                .resourceScopes(ResourceScopeMap.of("qq", ResourceScope.of(ADAPTER_ID)))
                .build();
        ToolRegistry registry = new ToolRegistry();
        toolSet.registerInto(registry);

        ToolResult result = ToolCallAuthorizer.standard().execute(
                registry, ConnectAgentTools.LIST_ADAPTERS, context(adapterScope, Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains(ADAPTER_ID).doesNotContain("hidden", "hidden-secret", "https://");
        assertThat(JSON.readTree(result.message()).path("count").asInt()).isEqualTo(1);
    }

    @Test
    void receiptWaitIsBoundedAndInterruptFlagIsRestored() throws Exception {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        CompletableFuture<SendReceipt> never = new CompletableFuture<>();
        adapter.responder = ignored -> never;
        ConnectToolSet toolSet = ConnectAgentTools.builder(new AdapterRegistry(List.of(adapter)))
                .sendTimeout(Duration.ofMillis(60))
                .build();

        long started = System.nanoTime();
        ToolResult timeout = executeSend(toolSet, defaultPermissions(ResourceScope.unlimited()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(timeout.code()).isEqualTo(QqSendMessageTool.SEND_UNKNOWN);
        assertThat(timeout.message()).contains("UNKNOWN", "不应自动重发");
        assertThat(elapsedMillis).isLessThan(2_000L);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> interruptedFlag = executor.submit(() -> {
                Thread.currentThread().interrupt();
                ToolResult result = executeSend(toolSet, defaultPermissions(ResourceScope.unlimited()));
                assertThat(result.code()).isEqualTo(QqSendMessageTool.SEND_UNKNOWN);
                return Thread.currentThread().isInterrupted();
            });
            assertThat(interruptedFlag.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void adapterThrownExceptionDoesNotLeakToCaller() {
        FakeAdapter adapter = new FakeAdapter(ADAPTER_ID);
        adapter.responder = ignored -> {
            throw new IllegalStateException("Authorization: Bearer top-secret https://example.test/send");
        };
        ConnectToolSet toolSet = ConnectAgentTools.create(new AdapterRegistry(List.of(adapter)));

        ToolResult result = executeSend(toolSet, defaultPermissions(ResourceScope.unlimited()));
        assertThat(result.code()).isEqualTo(QqSendMessageTool.SEND_UNKNOWN);
        assertThat(result.message()).doesNotContain("top-secret", "https://example.test");
        assertThat(result.message()).contains("UNKNOWN", "不应自动重发");
    }

    private static ToolResult executeLoginInfo(
            ConnectToolSet toolSet, AgentPermissionSet permissions) {
        return executeLoginInfo(toolSet, permissions, Map.of("adapterId", ADAPTER_ID));
    }

    private static ToolResult executeLoginInfo(
            ConnectToolSet toolSet,
            AgentPermissionSet permissions,
            Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        toolSet.registerInto(registry);
        return ToolCallAuthorizer.standard().execute(
                registry, ConnectAgentTools.GET_LOGIN_INFO, context(permissions, arguments));
    }

    private static AgentPermissionSet guestPermissions(ResourceScope scope) {
        return AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll()
                .resourceScopes(ResourceScopeMap.of("qq", scope))
                .build();
    }

    private static AgentPermissionSet guestPermissionsWithoutScope() {
        return AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll()
                .build();
    }

    private static ToolResult executeSend(ConnectToolSet toolSet, AgentPermissionSet permissions) {
        return executeSend(toolSet, permissions, sendArguments());
    }

    private static ToolResult executeSend(
            ConnectToolSet toolSet, AgentPermissionSet permissions, Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        toolSet.registerInto(registry);
        return ToolCallAuthorizer.standard().execute(
                registry,
                ConnectAgentTools.SEND_MESSAGE,
                context(permissions, arguments));
    }

    private static ToolContext context(AgentPermissionSet permissions, Map<String, Object> arguments) {
        return new ToolContext(permissions.grantedToken(), permissions, Map.of(), arguments);
    }

    private static Map<String, Object> sendArguments() {
        return new java.util.LinkedHashMap<>(Map.of(
                "adapterId", ADAPTER_ID,
                "targetType", "PRIVATE",
                "targetId", TARGET_ID,
                "text", TEXT));
    }

    private static AgentPermissionSet defaultPermissions(ResourceScope scope) {
        return AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll().sensitiveAllowed(true).destructiveAllowed(true)
                .resourceScopes(ResourceScopeMap.of("qq", scope))
                .build();
    }

    /** 测试用标准 SPI 适配器；不发网络，只捕获 OutboundMessage 并按配置回执。 */
    private static class FakeAdapter implements MessagingAdapter {

        private final String adapterId;
        private final AtomicReference<io.mosire.iibot.connect.core.AdapterStatus> status =
                new AtomicReference<>(io.mosire.iibot.connect.core.AdapterStatus.stopped());
        private final CopyOnWriteArrayList<OutboundMessage> sent = new CopyOnWriteArrayList<>();
        private volatile Function<OutboundMessage, CompletionStage<SendReceipt>> responder =
                ignored -> CompletableFuture.completedFuture(SendReceipt.sent(List.of("message-1")));

        private FakeAdapter(String adapterId) {
            this.adapterId = adapterId;
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public void start(InboundHandler handler) {
            // 测试适配器不接收真实入站消息。
        }

        @Override
        public CompletionStage<SendReceipt> send(OutboundMessage message) {
            sent.add(message);
            return responder.apply(message);
        }

        @Override
        public io.mosire.iibot.connect.core.AdapterStatus status() {
            return status.get();
        }

        @Override
        public void addEventListener(AdapterEventListener listener) {
            // 测试不需要事件监听。
        }

        @Override
        public void removeEventListener(AdapterEventListener listener) {
            // 测试不需要事件监听。
        }

        @Override
        public void close() {
            // 无资源可关闭。
        }
    }

    /** 测试用标准账号信息能力适配器。 */
    private static final class ProviderFakeAdapter extends FakeAdapter implements AccountInfoProvider {

        private final AtomicInteger accountInfoCalls = new AtomicInteger();
        private volatile Supplier<CompletionStage<AccountInfo>> accountInfoResponder =
                () -> CompletableFuture.completedFuture(new AccountInfo("123456", "fake-bot"));

        private ProviderFakeAdapter(String adapterId) {
            super(adapterId);
        }

        @Override
        public CompletionStage<AccountInfo> accountInfo() {
            accountInfoCalls.incrementAndGet();
            return accountInfoResponder.get();
        }
    }

}
