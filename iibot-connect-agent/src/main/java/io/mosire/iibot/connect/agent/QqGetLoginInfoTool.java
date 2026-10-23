package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.MessagingAdapter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code qq_get_login_info}：查询单个适配器当前 Bot 账号的公开信息。
 *
 * <p>该工具为 GUEST 级只读工具，要求调用者拥有 {@code qq:<adapterId>} 的读权限。
 * 只有实现了标准 {@link AccountInfoProvider} 的适配器才能提供信息；旧适配器会稳定返回
 * {@code UNSUPPORTED_ACCOUNT_INFO}，不会让工具层回退到协议特定类型判断。</p>
 *
 * <p>成功结果只包含 {@code adapterId}/{@code accountId}/{@code nickname}。超时、中断和底层异常
 * 均映射为稳定错误码，且不把异常消息、堆栈、token 或完整 URL 带入模型上下文。</p>
 */
final class QqGetLoginInfoTool extends AbstractQqTool {

    static final String NAME = "qq_get_login_info";
    static final String UNSUPPORTED_ACCOUNT_INFO = "UNSUPPORTED_ACCOUNT_INFO";
    static final String ACCOUNT_INFO_TIMEOUT = "ACCOUNT_INFO_TIMEOUT";
    static final String ACCOUNT_INFO_INTERRUPTED = "ACCOUNT_INFO_INTERRUPTED";
    static final String ACCOUNT_INFO_FAILED = "ACCOUNT_INFO_FAILED";

    private final AdapterRegistry adapters;
    private final Duration accountInfoTimeout;

    QqGetLoginInfoTool(AdapterRegistry adapters, Set<String> roles, Duration accountInfoTimeout) {
        super(ToolSpec.level(AccessToken.GUEST, false, false), roles);
        this.adapters = adapters;
        this.accountInfoTimeout = accountInfoTimeout;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询指定 QQ 适配器当前 Bot 账号的标识与昵称。GUEST 级只读操作，不返回凭据或协议细节。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "description", "按 adapterId 查询当前 Bot 账号公开信息。",
                "properties", Map.of(
                        "adapterId", Map.of(
                                "type", "string",
                                "description", "qq_list_adapters 返回的适配器 id")),
                "required", List.of("adapterId"),
                "additionalProperties", false);
    }

    @Override
    protected ToolResult executeChecked(ToolContext context) {
        String adapterId = ToolArguments.requiredText(context.arguments(), "adapterId");
        ToolArguments.requireSafePathIdentifier(adapterId, "adapterId");
        Optional<MessagingAdapter> found = adapters.find(adapterId);
        if (found.isEmpty()) {
            return ToolResult.error("ADAPTER_NOT_FOUND", "未找到适配器: " + Redaction.sanitize(adapterId));
        }
        MessagingAdapter adapter = found.get();
        context.resources().require(Operation.READ, QqResourcePaths.adapter(adapterId));

        if (!(adapter instanceof AccountInfoProvider provider)) {
            return ToolResult.error(
                    UNSUPPORTED_ACCOUNT_INFO,
                    "该适配器未实现标准账号信息查询能力");
        }
        return queryAndMap(adapter, provider);
    }

    private ToolResult queryAndMap(MessagingAdapter adapter, AccountInfoProvider provider) {
        final CompletionStage<AccountInfo> stage;
        try {
            stage = provider.accountInfo();
        } catch (RuntimeException exception) {
            return failed();
        }
        if (stage == null) {
            return failed();
        }

        final CompletableFuture<AccountInfo> future;
        try {
            future = stage.toCompletableFuture();
        } catch (RuntimeException exception) {
            return failed();
        }

        final AccountInfo info;
        try {
            info = future.get(accountInfoTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error(
                    ACCOUNT_INFO_INTERRUPTED,
                    "等待账号信息时线程被中断，查询结果未知");
        } catch (TimeoutException timeout) {
            return ToolResult.error(
                    ACCOUNT_INFO_TIMEOUT,
                    "等待账号信息查询超时");
        } catch (ExecutionException | CancellationException exception) {
            return failed();
        } catch (RuntimeException exception) {
            return failed();
        }

        if (info == null) {
            return failed();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("adapterId", Redaction.sanitize(adapter.adapterId()));
        root.put("accountId", Redaction.sanitize(info.accountId()));
        root.put("nickname", Redaction.sanitize(info.nickname()));
        return ToolResult.ok(Json.write(root));
    }

    private static ToolResult failed() {
        return ToolResult.error(
                ACCOUNT_INFO_FAILED,
                "获取账号信息失败（底层错误已脱敏）");
    }
}
