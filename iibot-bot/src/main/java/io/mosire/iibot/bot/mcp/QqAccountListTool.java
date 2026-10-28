package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.AdapterStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code qq_account_list}：列出注册表内的账号与连接状态摘要。
 *
 * <p>只返回 accountId、adapterId、state 和 changedAt；不返回 token、URL、错误详情或
 * 底层协议对象。账号列表完全来自宿主注入的 {@link AccountRegistry}。</p>
 */
public final class QqAccountListTool implements AgentTool {

    public static final String NAME = "qq_account_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false);

    private final AccountRegistry accounts;

    public QqAccountListTool(AccountRegistry accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "列出当前已注册 QQ 账号及其连接状态摘要。只读；不返回 token、URL 或协议细节。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            List<Map<String, Object>> values = new ArrayList<>();
            for (AccountRegistry.RegisteredAccount account : accounts.snapshot()) {
                AdapterStatus status = null;
                try {
                    status = account.adapter().status();
                } catch (RuntimeException ignored) {
                    // 单账号状态查询异常不阻断列表；该账号以 UNKNOWN 呈现。
                }
                Map<String, Object> value = new LinkedHashMap<>(
                        BotAccountToolSupport.statusFields(account.accountId(),
                                account.adapter().adapterId(), status));
                values.add(value);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", values.size());
            data.put("accounts", values);
            return BotToolSupport.ok(NAME, data);
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("ACCOUNT_LIST_UNAVAILABLE",
                    "账号列表暂不可用（内部原因已脱敏）");
        }
    }
}
