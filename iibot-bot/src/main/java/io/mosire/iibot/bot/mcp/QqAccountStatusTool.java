package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.AdapterStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code qq_account_status}：查询单个账号的连接状态与脱敏错误摘要。
 *
 * <p>单账号时 accountId 可省略；多账号且省略时返回稳定 {@code ACCOUNT_REQUIRED}。
 * 只按 accountId 从 {@link AccountRegistry} 查 adapter，不接受任意 adapter 或 URL。</p>
 */
public final class QqAccountStatusTool implements AgentTool {

    public static final String NAME = "qq_account_status";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string", "minLength", 1)),
            "additionalProperties", false);

    private final AccountRegistry accounts;

    public QqAccountStatusTool(AccountRegistry accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "查询指定 QQ 账号的连接状态、变更时间与脱敏后的最近错误。单账号时可省略 accountId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            AccountRegistry.RegisteredAccount account =
                    BotAccountToolSupport.selectAccount(accounts, context.arguments());
            AdapterStatus status;
            try {
                status = account.adapter().status();
            } catch (RuntimeException failure) {
                return BotToolSupport.failure("ACCOUNT_STATUS_UNAVAILABLE",
                        "账号状态暂不可用（内部原因已脱敏）");
            }
            Map<String, Object> data = new LinkedHashMap<>(
                    BotAccountToolSupport.statusFields(account.accountId(),
                            account.adapter().adapterId(), status));
            data.put("lastErrorCode", status == null
                    ? null : BotAccountToolSupport.sanitizeCode(status.lastErrorCode()));
            data.put("lastErrorMessage", status == null
                    ? null : BotAccountToolSupport.sanitize(status.lastErrorMessage()));
            return BotToolSupport.ok(NAME, data);
        } catch (BotAccountToolSupport.AccountToolException failure) {
            return BotToolSupport.failure(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("ACCOUNT_STATUS_UNAVAILABLE",
                    "账号状态暂不可用（内部原因已脱敏）");
        }
    }
}
