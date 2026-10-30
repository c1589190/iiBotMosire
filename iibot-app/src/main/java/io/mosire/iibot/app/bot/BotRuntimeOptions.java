package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.inbox.BotInboxRuntimeOptions;
import io.mosire.iibot.bot.weight.WeightSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bot runtime 的最小配置快照；不含玩家、推演、世界或回合语义。
 *
 * <p>{@code accountId}/{@code privateTempGroupId} 保留为单账号兼容入口；多账号运行时使用
 * {@link #accounts()} 中每个账号的稳定 alias/accountId/privateTempGroupId。所有账号共享
 * dataDir、MCP、Inbox 和发送超时/最小间隔，但各自拥有独立 Adapter 与 OutboxSender。</p>
 */
public record BotRuntimeOptions(
        Path dataDir,
        String accountId,
        String controlHost,
        int controlPort,
        String controlPath,
        String controlToken,
        BotInboxRuntimeOptions inbox,
        long digestDebounceMillis,
        int maxMessagesPerDigest,
        int inlineMaxChars,
        Duration outboxTimeout,
        String privateTempGroupId,
        Duration minSendInterval,
        WeightSettings weighting,
        List<AccountOptions> accounts) {

    public BotRuntimeOptions {
        dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        accountId = requireText(accountId, "accountId");
        controlHost = requireText(controlHost, "controlHost");
        if (controlPort < 0 || controlPort > 65535) {
            throw new IllegalArgumentException("controlPort 必须在 0..65535");
        }
        controlPath = requireText(controlPath, "controlPath");
        if (!controlPath.startsWith("/")) {
            throw new IllegalArgumentException("controlPath 必须以 / 开头");
        }
        controlToken = requireText(controlToken, "controlToken");
        inbox = Objects.requireNonNull(inbox, "inbox");
        if (digestDebounceMillis < 0) {
            throw new IllegalArgumentException("digestDebounceMillis 不能为负");
        }
        if (maxMessagesPerDigest < 1 || maxMessagesPerDigest > 1000) {
            throw new IllegalArgumentException("maxMessagesPerDigest 必须在 1..1000");
        }
        if (inlineMaxChars < 0) {
            throw new IllegalArgumentException("inlineMaxChars 不能为负");
        }
        outboxTimeout = positive(outboxTimeout, "outboxTimeout");
        minSendInterval = Objects.requireNonNull(minSendInterval, "minSendInterval");
        if (minSendInterval.isNegative()) {
            throw new IllegalArgumentException("minSendInterval 不能为负");
        }
        privateTempGroupId = normalizeOptional(privateTempGroupId);
        weighting = weighting == null ? WeightSettings.defaults() : weighting;
        if (accounts == null || accounts.isEmpty()) {
            accounts = List.of(new AccountOptions(accountId, accountId, privateTempGroupId));
        } else {
            accounts = List.copyOf(accounts);
        }
        Set<String> aliases = new LinkedHashSet<>();
        Set<String> accountIds = new LinkedHashSet<>();
        for (AccountOptions account : accounts) {
            if (!aliases.add(account.alias())) {
                throw new IllegalArgumentException("accounts alias 重复: " + account.alias());
            }
            if (!accountIds.add(account.accountId())) {
                throw new IllegalArgumentException(
                        "accounts accountId 重复: " + account.accountId());
            }
        }
        if (!accountIds.contains(accountId)) {
            throw new IllegalArgumentException(
                    "accountId 必须是 accounts 中一个已配置账号: " + accountId);
        }
    }

    /** 兼容未显式传递 inlineMaxChars 的调用；默认与配置默认值一致。 */
    public BotRuntimeOptions(
            Path dataDir, String accountId,
            String controlHost, int controlPort, String controlPath, String controlToken,
            BotInboxRuntimeOptions inbox, long digestDebounceMillis,
            int maxMessagesPerDigest, Duration outboxTimeout,
            String privateTempGroupId, Duration minSendInterval,
            WeightSettings weighting, List<AccountOptions> accounts) {
        this(dataDir, accountId, controlHost, controlPort, controlPath, controlToken,
                inbox, digestDebounceMillis, maxMessagesPerDigest, 512,
                outboxTimeout, privateTempGroupId, minSendInterval, weighting, accounts);
    }

    /**
     * 单账号兼容构造器：同时生成唯一的 {@link AccountOptions}。
     */
    public BotRuntimeOptions(
            Path dataDir, String accountId,
            String controlHost, int controlPort, String controlPath, String controlToken,
            BotInboxRuntimeOptions inbox, long digestDebounceMillis,
            int maxMessagesPerDigest, Duration outboxTimeout,
            String privateTempGroupId, Duration minSendInterval,
            WeightSettings weighting) {
        this(dataDir, accountId, controlHost, controlPort, controlPath, controlToken,
                inbox, digestDebounceMillis, maxMessagesPerDigest, 512,
                outboxTimeout, privateTempGroupId, minSendInterval, weighting,
                List.of(new AccountOptions(accountId, accountId, privateTempGroupId)));
    }

    /** 兼容旧调用：无权重配置和自定义 Inbox 网络参数时使用默认值。 */
    public BotRuntimeOptions(
            Path dataDir, Path inboxDataDir, String accountId,
            String controlHost, int controlPort, String controlPath, String controlToken,
            String inboxId, String bindingLabel, Set<String> inboxChannels,
            long inboxBindingTtlHours, long digestDebounceMillis,
            int maxMessagesPerDigest, Duration outboxTimeout,
            String privateTempGroupId, Duration minSendInterval) {
        this(dataDir, accountId, controlHost, controlPort, controlPath, controlToken,
                BotInboxRuntimeOptions.defaults(inboxDataDir, inboxId, bindingLabel,
                        inboxChannels, inboxBindingTtlHours),
                digestDebounceMillis, maxMessagesPerDigest, outboxTimeout,
                privateTempGroupId, minSendInterval, WeightSettings.defaults());
    }

    /** 兼容带权重、但未传 Inbox 网络参数的旧调用。 */
    public BotRuntimeOptions(
            Path dataDir, Path inboxDataDir, String accountId,
            String controlHost, int controlPort, String controlPath, String controlToken,
            String inboxId, String bindingLabel, Set<String> inboxChannels,
            long inboxBindingTtlHours, long digestDebounceMillis,
            int maxMessagesPerDigest, Duration outboxTimeout,
            String privateTempGroupId, Duration minSendInterval,
            WeightSettings weighting) {
        this(dataDir, accountId, controlHost, controlPort, controlPath, controlToken,
                BotInboxRuntimeOptions.defaults(inboxDataDir, inboxId, bindingLabel,
                        inboxChannels, inboxBindingTtlHours),
                digestDebounceMillis, maxMessagesPerDigest, outboxTimeout,
                privateTempGroupId, minSendInterval, weighting);
    }

    /** 按稳定 accountId 查找该账号的 runtime 配置。 */
    public Optional<AccountOptions> accountOptions(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        String normalized = accountId.trim();
        return accounts.stream()
                .filter(account -> account.accountId().equals(normalized))
                .findFirst();
    }

    /** 一个账号的稳定运行时配置。 */
    public record AccountOptions(String alias, String accountId, String privateTempGroupId) {
        public AccountOptions {
            alias = requireText(alias, "alias");
            accountId = requireText(accountId, "accountId");
            privateTempGroupId = normalizeOptional(privateTempGroupId);
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
