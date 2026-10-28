package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AccountRegistry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code qq_account_login_info}：查询账号适配器当前登录的公开信息。
 *
 * <p>单账号时 accountId 可省略；多账号且省略时返回 {@code ACCOUNT_REQUIRED}。仅当
 * adapter 实现 {@link AccountInfoProvider} 时查询；失败只返回稳定错误码和脱敏说明。</p>
 */
public final class QqAccountLoginInfoTool implements AgentTool {

    public static final String NAME = "qq_account_login_info";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string", "minLength", 1)),
            "additionalProperties", false);

    private final AccountRegistry accounts;
    private final Duration timeout;

    public QqAccountLoginInfoTool(AccountRegistry accounts) {
        this(accounts, DEFAULT_TIMEOUT);
    }

    public QqAccountLoginInfoTool(AccountRegistry accounts, Duration timeout) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正");
        }
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "查询指定 QQ 账号当前登录的公开标识与昵称。仅支持标准账号信息能力；不返回凭据或 URL。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            AccountRegistry.RegisteredAccount account =
                    BotAccountToolSupport.selectAccount(accounts, context.arguments());
            if (!(account.adapter() instanceof AccountInfoProvider provider)) {
                return BotToolSupport.failure(BotAccountToolSupport.UNSUPPORTED_ACCOUNT_INFO,
                        "该账号适配器未实现标准账号信息查询能力");
            }
            return query(account, provider);
        } catch (BotAccountToolSupport.AccountToolException failure) {
            return BotToolSupport.failure(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure(BotAccountToolSupport.ACCOUNT_INFO_FAILED,
                    "获取账号信息失败（底层错误已脱敏）");
        }
    }

    private ToolResult query(AccountRegistry.RegisteredAccount account,
                             AccountInfoProvider provider) {
        final CompletionStage<AccountInfo> stage;
        try {
            stage = provider.accountInfo();
        } catch (RuntimeException failure) {
            return failed();
        }
        if (stage == null) {
            return failed();
        }
        final CompletableFuture<AccountInfo> future;
        try {
            future = stage.toCompletableFuture();
        } catch (RuntimeException failure) {
            return failed();
        }
        final AccountInfo info;
        try {
            info = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return BotToolSupport.failure(BotAccountToolSupport.ACCOUNT_INFO_INTERRUPTED,
                    "等待账号信息时线程被中断，查询结果未知");
        } catch (TimeoutException timeoutFailure) {
            return BotToolSupport.failure(BotAccountToolSupport.ACCOUNT_INFO_TIMEOUT,
                    "等待账号信息查询超时");
        } catch (ExecutionException | CancellationException failure) {
            return failed();
        } catch (RuntimeException failure) {
            return failed();
        }
        if (info == null) {
            return failed();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", BotAccountToolSupport.sanitize(account.accountId()));
        data.put("adapterId", BotAccountToolSupport.sanitize(account.adapter().adapterId()));
        data.put("loginAccountId", BotAccountToolSupport.sanitize(info.accountId()));
        data.put("nickname", BotAccountToolSupport.sanitize(info.nickname()));
        return BotToolSupport.ok(NAME, data);
    }

    private static ToolResult failed() {
        return BotToolSupport.failure(BotAccountToolSupport.ACCOUNT_INFO_FAILED,
                "获取账号信息失败（底层错误已脱敏）");
    }
}
