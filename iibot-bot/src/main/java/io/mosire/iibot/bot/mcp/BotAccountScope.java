package io.mosire.iibot.bot.mcp;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 逐账号工具的账号集合与选择规则。
 *
 * <p>规则对用户与模型保持一致：</p>
 * <ul>
 *   <li>只有一个可用账号且未指定 {@code accountId} → 默认该账号；</li>
 *   <li>多个可用账号且未指定 → 稳定失败码 {@code ACCOUNT_REQUIRED}；</li>
 *   <li>指定了未知账号 → {@code ACCOUNT_UNAVAILABLE}；</li>
 *   <li>账号存在但该账号不具备本次能力 → {@code UNSUPPORTED_*}。</li>
 * </ul>
 *
 * <p>账号集合只来自宿主装配（AccountRegistry/AdapterBinding），不接受模型传 adapter、
 * URL 或凭据。</p>
 */
final class BotAccountScope {

    private static final String ACCOUNT_ID = "accountId";

    private final List<String> knownAccountIds;

    private BotAccountScope(List<String> knownAccountIds) {
        this.knownAccountIds = List.copyOf(knownAccountIds);
    }

    /** 由宿主已知账号集合创建；空白项被忽略，按字典序稳定排序。 */
    static BotAccountScope of(Collection<String> accountIds) {
        TreeSet<String> normalized = new TreeSet<>();
        if (accountIds != null) {
            for (String accountId : accountIds) {
                if (accountId != null && !accountId.isBlank()) {
                    normalized.add(accountId.trim());
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("账号集合不能为空");
        }
        return new BotAccountScope(List.copyOf(normalized));
    }

    /** 单账号便捷构造：仅该账号可用。 */
    static BotAccountScope single(String accountId) {
        return of(List.of(Objects.requireNonNull(accountId, "accountId")));
    }

    List<String> knownAccountIds() {
        return knownAccountIds;
    }

    /**
     * 按参数选择目标账号。
     *
     * @param args 工具原始参数；只读取 {@code accountId}
     * @return 选中的稳定 accountId
     * @throws BotAccountToolSupport.AccountToolException 缺少选择或账号不可用
     */
    String resolve(Map<String, Object> args) {
        Object raw = args == null ? null : args.get(ACCOUNT_ID);
        if (raw == null) {
            if (knownAccountIds.size() == 1) {
                return knownAccountIds.getFirst();
            }
            throw new BotAccountToolSupport.AccountToolException(
                    BotAccountToolSupport.ACCOUNT_REQUIRED,
                    "存在多个账号，必须指定 accountId；可用账号: " + knownAccountIds);
        }
        String accountId = requireText(raw);
        if (!knownAccountIds.contains(accountId)) {
            throw new BotAccountToolSupport.AccountToolException(
                    BotAccountToolSupport.ACCOUNT_UNAVAILABLE,
                    "账号不可用: " + BotAccountToolSupport.sanitize(accountId));
        }
        return accountId;
    }

    /**
     * 按 {@code accountId -> 能力实例} 映射选择账号；null value 表示账号已注册但不支持该能力。
     *
     * <p>省略 {@code accountId} 时只在恰好一个账号真正具备该能力时默认成功；两个及以上可用
     * provider 必须显式选择，避免跨账号串号。</p>
     *
     * @param providers       账号到能力的映射，可为 null/空
     * @param args            工具原始参数
     * @param unsupportedCode 账号存在但能力缺失时的稳定错误码
     * @return 选中的 accountId，调用方可用它从同一映射取 provider
     */
    static String resolveCapability(Map<String, ?> providers,
                                    Map<String, Object> args,
                                    String unsupportedCode) {
        if (providers == null || providers.isEmpty()) {
            throw new BotAccountToolSupport.AccountToolException(
                    BotAccountToolSupport.ACCOUNT_UNAVAILABLE, "没有可用的账号能力");
        }
        Object raw = args == null ? null : args.get(ACCOUNT_ID);
        if (raw == null) {
            List<String> available = providers.entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .map(Map.Entry::getKey)
                    .toList();
            if (available.size() == 1) {
                return available.getFirst();
            }
            if (available.isEmpty()) {
                throw new BotAccountToolSupport.AccountToolException(unsupportedCode,
                        "当前没有账号支持此能力");
            }
            throw new BotAccountToolSupport.AccountToolException(
                    BotAccountToolSupport.ACCOUNT_REQUIRED,
                    "存在多个可用账号，必须指定 accountId；可选账号: " + available);
        }
        String accountId = requireText(raw);
        if (!providers.containsKey(accountId)) {
            throw new BotAccountToolSupport.AccountToolException(
                    BotAccountToolSupport.ACCOUNT_UNAVAILABLE,
                    "账号不可用: " + BotAccountToolSupport.sanitize(accountId));
        }
        if (providers.get(accountId) == null) {
            throw new BotAccountToolSupport.AccountToolException(unsupportedCode,
                    "账号 " + BotAccountToolSupport.sanitize(accountId) + " 不支持此能力");
        }
        return accountId;
    }

    private static String requireText(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new BotAccountToolSupport.AccountToolException(
                    BotAccountToolSupport.INVALID_ARGUMENTS,
                    "参数 accountId 必须是非空白字符串");
        }
        return text.trim();
    }
}
