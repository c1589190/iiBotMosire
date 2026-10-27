package io.mosire.iibot.app.bot;

import io.mosire.inbox.server.ServerConfig;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bot-first 的严格配置快照；不包含玩家、推演、世界、回合或 LLM 路由。 */
public record BotConfig(
        Path dataDir,
        String accountId,
        NapCat napcat,
        Control control,
        Inbox inbox,
        Summary summary,
        Outbox outbox,
        Weighting weighting) {

    public BotConfig {
        dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        accountId = requireText(accountId, "accountId");
        napcat = Objects.requireNonNull(napcat, "napcat");
        control = Objects.requireNonNull(control, "control");
        inbox = Objects.requireNonNull(inbox, "inbox");
        summary = summary == null ? Summary.defaults() : summary;
        outbox = outbox == null ? Outbox.defaults() : outbox;
        weighting = weighting == null ? Weighting.defaults() : weighting;
    }

    public record NapCat(
            URI eventWebSocketUri,
            URI httpBaseUri,
            Optional<String> accessTokenEnv,
            Optional<String> accountId,
            Optional<String> privateTempGroupId,
            boolean suppressSelfMessages,
            int requestTimeoutSeconds,
            int maxTextLength) {

        public NapCat {
            eventWebSocketUri = requireUri(eventWebSocketUri, "napcat.eventWebSocketUri");
            httpBaseUri = requireUri(httpBaseUri, "napcat.httpBaseUri");
            accessTokenEnv = normalizeOptional(accessTokenEnv);
            accountId = normalizeOptional(accountId);
            privateTempGroupId = normalizeOptional(privateTempGroupId);
            if (requestTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("napcat.requestTimeoutSeconds 必须为正");
            }
            if (maxTextLength <= 0) {
                throw new IllegalArgumentException("napcat.maxTextLength 必须为正");
            }
        }
    }

    public record Control(String host, int port, String path, String bearerTokenEnv) {
        public Control {
            host = requireText(host, "control.host");
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("control.port 必须在 0..65535");
            }
            path = requireText(path, "control.path");
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("control.path 必须以 / 开头");
            }
            bearerTokenEnv = requireText(bearerTokenEnv, "control.bearerTokenEnv");
        }
    }

    public record Inbox(
            Path dataDir,
            String inboxId,
            String bindingLabel,
            Set<String> channels,
            long bindingTtlHours,
            String apiHost,
            int apiPort,
            String mcpHost,
            int mcpPort,
            String mcpPath,
            long heartbeatMillis,
            long claimSweepIntervalMillis) {

        public static final String DEFAULT_HOST = "127.0.0.1";
        public static final int DEFAULT_PORT = 0;
        public static final String DEFAULT_MCP_PATH = "/mcp";
        public static final long DEFAULT_HEARTBEAT_MILLIS = 15_000L;
        public static final long DEFAULT_CLAIM_SWEEP_INTERVAL_MILLIS = 5_000L;

        public Inbox {
            dataDir = Objects.requireNonNull(dataDir, "inbox.dataDir").toAbsolutePath().normalize();
            inboxId = requireText(inboxId, "inbox.inboxId");
            bindingLabel = requireText(bindingLabel, "inbox.bindingLabel");
            channels = channels == null || channels.isEmpty()
                    ? Set.of("qq") : Set.copyOf(new LinkedHashSet<>(channels));
            if (bindingTtlHours <= 0 || bindingTtlHours > 24L * 365L) {
                throw new IllegalArgumentException("inbox.bindingTtlHours 超出范围");
            }
            apiHost = requireLoopback(apiHost, "inbox.apiHost");
            mcpHost = requireLoopback(mcpHost, "inbox.mcpHost");
            apiPort = requirePort(apiPort, "inbox.apiPort");
            mcpPort = requirePort(mcpPort, "inbox.mcpPort");
            mcpPath = requireMcpPath(mcpPath);
            if (heartbeatMillis <= 0L) {
                throw new IllegalArgumentException("inbox.heartbeatMillis 必须为正");
            }
            if (claimSweepIntervalMillis <= 0L) {
                throw new IllegalArgumentException(
                        "inbox.claimSweepIntervalMillis 必须为正");
            }
        }

        /** 旧五参调用：网络参数取默认随机回环端口。 */
        public Inbox(Path dataDir, String inboxId, String bindingLabel,
                     Set<String> channels, long bindingTtlHours) {
            this(dataDir, inboxId, bindingLabel, channels, bindingTtlHours,
                    DEFAULT_HOST, DEFAULT_PORT, DEFAULT_HOST, DEFAULT_PORT,
                    DEFAULT_MCP_PATH, DEFAULT_HEARTBEAT_MILLIS,
                    DEFAULT_CLAIM_SWEEP_INTERVAL_MILLIS);
        }

        private static int requirePort(int port, String name) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(name + " 必须在 0..65535");
            }
            return port;
        }

        private static String requireLoopback(String host, String name) {
            String normalized = requireText(host, name);
            if (!ServerConfig.isLoopback(normalized)) {
                throw new IllegalArgumentException(name + " 只允许回环地址: " + normalized);
            }
            return normalized;
        }

        private static String requireMcpPath(String value) {
            String normalized = requireText(value, "inbox.mcpPath");
            if (!normalized.startsWith("/") || normalized.contains("?")
                    || normalized.contains("#")
                    || normalized.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "inbox.mcpPath 必须是以 / 开头且不含 query/fragment/空白的路径");
            }
            return normalized;
        }
    }

    public record Summary(long debounceMillis, int maxMessagesPerDigest) {
        public static Summary defaults() {
            return new Summary(2_000L, 100);
        }

        public Summary {
            if (debounceMillis < 0L) {
                throw new IllegalArgumentException("summary.debounceMillis 不能为负");
            }
            if (maxMessagesPerDigest < 1 || maxMessagesPerDigest > 1000) {
                throw new IllegalArgumentException(
                        "summary.maxMessagesPerDigest 必须在 1..1000");
            }
        }
    }

    public record Outbox(int sendTimeoutSeconds, int minSendIntervalMillis) {
        public static Outbox defaults() {
            return new Outbox(15, 150);
        }

        public Outbox {
            if (sendTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("outbox.sendTimeoutSeconds 必须为正");
            }
            if (minSendIntervalMillis < 0) {
                throw new IllegalArgumentException("outbox.minSendIntervalMillis 不能为负");
            }
        }
    }

    public record Weighting(
            int defaultPrivateSourceWeight,
            int defaultGroupSourceWeight,
            int defaultNotifyThreshold,
            java.util.Map<String, Integer> signalWeights,
            java.util.Map<String, Integer> keywordWeights,
            java.util.Set<String> aliases,
            java.util.Set<String> commandPrefixes,
            int maxMessagesPerDigest,
            long quietMillis,
            long cooldownMillis) {

        public static Weighting defaults() {
            return new Weighting(300, 0, 100,
                    java.util.Map.of("DM", 200, "MENTION", 200,
                            "REPLY_TO_BOT", 180, "NAME", 120, "COMMAND", 250),
                    java.util.Map.of(),
                    java.util.Set.of("贾维洛", "洛洛"),
                    java.util.Set.of("/", "!"),
                    20, 180_000L, 60_000L);
        }

        public Weighting {
            if (defaultPrivateSourceWeight < -1000 || defaultPrivateSourceWeight > 1000
                    || defaultGroupSourceWeight < -1000 || defaultGroupSourceWeight > 1000
                    || defaultNotifyThreshold < -1000 || defaultNotifyThreshold > 1000) {
                throw new IllegalArgumentException("weighting 权重必须在 -1000..1000");
            }
            signalWeights = normalizeWeightMap(signalWeights, "weighting.signalWeights");
            for (String signal : signalWeights.keySet()) {
                try {
                    io.mosire.iibot.bot.weight.WeightSignal.valueOf(signal);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(
                            "weighting.signalWeights 含未知 signal: " + signal);
                }
            }
            keywordWeights = normalizeWeightMap(keywordWeights, "weighting.keywordWeights");
            aliases = normalizeTextSet(aliases, "weighting.aliases");
            commandPrefixes = normalizeTextSet(commandPrefixes, "weighting.commandPrefixes");
            if (maxMessagesPerDigest < 1 || maxMessagesPerDigest > 1000) {
                throw new IllegalArgumentException("weighting.maxMessagesPerDigest 必须在 1..1000");
            }
            if (quietMillis < 0L || quietMillis > 86_400_000L
                    || cooldownMillis < 0L || cooldownMillis > 86_400_000L) {
                throw new IllegalArgumentException("weighting quiet/cooldown 超出范围");
            }
        }

        public io.mosire.iibot.bot.weight.WeightSettings toSettings() {
            java.util.Map<io.mosire.iibot.bot.weight.WeightSignal, Integer> signals =
                    new java.util.LinkedHashMap<>();
            signalWeights.forEach((name, value) -> signals.put(
                    io.mosire.iibot.bot.weight.WeightSignal.valueOf(name), value));
            return new io.mosire.iibot.bot.weight.WeightSettings(
                    defaultPrivateSourceWeight, defaultGroupSourceWeight,
                    defaultNotifyThreshold, signals, keywordWeights, aliases,
                    commandPrefixes, maxMessagesPerDigest, quietMillis, cooldownMillis);
        }

        private static java.util.Map<String, Integer> normalizeWeightMap(
                java.util.Map<String, Integer> value, String name) {
            if (value == null || value.isEmpty()) {
                return java.util.Map.of();
            }
            java.util.Map<String, Integer> normalized = new java.util.LinkedHashMap<>();
            for (var entry : value.entrySet()) {
                String key = requireText(entry.getKey(), name + " key");
                if (entry.getValue() == null || entry.getValue() < -1000
                        || entry.getValue() > 1000) {
                    throw new IllegalArgumentException(name + " 权重必须在 -1000..1000");
                }
                normalized.put(key, entry.getValue());
            }
            return java.util.Map.copyOf(normalized);
        }

        private static java.util.Set<String> normalizeTextSet(
                java.util.Set<String> value, String name) {
            if (value == null || value.isEmpty()) {
                return java.util.Set.of();
            }
            java.util.Set<String> normalized = new java.util.LinkedHashSet<>();
            for (String item : value) {
                normalized.add(requireText(item, name));
            }
            return java.util.Set.copyOf(normalized);
        }
    }

    private static URI requireUri(URI value, String name) {
        Objects.requireNonNull(value, name);
        String scheme = value.getScheme();
        if (scheme == null || (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)
                && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(name + " 必须是 ws/wss/http/https URI");
        }
        return value;
    }

    private static Optional<String> normalizeOptional(Optional<String> value) {
        return value == null ? Optional.empty()
                : value.map(String::trim).filter(text -> !text.isEmpty());
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
