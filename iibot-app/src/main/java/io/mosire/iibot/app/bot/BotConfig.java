package io.mosire.iibot.app.bot;

import io.mosire.inbox.server.ServerConfig;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bot 配置 v2 的严格、已解析快照。
 *
 * <p>P4 runtime 消费全部 enabled {@link #accounts()}；第一个 enabled account 仍作为
 * 直接 provider 工具的 active/default account。配置中永远只保存 secret 环境变量名，
 * 不保存 secret 值。</p>
 */
public record BotConfig(
        Path dataDir,
        Map<String, Account> accounts,
        Control control,
        Inbox inbox,
        MessageBoard messageBoard,
        Logging logging) {

    public BotConfig {
        dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalArgumentException("accounts 至少需要一个账号");
        }
        Map<String, Account> accountCopy = new LinkedHashMap<>();
        accounts.forEach((alias, account) -> accountCopy.put(
                requireText(alias, "accounts alias"), Objects.requireNonNull(account, alias)));
        accounts = Collections.unmodifiableMap(accountCopy);
        control = Objects.requireNonNull(control, "control");
        inbox = Objects.requireNonNull(inbox, "inbox");
        messageBoard = messageBoard == null ? MessageBoard.defaults() : messageBoard;
        logging = logging == null ? Logging.defaults(dataDir.getParent()) : logging;
        if (accounts.values().stream().noneMatch(Account::enabled)) {
            throw new IllegalArgumentException("accounts 至少需要一个 enabled=true 的账号");
        }
    }

    /** 按配置顺序返回第一个 enabled account，作为 direct-provider 工具的 active 账号。 */
    public Map.Entry<String, Account> firstEnabledAccount() {
        return accounts.entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有 enabled account"));
    }

    public String firstEnabledAlias() {
        return firstEnabledAccount().getKey();
    }

    public Account activeAccount() {
        return firstEnabledAccount().getValue();
    }

    /** active/default account 的 accountId；完整多账号集合使用 {@link #accounts()}。 */
    public String accountId() {
        return activeAccount().accountId();
    }

    /** active/default account 的 OneBot 配置；每个账号的 Adapter 由 app 组合根创建。 */
    public OneBot activeOneBot() {
        return activeAccount().oneBot();
    }

    public record Account(boolean enabled, String adapter, String accountId, OneBot oneBot) {
        public Account {
            adapter = requireText(adapter, "accounts.adapter");
            if (!"onebot11".equals(adapter)) {
                throw new IllegalArgumentException("accounts.adapter 首版只允许 onebot11");
            }
            accountId = requireText(accountId, "accounts.accountId");
            oneBot = Objects.requireNonNull(oneBot, "accounts.oneBot");
        }
    }

    public record OneBot(
            URI eventWebSocketUri,
            URI httpBaseUri,
            Optional<String> accessTokenEnv,
            Optional<String> privateTempGroupId,
            boolean suppressSelfMessages,
            int requestTimeoutSeconds,
            int maxTextLength) {

        public OneBot {
            eventWebSocketUri = requireWsUri(eventWebSocketUri, "oneBot.eventWebSocketUri");
            httpBaseUri = requireHttpUri(httpBaseUri, "oneBot.httpBaseUri");
            accessTokenEnv = normalizeOptional(accessTokenEnv);
            privateTempGroupId = normalizeOptional(privateTempGroupId);
            validateSecretEnvName(accessTokenEnv, "oneBot.accessTokenEnv");
            if (requestTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("oneBot.requestTimeoutSeconds 必须为正");
            }
            if (maxTextLength <= 0) {
                throw new IllegalArgumentException("oneBot.maxTextLength 必须为正");
            }
        }
    }

    public record Control(String host, int port, String path, String bearerTokenEnv) {
        public Control {
            host = requireLoopback(host, "control.host");
            port = requirePort(port, "control.port");
            path = requireMcpPath(path, "control.path");
            bearerTokenEnv = requireText(bearerTokenEnv, "control.bearerTokenEnv");
            validateSecretEnvName(Optional.of(bearerTokenEnv), "control.bearerTokenEnv");
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
            mcpPath = requireMcpPath(mcpPath, "inbox.mcpPath");
            if (heartbeatMillis <= 0L) {
                throw new IllegalArgumentException("inbox.heartbeatMillis 必须为正");
            }
            if (claimSweepIntervalMillis <= 0L) {
                throw new IllegalArgumentException(
                        "inbox.claimSweepIntervalMillis 必须为正");
            }
        }
    }

    public record MessageBoard(long summaryDebounceMillis, int maxPreviewMessages,
                               int inlineMaxChars, Outbox outbox, Weighting weighting) {
        public MessageBoard {
            if (summaryDebounceMillis < 0L || summaryDebounceMillis > 86_400_000L) {
                throw new IllegalArgumentException(
                        "messageBoard.summaryDebounceMillis 必须在 0..86400000");
            }
            if (maxPreviewMessages < 1 || maxPreviewMessages > 1000) {
                throw new IllegalArgumentException(
                        "messageBoard.maxPreviewMessages 必须在 1..1000");
            }
            if (inlineMaxChars < 0) {
                throw new IllegalArgumentException("messageBoard.inlineMaxChars 不能为负");
            }
            outbox = outbox == null ? Outbox.defaults() : outbox;
            weighting = weighting == null ? Weighting.defaults() : weighting;
        }

        public static MessageBoard defaults() {
            return new MessageBoard(2_000L, 20, 512, Outbox.defaults(),
                    Weighting.defaults());
        }
    }

    public record Outbox(int sendTimeoutSeconds, int minSendIntervalMillis) {
        public static Outbox defaults() {
            return new Outbox(15, 150);
        }

        public Outbox {
            if (sendTimeoutSeconds <= 0 || sendTimeoutSeconds > 3_600) {
                throw new IllegalArgumentException(
                        "messageBoard.outbox.sendTimeoutSeconds 必须在 1..3600");
            }
            if (minSendIntervalMillis < 0 || minSendIntervalMillis > 3_600_000) {
                throw new IllegalArgumentException(
                        "messageBoard.outbox.minSendIntervalMillis 必须在 0..3600000");
            }
        }
    }

    public record Weighting(
            int defaultPrivateSourceWeight,
            int defaultGroupSourceWeight,
            int defaultNotifyThreshold,
            Map<String, Integer> signalWeights,
            Map<String, Integer> keywordWeights,
            Set<String> aliases,
            Set<String> commandPrefixes,
            long quietMillis,
            long cooldownMillis) {

        public static Weighting defaults() {
            return new Weighting(300, 0, 100,
                    Map.of("DM", 200, "MENTION", 200,
                            "REPLY_TO_BOT", 180, "NAME", 120, "COMMAND", 250),
                    Map.of(), Set.of(), Set.of("/", "!"),
                    180_000L, 60_000L);
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
            if (quietMillis < 0L || quietMillis > 86_400_000L
                    || cooldownMillis < 0L || cooldownMillis > 86_400_000L) {
                throw new IllegalArgumentException("weighting quiet/cooldown 超出范围");
            }
        }

        public io.mosire.iibot.bot.weight.WeightSettings toSettings(int maxMessagesPerDigest) {
            Map<io.mosire.iibot.bot.weight.WeightSignal, Integer> signals =
                    new LinkedHashMap<>();
            signalWeights.forEach((name, value) -> signals.put(
                    io.mosire.iibot.bot.weight.WeightSignal.valueOf(name), value));
            return new io.mosire.iibot.bot.weight.WeightSettings(
                    defaultPrivateSourceWeight, defaultGroupSourceWeight,
                    defaultNotifyThreshold, signals, keywordWeights, aliases,
                    commandPrefixes, maxMessagesPerDigest, quietMillis, cooldownMillis);
        }

        private static Map<String, Integer> normalizeWeightMap(
                Map<String, Integer> value, String name) {
            if (value == null || value.isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> normalized = new LinkedHashMap<>();
            for (var entry : value.entrySet()) {
                String key = requireText(entry.getKey(), name + " key");
                if (entry.getValue() == null || entry.getValue() < -1000
                        || entry.getValue() > 1000) {
                    throw new IllegalArgumentException(name + " 权重必须在 -1000..1000");
                }
                normalized.put(key, entry.getValue());
            }
            return Collections.unmodifiableMap(normalized);
        }

        private static Set<String> normalizeTextSet(Set<String> value, String name) {
            if (value == null || value.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String item : value) {
                normalized.add(requireText(item, name));
            }
            return Set.copyOf(normalized);
        }
    }

    public record Logging(
            Path directory,
            boolean console,
            boolean file,
            String rootLevel,
            String opsLevel,
            Map<String, String> levels,
            String maxFileSize,
            int maxHistory) {

        public static final Set<String> LEVELS =
                Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
        public static final Set<String> KNOWN_LOGGER_PREFIXES = Set.of(
                "io.mosire.iibot.connect",
                "io.mosire.iibot.bot",
                "io.mosire.iibot.app",
                "io.mosire.iibot.inbox",
                "iibot.ops");

        public Logging {
            directory = Objects.requireNonNull(directory, "logging.directory")
                    .toAbsolutePath().normalize();
            rootLevel = requireLevel(rootLevel, "logging.rootLevel");
            opsLevel = requireLevel(opsLevel, "logging.opsLevel");
            Map<String, String> levelCopy = new LinkedHashMap<>();
            if (levels != null) {
                levels.forEach((name, level) -> {
                    String loggerName = requireText(name, "logging.levels key");
                    if (!isKnownLogger(loggerName)) {
                        throw new IllegalArgumentException(
                                "logging.levels 含未知包名: " + loggerName);
                    }
                    levelCopy.put(loggerName, requireLevel(level,
                            "logging.levels." + loggerName));
                });
            }
            levels = Collections.unmodifiableMap(levelCopy);
            maxFileSize = requireText(maxFileSize, "logging.maxFileSize");
            if (!maxFileSize.matches("[1-9][0-9]*\\s?(B|KB|MB|GB)")) {
                throw new IllegalArgumentException(
                        "logging.maxFileSize 必须为如 20MB/5 MB 的正大小");
            }
            if (maxHistory < 1 || maxHistory > 10_000) {
                throw new IllegalArgumentException("logging.maxHistory 必须在 1..10000");
            }
        }

        public static Logging defaults(Path baseDir) {
            Path base = baseDir == null ? Path.of(".") : baseDir;
            return new Logging(base.resolve("logs"), true, true, "INFO", "INFO",
                    Map.of(), "20MB", 30);
        }

        public static boolean isKnownLogger(String name) {
            return KNOWN_LOGGER_PREFIXES.stream().anyMatch(prefix ->
                    name.equals(prefix) || name.startsWith(prefix + "."));
        }

        private static String requireLevel(String value, String name) {
            String normalized = requireText(value, name);
            if (!LEVELS.contains(normalized)) {
                throw new IllegalArgumentException(
                        name + " 必须是 TRACE/DEBUG/INFO/WARN/ERROR/OFF 之一");
            }
            return normalized;
        }
    }

    private static URI requireWsUri(URI value, String name) {
        URI uri = requireUri(value, name);
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new IllegalArgumentException(name + " 必须是 ws/wss URI");
        }
        return uri;
    }

    private static URI requireHttpUri(URI value, String name) {
        URI uri = requireUri(value, name);
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(name + " 必须是 http/https URI");
        }
        return uri;
    }

    private static URI requireUri(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getScheme() == null || value.getHost() == null) {
            throw new IllegalArgumentException(name + " 必须是带 host 的合法 URI");
        }
        if (value.getUserInfo() != null) {
            throw new IllegalArgumentException(name + " 不允许包含 userinfo");
        }
        return value;
    }

    private static Optional<String> normalizeOptional(Optional<String> value) {
        return value == null ? Optional.empty()
                : value.map(String::trim).filter(text -> !text.isEmpty());
    }

    private static void validateSecretEnvName(Optional<String> value, String name) {
        value.ifPresent(env -> {
            if (!env.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                        name + " 必须是合法环境变量名: " + env);
            }
        });
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

    private static String requireMcpPath(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.startsWith("/") || normalized.contains("?")
                || normalized.contains("#")
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    name + " 必须是以 / 开头且不含 query/fragment/空白的路径");
        }
        return normalized;
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
