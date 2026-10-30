package io.mosire.iibot.app.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.iibot.connect.video.core.VideoQuality;
import io.mosire.iibot.app.config.ConfigException;
import io.mosire.iibot.app.config.JsonConfigBundle;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.inbox.server.ServerConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bot 配置 v2 装载器。
 *
 * <p>严格拒绝未知字段，并把错误定位到来源文件与 JSON pointer。所有路径相对合并后的
 * 配置目录解析。secret 只接受环境变量名；{@link #loadDetailed} 会收集缺失 env，
 * 供诊断命令输出 SET/MISSING，而普通启动路径通过 {@link #load} fail-closed。</p>
 */
public final class BotConfigLoader {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "$schema", "dataDir", "accounts", "control", "inbox", "messageBoard",
            "logging", "video");
    private static final Set<String> ACCOUNT_FIELDS = Set.of(
            "enabled", "adapter", "accountId", "oneBot", "bilibili");
    private static final Set<String> ONEBOT_FIELDS = Set.of(
            "eventWebSocketUri", "httpBaseUri", "accessTokenEnv", "privateTempGroupId",
            "suppressSelfMessages", "requestTimeoutSeconds", "maxTextLength");
    private static final Set<String> BILIBILI_FIELDS = Set.of(
            "credentialFileEnv", "pollIntervalSeconds", "receivePrivateMessages",
            "receiveCommentReplies", "writeEnabled", "maxWritesPerDay");
    private static final Set<String> CONTROL_FIELDS = Set.of(
            "host", "port", "path", "bearerTokenEnv");
    private static final Set<String> INBOX_FIELDS = Set.of(
            "dataDir", "inboxId", "bindingLabel", "channels", "bindingTtlHours",
            "apiHost", "apiPort", "mcpHost", "mcpPort", "mcpPath",
            "heartbeatMillis", "claimSweepIntervalMillis");
    private static final Set<String> MESSAGE_BOARD_FIELDS = Set.of(
            "summaryDebounceMillis", "maxPreviewMessages", "inlineMaxChars", "outbox", "weighting");
    private static final Set<String> OUTBOX_FIELDS = Set.of(
            "sendTimeoutSeconds", "minSendIntervalMillis");
    private static final Set<String> WEIGHTING_FIELDS = Set.of(
            "defaultPrivateSourceWeight", "defaultGroupSourceWeight",
            "defaultNotifyThreshold", "signalWeights", "keywordWeights",
            "aliases", "commandPrefixes", "quietMillis", "cooldownMillis");
    private static final Set<String> LOGGING_FIELDS = Set.of(
            "directory", "console", "file", "rootLevel", "opsLevel", "levels",
            "maxFileSize", "maxHistory");
    private static final Set<String> VIDEO_FIELDS = Set.of(
            "defaultQuality", "maxHeight", "defaultFrameCount", "maxFrameCount", "comments");
    private static final Set<String> VIDEO_COMMENTS_FIELDS = Set.of(
            "defaultLimit", "maxLimit", "allowFullScan");

    private BotConfigLoader() {
    }

    public static BotConfig load(Path configFile) {
        return load(List.of(configFile), SecretProvider.environment());
    }

    public static BotConfig load(List<Path> configSources) {
        return load(configSources, SecretProvider.environment());
    }

    /** 严格加载；任一必需 secret env 缺失即 fail-closed。 */
    public static BotConfig load(List<Path> configSources, SecretProvider secrets) {
        LoadResult result = loadDetailed(configSources, secrets);
        if (!result.secretErrors().isEmpty()) {
            throw result.secretErrors().getFirst();
        }
        return result.config();
    }

    public static BotConfig load(Path configFile, SecretProvider secrets) {
        return load(List.of(configFile), secrets);
    }

    /**
     * 加载并做全部结构/跨字段校验。缺失 secret env 不抛异常，而是记录在
     * {@link LoadResult#secretErrors()}，让 --check-config 能同时输出 MISSING 状态。
     */
    public static LoadResult loadDetailed(List<Path> configSources, SecretProvider secrets) {
        if (configSources == null || configSources.isEmpty()) {
            throw ConfigException.invalid((Path) null, "/", "至少一个配置源", "missing",
                    "使用 --config 指定配置文件或目录");
        }
        JsonConfigBundle.Bundle bundle = JsonConfigBundle.load(configSources);
        Reader reader = new Reader(bundle);
        return reader.load(secrets == null ? SecretProvider.environment() : secrets);
    }

    public record LoadResult(BotConfig config, List<ConfigException> secretErrors) {
        public LoadResult {
            config = java.util.Objects.requireNonNull(config, "config");
            secretErrors = List.copyOf(secretErrors);
        }

        public boolean secretReady() {
            return secretErrors.isEmpty();
        }
    }

    private static final class Reader {
        private final JsonConfigBundle.Bundle bundle;

        private Reader(JsonConfigBundle.Bundle bundle) {
            this.bundle = bundle;
        }

        private LoadResult load(SecretProvider secrets) {
            JsonNode root = bundle.root();
            requireObject(root, "/", "配置根");
            rejectUnknown(root, ROOT_FIELDS, "/", "配置根");
            JsonNode schemaHint = root.get("$schema");
            if (schemaHint != null && !schemaHint.isNull()
                    && (!schemaHint.isTextual() || schemaHint.asText().isBlank())) {
                throw error("/$schema", "non-empty string",
                        JsonConfigBundle.nodeType(schemaHint),
                        "$schema 若出现必须是非空字符串");
            }

            Path baseDir = bundle.baseDir();
            Path dataDir = resolvePath(baseDir,
                    requiredText(root, "dataDir", "/"), "/dataDir");
            JsonNode accountsNode = requiredObject(root, "accounts", "/");
            Map<String, BotConfig.Account> accounts = parseAccounts(accountsNode);
            if (accounts.values().stream().noneMatch(BotConfig.Account::enabled)) {
                throw error("/accounts", "at least one enabled account",
                        JsonConfigBundle.nodeType(accountsNode),
                        "至少一个账号必须 enabled=true；否则 runtime 没有可启动账号");
            }

            BotConfig.Control control = parseControl(
                    requiredObject(root, "control", "/"));
            BotConfig.Inbox inbox = parseInbox(
                    requiredObject(root, "inbox", "/"), baseDir, dataDir);
            BotConfig.MessageBoard messageBoard = parseMessageBoard(root);
            BotConfig.Logging logging = parseLogging(root, baseDir);
            BotConfig.Video video = parseVideo(root);

            BotConfig config;
            try {
                config = new BotConfig(dataDir, accounts, control, inbox, messageBoard,
                        logging, video);
            } catch (IllegalArgumentException invalid) {
                throw error("/", "valid bot config", "object", invalid.getMessage());
            }

            List<ConfigException> secretErrors = new ArrayList<>();
            checkSecret(secrets, control.bearerTokenEnv(), "/control/bearerTokenEnv",
                    "control bearer token", secretErrors);
            for (Map.Entry<String, BotConfig.Account> entry : accounts.entrySet()) {
                BotConfig.Account account = entry.getValue();
                if (!account.enabled()) {
                    continue;
                }
                if (account.oneBot() != null) {
                    Optional<String> env = account.oneBot().accessTokenEnv();
                    env.ifPresent(name -> checkSecret(secrets, name,
                            "/accounts/" + escape(entry.getKey())
                                    + "/oneBot/accessTokenEnv",
                            "OneBot access token", secretErrors));
                }
                if (account.bilibili() != null) {
                    checkSecret(secrets, account.bilibili().credentialFileEnv(),
                            "/accounts/" + escape(entry.getKey())
                                    + "/bilibili/credentialFileEnv",
                            "Bilibili credential file", secretErrors);
                }
            }
            return new LoadResult(config, secretErrors);
        }

        private Map<String, BotConfig.Account> parseAccounts(JsonNode accountsNode) {
            Map<String, BotConfig.Account> accounts = new LinkedHashMap<>();
            Set<String> accountIds = new LinkedHashSet<>();
            var fields = accountsNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String alias = entry.getKey();
                String pointer = "/accounts/" + escape(alias);
                if (!alias.matches("[A-Za-z0-9._-]+")) {
                    throw error(pointer, "alias matching [A-Za-z0-9._-]+", "string",
                            "account alias 只允许字母、数字、点、下划线和连字符: " + alias);
                }
                JsonNode accountNode = entry.getValue();
                requireObject(accountNode, pointer, "account " + alias);
                rejectUnknown(accountNode, ACCOUNT_FIELDS, pointer, "account " + alias);

                boolean enabled = requiredBoolean(accountNode, "enabled", pointer);
                String adapter = requiredText(accountNode, "adapter", pointer);
                String accountId = requiredText(accountNode, "accountId", pointer);
                if (!accountIds.add(accountId)) {
                    throw error(pointer + "/accountId", "globally unique accountId",
                            "string", "accountId 重复: " + accountId
                                    + "；每个账号的消息板分区键必须唯一");
                }
                BotConfig.OneBot oneBot = null;
                BotConfig.Bilibili bilibili = null;
                switch (adapter) {
                    case "onebot11" -> {
                        if (accountNode.has("bilibili")) {
                            throw error(pointer + "/bilibili", "absent for onebot11",
                                    "object", "onebot11 账号不能配置 bilibili");
                        }
                        JsonNode oneBotNode = requiredObject(accountNode, "oneBot", pointer);
                        oneBot = parseOneBot(oneBotNode, pointer + "/oneBot");
                    }
                    case "bilibili" -> {
                        if (accountNode.has("oneBot")) {
                            throw error(pointer + "/oneBot", "absent for bilibili",
                                    "object", "bilibili 账号不能配置 oneBot");
                        }
                        JsonNode biliNode = requiredObject(accountNode, "bilibili", pointer);
                        bilibili = parseBilibili(biliNode, pointer + "/bilibili");
                    }
                    default -> throw error(pointer + "/adapter",
                            "onebot11|bilibili", "string",
                            "adapter 只允许 onebot11 或 bilibili，实际: " + adapter);
                }
                accounts.put(alias, new BotConfig.Account(
                        enabled, adapter, accountId, oneBot, bilibili));
            }
            if (accounts.isEmpty()) {
                throw error("/accounts", "non-empty object", "empty object",
                        "accounts 至少需要一个账号");
            }
            return accounts;
        }

        private BotConfig.OneBot parseOneBot(JsonNode node, String pointer) {
            rejectUnknown(node, ONEBOT_FIELDS, pointer, "oneBot");
            URI ws = parseUri(requiredText(node, "eventWebSocketUri", pointer),
                    pointer + "/eventWebSocketUri", Set.of("ws", "wss"));
            URI http = parseUri(requiredText(node, "httpBaseUri", pointer),
                    pointer + "/httpBaseUri", Set.of("http", "https"));
            Optional<String> tokenEnv = optionalText(node, "accessTokenEnv", pointer);
            tokenEnv.ifPresent(env -> validateEnvName(env,
                    pointer + "/accessTokenEnv"));
            Optional<String> tempGroup = optionalText(node, "privateTempGroupId", pointer);
            boolean suppressSelf = optionalBoolean(node, "suppressSelfMessages", pointer, true);
            int requestTimeout = optionalInt(node, "requestTimeoutSeconds", pointer, 10);
            int maxTextLength = optionalInt(node, "maxTextLength", pointer, 2000);
            try {
                return new BotConfig.OneBot(ws, http, tokenEnv, tempGroup,
                        suppressSelf, requestTimeout, maxTextLength);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid oneBot config", "object", invalid.getMessage());
            }
        }

        private BotConfig.Bilibili parseBilibili(JsonNode node, String pointer) {
            rejectUnknown(node, BILIBILI_FIELDS, pointer, "bilibili");
            String credentialEnv = requiredText(node, "credentialFileEnv", pointer);
            validateEnvName(credentialEnv, pointer + "/credentialFileEnv");
            int pollSeconds = optionalInt(node, "pollIntervalSeconds", pointer, 60);
            boolean privateMessages = optionalBoolean(
                    node, "receivePrivateMessages", pointer, true);
            boolean commentReplies = optionalBoolean(
                    node, "receiveCommentReplies", pointer, true);
            boolean writeEnabled = optionalBoolean(node, "writeEnabled", pointer, false);
            int maxWrites = optionalInt(node, "maxWritesPerDay", pointer, 3);
            try {
                return new BotConfig.Bilibili(credentialEnv, pollSeconds, privateMessages,
                        commentReplies, writeEnabled, maxWrites);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid bilibili config", "object",
                        invalid.getMessage());
            }
        }

        private BotConfig.Control parseControl(JsonNode node) {
            String pointer = "/control";
            rejectUnknown(node, CONTROL_FIELDS, pointer, "control");
            String host = optionalText(node, "host", pointer).orElse("127.0.0.1");
            if (!ServerConfig.isLoopback(host)) {
                throw error(pointer + "/host", "loopback host", "string",
                        "control.host 只允许回环地址，实际: " + host);
            }
            int port = optionalInt(node, "port", pointer, 8765);
            String path = optionalText(node, "path", pointer).orElse("/mcp/bot");
            String tokenEnv = requiredText(node, "bearerTokenEnv", pointer);
            validateEnvName(tokenEnv, pointer + "/bearerTokenEnv");
            try {
                return new BotConfig.Control(host, port, path, tokenEnv);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid control config", "object", invalid.getMessage());
            }
        }

        private BotConfig.Inbox parseInbox(JsonNode node, Path baseDir, Path dataDir) {
            String pointer = "/inbox";
            rejectUnknown(node, INBOX_FIELDS, pointer, "inbox");
            Path inboxDir = optionalText(node, "dataDir", pointer)
                    .map(value -> resolvePath(baseDir, value, pointer + "/dataDir"))
                    .orElseGet(() -> dataDir.resolve("inbox"));
            String inboxId = optionalText(node, "inboxId", pointer).orElse("iibot-bot");
            String bindingLabel = optionalText(node, "bindingLabel", pointer)
                    .orElse("dsh-bot");
            Set<String> channels = optionalStringSet(node, "channels", pointer)
                    .orElse(Set.of("qq"));
            long ttlHours = optionalLong(node, "bindingTtlHours", pointer, 24L);
            String apiHost = optionalText(node, "apiHost", pointer)
                    .orElse(BotConfig.Inbox.DEFAULT_HOST);
            int apiPort = optionalInt(node, "apiPort", pointer,
                    BotConfig.Inbox.DEFAULT_PORT);
            String mcpHost = optionalText(node, "mcpHost", pointer)
                    .orElse(BotConfig.Inbox.DEFAULT_HOST);
            int mcpPort = optionalInt(node, "mcpPort", pointer,
                    BotConfig.Inbox.DEFAULT_PORT);
            String mcpPath = optionalText(node, "mcpPath", pointer)
                    .orElse(BotConfig.Inbox.DEFAULT_MCP_PATH);
            long heartbeatMillis = optionalLong(node, "heartbeatMillis", pointer,
                    BotConfig.Inbox.DEFAULT_HEARTBEAT_MILLIS);
            long claimSweepMillis = optionalLong(node, "claimSweepIntervalMillis", pointer,
                    BotConfig.Inbox.DEFAULT_CLAIM_SWEEP_INTERVAL_MILLIS);
            for (String host : List.of(apiHost, mcpHost)) {
                if (!ServerConfig.isLoopback(host)) {
                    throw error(pointer + (host.equals(apiHost) ? "/apiHost" : "/mcpHost"),
                            "loopback host", "string",
                            "Inbox 监听 host 只允许回环地址，实际: " + host);
                }
            }
            try {
                return new BotConfig.Inbox(inboxDir, inboxId, bindingLabel, channels,
                        ttlHours, apiHost, apiPort, mcpHost, mcpPort, mcpPath,
                        heartbeatMillis, claimSweepMillis);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid inbox config", "object", invalid.getMessage());
            }
        }

        private BotConfig.Video parseVideo(JsonNode root) {
            JsonNode node = optionalObject(root, "video", "/");
            if (node == null) {
                return BotConfig.Video.defaults();
            }
            String pointer = "/video";
            rejectUnknown(node, VIDEO_FIELDS, pointer, "video");
            String qualityText = optionalText(node, "defaultQuality", pointer)
                    .orElse("auto");
            VideoQuality quality;
            try {
                quality = VideoQuality.parse(qualityText);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer + "/defaultQuality",
                        "auto/360p/480p/720p/1080p/best", qualityText,
                        invalid.getMessage());
            }
            int maxHeight = optionalInt(node, "maxHeight", pointer, 720);
            int defaultFrameCount = optionalInt(node, "defaultFrameCount", pointer, 6);
            int maxFrameCount = optionalInt(node, "maxFrameCount", pointer, 9);
            BotConfig.Comments comments = parseVideoComments(node, pointer);
            try {
                return new BotConfig.Video(quality, maxHeight, defaultFrameCount,
                        maxFrameCount, comments);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid video config", "object", invalid.getMessage());
            }
        }

        private BotConfig.Comments parseVideoComments(JsonNode video, String parentPointer) {
            JsonNode node = optionalObject(video, "comments", parentPointer);
            if (node == null) {
                return BotConfig.Comments.defaults();
            }
            String pointer = parentPointer + "/comments";
            rejectUnknown(node, VIDEO_COMMENTS_FIELDS, pointer, "video.comments");
            int defaultLimit = optionalInt(node, "defaultLimit", pointer, 10);
            int maxLimit = optionalInt(node, "maxLimit", pointer, 20);
            boolean allowFullScan = optionalBoolean(node, "allowFullScan", pointer, false);
            try {
                return new BotConfig.Comments(defaultLimit, maxLimit, allowFullScan);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid video.comments config", "object",
                        invalid.getMessage());
            }
        }

        private BotConfig.MessageBoard parseMessageBoard(JsonNode root) {
            JsonNode node = optionalObject(root, "messageBoard", "/");
            if (node == null) {
                return BotConfig.MessageBoard.defaults();
            }
            String pointer = "/messageBoard";
            rejectUnknown(node, MESSAGE_BOARD_FIELDS, pointer, "messageBoard");
            long debounce = optionalLong(node, "summaryDebounceMillis", pointer, 2_000L);
            int maxPreview = optionalInt(node, "maxPreviewMessages", pointer, 20);
            int inlineMaxChars = optionalInt(node, "inlineMaxChars", pointer, 512);
            BotConfig.Outbox outbox = parseOutbox(node, pointer);
            BotConfig.Weighting weighting = parseWeighting(node, pointer);
            try {
                return new BotConfig.MessageBoard(debounce, maxPreview, inlineMaxChars,
                        outbox, weighting);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid messageBoard config", "object",
                        invalid.getMessage());
            }
        }

        private BotConfig.Outbox parseOutbox(JsonNode board, String parentPointer) {
            JsonNode node = optionalObject(board, "outbox", parentPointer);
            if (node == null) {
                return BotConfig.Outbox.defaults();
            }
            String pointer = parentPointer + "/outbox";
            rejectUnknown(node, OUTBOX_FIELDS, pointer, "outbox");
            int timeout = optionalInt(node, "sendTimeoutSeconds", pointer, 15);
            int interval = optionalInt(node, "minSendIntervalMillis", pointer, 150);
            try {
                return new BotConfig.Outbox(timeout, interval);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid outbox config", "object", invalid.getMessage());
            }
        }

        private BotConfig.Weighting parseWeighting(JsonNode board, String parentPointer) {
            JsonNode node = optionalObject(board, "weighting", parentPointer);
            if (node == null) {
                return BotConfig.Weighting.defaults();
            }
            String pointer = parentPointer + "/weighting";
            rejectUnknown(node, WEIGHTING_FIELDS, pointer, "weighting");
            int privateWeight = optionalInt(node, "defaultPrivateSourceWeight", pointer, 300);
            int groupWeight = optionalInt(node, "defaultGroupSourceWeight", pointer, 0);
            int threshold = optionalInt(node, "defaultNotifyThreshold", pointer, 100);
            long quiet = optionalLong(node, "quietMillis", pointer, 180_000L);
            long cooldown = optionalLong(node, "cooldownMillis", pointer, 60_000L);
            Map<String, Integer> signals = optionalWeightMap(node, "signalWeights", pointer,
                    Map.of("DM", 200, "MENTION", 200, "REPLY_TO_BOT", 180,
                            "NAME", 120, "COMMAND", 250));
            Map<String, Integer> keywords = optionalWeightMap(node, "keywordWeights", pointer,
                    Map.of());
            Set<String> aliases = optionalStringSet(node, "aliases", pointer).orElse(Set.of());
            Set<String> commands = optionalStringSet(node, "commandPrefixes", pointer)
                    .orElse(Set.of("/", "!"));
            try {
                return new BotConfig.Weighting(privateWeight, groupWeight, threshold,
                        signals, keywords, aliases, commands, quiet, cooldown);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid weighting config", "object",
                        invalid.getMessage());
            }
        }

        private Map<String, Integer> optionalWeightMap(JsonNode node, String field,
                                                        String parentPointer,
                                                        Map<String, Integer> fallback) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            String pointer = parentPointer + "/" + escape(field);
            requireObject(value, pointer, field);
            Map<String, Integer> result = new LinkedHashMap<>();
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!entry.getValue().isIntegralNumber()) {
                    throw error(pointer + "/" + escape(entry.getKey()), "integer",
                            JsonConfigBundle.nodeType(entry.getValue()),
                            field + " 权重必须是整数");
                }
                result.put(entry.getKey(), entry.getValue().intValue());
            }
            return result;
        }

        private BotConfig.Logging parseLogging(JsonNode root, Path baseDir) {
            JsonNode node = optionalObject(root, "logging", "/");
            if (node == null) {
                return BotConfig.Logging.defaults(baseDir);
            }
            String pointer = "/logging";
            rejectUnknown(node, LOGGING_FIELDS, pointer, "logging");
            Path directory = optionalText(node, "directory", pointer)
                    .map(value -> resolvePath(baseDir, value, pointer + "/directory"))
                    .orElseGet(() -> baseDir.resolve("logs").toAbsolutePath().normalize());
            boolean console = optionalBoolean(node, "console", pointer, true);
            boolean file = optionalBoolean(node, "file", pointer, true);
            String rootLevel = optionalText(node, "rootLevel", pointer).orElse("INFO");
            String opsLevel = optionalText(node, "opsLevel", pointer).orElse("INFO");
            Map<String, String> levels = parseLevels(node, pointer);
            String maxFileSize = optionalText(node, "maxFileSize", pointer).orElse("20MB");
            int maxHistory = optionalInt(node, "maxHistory", pointer, 30);
            try {
                return new BotConfig.Logging(directory, console, file, rootLevel, opsLevel,
                        levels, maxFileSize, maxHistory);
            } catch (IllegalArgumentException invalid) {
                throw error(pointer, "valid logging config", "object", invalid.getMessage());
            }
        }

        private Map<String, String> parseLevels(JsonNode loggingNode, String parentPointer) {
            JsonNode node = loggingNode.get("levels");
            if (node == null || node.isNull()) {
                return Map.of();
            }
            String pointer = parentPointer + "/levels";
            requireObject(node, pointer, "logging.levels");
            Map<String, String> levels = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String loggerName = entry.getKey();
                String childPointer = pointer + "/" + escape(loggerName);
                if (!BotConfig.Logging.isKnownLogger(loggerName)) {
                    throw error(childPointer, "known logger package", "string",
                            "未知包名；允许 io.mosire.iibot.connect/bot/app/inbox 及其子包、iibot.ops");
                }
                if (!entry.getValue().isTextual()) {
                    throw error(childPointer, "log level string",
                            JsonConfigBundle.nodeType(entry.getValue()),
                            "日志级别必须是 TRACE/DEBUG/INFO/WARN/ERROR/OFF");
                }
                String level = entry.getValue().asText().trim();
                if (!BotConfig.Logging.LEVELS.contains(level)) {
                    throw error(childPointer, "TRACE|DEBUG|INFO|WARN|ERROR|OFF", "string",
                            "非法日志级别: " + entry.getValue().asText());
                }
                levels.put(loggerName, level);
            }
            return levels;
        }

        private void checkSecret(SecretProvider secrets, String name, String pointer,
                                 String label, List<ConfigException> errors) {
            if (secrets.lookup(name).filter(value -> !value.isBlank()).isEmpty()) {
                errors.add(error(pointer, "non-empty environment variable " + name,
                        "MISSING", label + " 的环境变量 " + name
                                + " 未设置；在启动 shell 中 export 后再运行"));
            }
        }

        private void validateEnvName(String value, String pointer) {
            if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw error(pointer, "environment variable name",
                        "string", "secret env 名必须匹配 [A-Za-z_][A-Za-z0-9_]*");
            }
        }

        private JsonNode requiredObject(JsonNode parent, String field, String parentPointer) {
            JsonNode value = parent.get(field);
            String pointer = parentPointer + "/" + escape(field);
            if (value == null || value.isNull()) {
                throw missing(pointer, "object", "缺少必需对象 " + field);
            }
            requireObject(value, pointer, field);
            return value;
        }

        private JsonNode optionalObject(JsonNode parent, String field, String parentPointer) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) {
                return null;
            }
            String pointer = parentPointer + "/" + escape(field);
            requireObject(value, pointer, field);
            return value;
        }

        private void requireObject(JsonNode node, String pointer, String name) {
            if (node == null || !node.isObject()) {
                throw error(pointer, "object", JsonConfigBundle.nodeType(node),
                        name + " 必须是 JSON 对象");
            }
        }

        private void rejectUnknown(JsonNode node, Set<String> allowed, String pointer,
                                   String name) {
            var fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!allowed.contains(field)) {
                    String hint = "未知字段默认 fail-closed，不允许静默忽略";
                    if ("/".equals(pointer) && "napcat".equals(field)) {
                        hint = "旧 napcat 字段已迁移到 accounts.<alias>.oneBot";
                    } else if ("/".equals(pointer) && "accountId".equals(field)) {
                        hint = "旧顶层 accountId 已迁移到 accounts.<alias>.accountId";
                    } else if ("/".equals(pointer) && "summary".equals(field)) {
                        hint = "旧 summary 已迁移到 messageBoard.summaryDebounceMillis";
                    } else if ("/".equals(pointer) && "outbox".equals(field)) {
                        hint = "旧 outbox 已迁移到 messageBoard.outbox";
                    } else if ("/".equals(pointer) && "weighting".equals(field)) {
                        hint = "旧 weighting 已迁移到 messageBoard.weighting";
                    }
                    throw error(pointer + "/" + escape(field), "known field",
                            "unknown field", hint);
                }
            }
        }

        private String requiredText(JsonNode parent, String field, String parentPointer) {
            JsonNode value = parent.get(field);
            String pointer = parentPointer + "/" + escape(field);
            if (value == null || value.isNull()) {
                throw missing(pointer, "non-empty string", "缺少必需字符串 " + field);
            }
            if (!value.isTextual()) {
                throw error(pointer, "non-empty string", JsonConfigBundle.nodeType(value),
                        field + " 必须是字符串");
            }
            String text = value.asText().trim();
            if (text.isEmpty()) {
                throw error(pointer, "non-empty string", "empty string",
                        field + " 不能为空");
            }
            return text;
        }

        private Optional<String> optionalText(JsonNode parent, String field,
                                              String parentPointer) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            String pointer = parentPointer + "/" + escape(field);
            if (!value.isTextual()) {
                throw error(pointer, "string", JsonConfigBundle.nodeType(value),
                        field + " 必须是字符串");
            }
            String text = value.asText().trim();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }

        private int optionalInt(JsonNode parent, String field, String parentPointer,
                                int fallback) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            String pointer = parentPointer + "/" + escape(field);
            if (!value.isInt()) {
                throw error(pointer, "integer", JsonConfigBundle.nodeType(value),
                        field + " 必须是整数");
            }
            return value.asInt();
        }

        private long optionalLong(JsonNode parent, String field, String parentPointer,
                                  long fallback) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            String pointer = parentPointer + "/" + escape(field);
            if (!value.isIntegralNumber()) {
                throw error(pointer, "integer", JsonConfigBundle.nodeType(value),
                        field + " 必须是整数");
            }
            return value.asLong();
        }

        private boolean optionalBoolean(JsonNode parent, String field, String parentPointer,
                                        boolean fallback) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            String pointer = parentPointer + "/" + escape(field);
            if (!value.isBoolean()) {
                throw error(pointer, "boolean", JsonConfigBundle.nodeType(value),
                        field + " 必须是布尔值");
            }
            return value.asBoolean();
        }

        private boolean requiredBoolean(JsonNode parent, String field, String parentPointer) {
            JsonNode value = parent.get(field);
            String pointer = parentPointer + "/" + escape(field);
            if (value == null || value.isNull()) {
                throw missing(pointer, "boolean", "缺少必需布尔值 " + field);
            }
            if (!value.isBoolean()) {
                throw error(pointer, "boolean", JsonConfigBundle.nodeType(value),
                        field + " 必须是布尔值");
            }
            return value.asBoolean();
        }

        private Optional<Set<String>> optionalStringSet(JsonNode parent, String field,
                                                         String parentPointer) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            String pointer = parentPointer + "/" + escape(field);
            if (!value.isArray()) {
                throw error(pointer, "array of non-empty strings",
                        JsonConfigBundle.nodeType(value), field + " 必须是字符串数组");
            }
            List<String> values = new ArrayList<>();
            for (int index = 0; index < value.size(); index++) {
                JsonNode item = value.get(index);
                String itemPointer = pointer + "/" + index;
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw error(itemPointer, "non-empty string",
                            item.isTextual() ? "empty string"
                                    : JsonConfigBundle.nodeType(item),
                            field + " 不能包含空值");
                }
                values.add(item.asText().trim());
            }
            return Optional.of(Set.copyOf(new LinkedHashSet<>(values)));
        }

        private URI parseUri(String value, String pointer, Set<String> schemes) {
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme();
                if (scheme == null || !schemes.contains(scheme.toLowerCase())) {
                    throw error(pointer, String.join("|", schemes.stream().sorted().toList())
                                    + " URI", "string",
                            "URI 协议不合法；应使用 " + String.join("|",
                                    schemes.stream().sorted().toList()) + " URI 且带 host");
                }
                if (uri.getHost() == null) {
                    throw error(pointer, "URI with host", "string",
                            "URI 缺少 host");
                }
                if (uri.getUserInfo() != null) {
                    throw error(pointer, "URI without userinfo", "string",
                            "URI 不允许包含 userinfo");
                }
                return uri;
            } catch (URISyntaxException invalid) {
                throw error(pointer, "valid URI", "string",
                        "不是合法 URI；检查 scheme、host 和转义");
            }
        }

        private Path resolvePath(Path baseDir, String value, String pointer) {
            try {
                Path path = Path.of(value);
                return (path.isAbsolute() ? path : baseDir.resolve(path))
                        .toAbsolutePath().normalize();
            } catch (InvalidPathException invalid) {
                throw error(pointer, "valid filesystem path", "string",
                        "不是合法路径: " + value);
            }
        }

        private ConfigException missing(String pointer, String expected, String hint) {
            return error(pointer, expected, "missing", hint);
        }

        private ConfigException error(String pointer, String expected, String actual,
                                      String hint) {
            Path file = bundle.nearestSourceOf(pointer).orElse(bundle.baseDir());
            return ConfigException.invalid(file, pointer, expected, actual, hint);
        }

        private static String escape(String value) {
            return value.replace("~", "~0").replace("/", "~1");
        }
    }
}
