package io.mosire.iibot.app.reply;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.core.RecallCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.ops.OpsLog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 按账号撤回一条消息。
 *
 * <p>D-031 授权 Agent 自行决定撤回目标，因此不要求 confirm；仍必须按账号路由、
 * 记录真实结果，并且不得把失败写成成功。</p>
 */
public final class BotMessageRecallTool implements AgentTool {

    public static final String NAME = "qq_message_recall";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 20L;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageId", Map.of("type", "string", "minLength", 1,
                            "description", "要撤回的 QQ messageId"),
                    "source", Map.of("type", "string",
                            "description", "可选来源地址，用于从 accountId 路由"),
                    "accountId", Map.of("type", "string",
                            "description", "可选账号；单账号可省略，多账号必须可确定")),
            "required", List.of("messageId"),
            "additionalProperties", false);

    private final Map<String, RecallCapability> providers;

    public BotMessageRecallTool(Map<String, RecallCapability> providers) {
        Objects.requireNonNull(providers, "providers");
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "撤回一条 QQ 消息。Agent 可自行决定目标，不需要 confirm；按 source/accountId "
                + "选择账号，失败或 UNKNOWN 如实返回，不自动重试。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageId = requiredText(context.arguments(), "messageId", 256);
            String accountId = resolveAccount(context.arguments());
            RecallCapability provider = providers.get(accountId);
            if (provider == null) {
                return ToolResult.error("RECALL_UNSUPPORTED",
                        "该账号不支持消息撤回");
            }
            SendReceipt receipt = provider.recall(messageId)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            OpsLog.info("event=message_recall accountId={} messageId={} state={} "
                            + "code={}", accountId, messageId, receipt.state(),
                    receipt.errorCode());
            if (receipt.state() != SendReceipt.State.SENT) {
                return ToolResult.error(
                        receipt.errorCode() == null ? "RECALL_FAILED" : receipt.errorCode(),
                        "撤回未成功；结果=" + receipt.state());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("messageId", messageId);
            data.put("recalled", true);
            data.put("state", receipt.state().name());
            return ToolResult.ok(JSON.writeValueAsString(data));
        } catch (RecallToolException failure) {
            return ToolResult.error(failure.code(), failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error("RECALL_UNKNOWN", "撤回请求被中断，结果未知");
        } catch (Exception failure) {
            return ToolResult.error("RECALL_UNAVAILABLE",
                    "撤回暂不可用（内部原因已脱敏）");
        }
    }

    private String resolveAccount(Map<String, Object> args) {
        String accountId = optionalText(args, "accountId");
        String source = optionalText(args, "source");
        if (!source.isEmpty()) {
            MessageSourceAddress address;
            try {
                address = MessageSourceAddress.parse(source);
            } catch (RuntimeException invalid) {
                throw new RecallToolException("INVALID_ARGUMENTS", "source 非法");
            }
            if (!address.isQqGroup() && !address.isQqPrivate()) {
                throw new RecallToolException("INVALID_ARGUMENTS",
                        "source 必须是 qq_group 或 qq_private");
            }
            String sourceAccount = address.accountId();
            if (sourceAccount == null || sourceAccount.isBlank()) {
                throw new RecallToolException("INVALID_ARGUMENTS",
                        "source 不能确定 QQ 账号");
            }
            if (!accountId.isEmpty() && !accountId.equals(sourceAccount)) {
                throw new RecallToolException("ACCOUNT_MISMATCH",
                        "source 与 accountId 不属于同一账号");
            }
            accountId = sourceAccount;
        }
        if (!accountId.isEmpty()) {
            if (!providers.containsKey(accountId)) {
                throw new RecallToolException("ACCOUNT_UNAVAILABLE",
                        "账号不可用: " + accountId);
            }
            return accountId;
        }
        List<String> available = providers.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        if (available.isEmpty()) {
            throw new RecallToolException("RECALL_UNSUPPORTED",
                    "当前没有账号支持消息撤回");
        }
        if (available.size() > 1) {
            throw new RecallToolException("ACCOUNT_REQUIRED",
                    "存在多个可用账号，必须指定 source 或 accountId");
        }
        return available.getFirst();
    }

    private static String requiredText(Map<String, Object> args, String name,
                                       int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new RecallToolException("INVALID_ARGUMENTS", name + " 不能为空");
        }
        String value = text.trim();
        if (value.length() > maxChars) {
            throw new RecallToolException("INVALID_ARGUMENTS", name + " 超过长度上限");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text)) {
            throw new RecallToolException("INVALID_ARGUMENTS", name + " 必须是字符串");
        }
        return text.trim();
    }

    private static final class RecallToolException extends RuntimeException {
        private final String code;

        private RecallToolException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
