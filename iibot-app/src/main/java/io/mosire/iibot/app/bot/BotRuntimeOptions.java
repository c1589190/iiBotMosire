package io.mosire.iibot.app.bot;

import io.mosire.iibot.app.inbox.BotInboxRuntimeOptions;
import io.mosire.iibot.bot.weight.WeightSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Bot-first runtime 的最小配置快照；不含玩家、推演、世界或回合语义。 */
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
        Duration outboxTimeout,
        String privateTempGroupId,
        Duration minSendInterval,
        WeightSettings weighting) {

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
        outboxTimeout = positive(outboxTimeout, "outboxTimeout");
        minSendInterval = Objects.requireNonNull(minSendInterval, "minSendInterval");
        if (minSendInterval.isNegative()) {
            throw new IllegalArgumentException("minSendInterval 不能为负");
        }
        privateTempGroupId = privateTempGroupId == null || privateTempGroupId.isBlank()
                ? null : privateTempGroupId.trim();
        weighting = weighting == null ? WeightSettings.defaults() : weighting;
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
}
