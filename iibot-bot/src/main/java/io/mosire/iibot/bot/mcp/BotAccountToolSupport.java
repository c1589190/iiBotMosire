package io.mosire.iibot.bot.mcp;

import io.mosire.iibot.connect.core.AccountRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 账号工具的账号选择与输出脱敏公共层。 */
final class BotAccountToolSupport {

    static final String ACCOUNT_REQUIRED = "ACCOUNT_REQUIRED";
    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";
    static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    static final String UNSUPPORTED_ACCOUNT_INFO = "UNSUPPORTED_ACCOUNT_INFO";
    static final String UNSUPPORTED_RECENT_QQ = "UNSUPPORTED_RECENT_QQ";
    static final String UNSUPPORTED_CHAT_HISTORY = "UNSUPPORTED_CHAT_HISTORY";
    static final String UNSUPPORTED_FRIEND_REQUEST = "UNSUPPORTED_FRIEND_REQUEST";
    static final String ACCOUNT_INFO_TIMEOUT = "ACCOUNT_INFO_TIMEOUT";
    static final String ACCOUNT_INFO_INTERRUPTED = "ACCOUNT_INFO_INTERRUPTED";
    static final String ACCOUNT_INFO_FAILED = "ACCOUNT_INFO_FAILED";

    private static final int MAX_TEXT = 512;
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?|wss?)://[^\\s\"']+");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\b(authorization)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(access[_-]?token|token|api[_-]?key|apikey|password|passwd|secret|credential)"
                    + "\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\t]+");

    private BotAccountToolSupport() {
    }

    static AccountRegistry.RegisteredAccount selectAccount(
            AccountRegistry registry, Map<String, Object> arguments) {
        Objects.requireNonNull(registry, "registry");
        List<AccountRegistry.RegisteredAccount> accounts = registry.snapshot();
        if (accounts.isEmpty()) {
            throw new AccountToolException(ACCOUNT_NOT_FOUND, "没有已注册账号");
        }
        Object raw = arguments == null ? null : arguments.get("accountId");
        if (raw == null) {
            if (accounts.size() == 1) {
                return accounts.getFirst();
            }
            throw new AccountToolException(ACCOUNT_REQUIRED,
                    "存在多个账号，必须指定 accountId");
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new AccountToolException(INVALID_ARGUMENTS,
                    "参数 accountId 必须是非空白字符串");
        }
        String accountId = text.trim();
        return accounts.stream()
                .filter(account -> account.accountId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new AccountToolException(ACCOUNT_NOT_FOUND,
                        "未找到 accountId: " + sanitize(accountId)));
    }

    static Map<String, Object> statusFields(String accountId, String adapterId,
                                            io.mosire.iibot.connect.core.AdapterStatus status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("accountId", sanitize(accountId));
        value.put("adapterId", sanitize(adapterId));
        value.put("state", status == null ? "UNKNOWN" : status.state().name());
        value.put("changedAt", status == null ? null : status.changedAt().toString());
        return value;
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String result = URL.matcher(value).replaceAll("[URL已脱敏]");
        result = AUTHORIZATION.matcher(result).replaceAll("$1=***");
        result = BEARER.matcher(result).replaceAll("$1 ***");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=***");
        result = LINE_BREAKS.matcher(result).replaceAll(" ").trim();
        return result.length() <= MAX_TEXT
                ? result : result.substring(0, MAX_TEXT) + "…";
    }

    static String sanitizeCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = sanitize(value).replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.length() <= 96 ? sanitized : sanitized.substring(0, 96);
    }

    /** 账号工具的可映射错误。 */
    static final class AccountToolException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        AccountToolException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
