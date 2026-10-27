package io.mosire.iibot.app.bot;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.iibot.app.config.ConfigException;
import io.mosire.iibot.app.config.JsonConfigBundle;

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

/** Bot-first 配置装载器；严格拒绝未知字段，所有路径相对合并后的配置目录解析。 */
public final class BotConfigLoader {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "dataDir", "accountId", "napcat", "control", "inbox", "summary", "outbox",
            "weighting");
    private static final Set<String> NAPCAT_FIELDS = Set.of(
            "eventWebSocketUri", "httpBaseUri", "accessTokenEnv", "accountId",
            "privateTempGroupId", "suppressSelfMessages", "requestTimeoutSeconds",
            "maxTextLength");
    private static final Set<String> CONTROL_FIELDS = Set.of(
            "host", "port", "path", "bearerTokenEnv");
    private static final Set<String> INBOX_FIELDS = Set.of(
            "dataDir", "inboxId", "bindingLabel", "channels", "bindingTtlHours",
            "apiHost", "apiPort", "mcpHost", "mcpPort", "mcpPath",
            "heartbeatMillis", "claimSweepIntervalMillis");
    private static final Set<String> SUMMARY_FIELDS = Set.of(
            "debounceMillis", "maxMessagesPerDigest");
    private static final Set<String> OUTBOX_FIELDS = Set.of(
            "sendTimeoutSeconds", "minSendIntervalMillis");
    private static final Set<String> WEIGHTING_FIELDS = Set.of(
            "defaultPrivateSourceWeight", "defaultGroupSourceWeight",
            "defaultNotifyThreshold", "signalWeights", "keywordWeights",
            "aliases", "commandPrefixes", "maxMessagesPerDigest",
            "quietMillis", "cooldownMillis");

    private BotConfigLoader() {
    }

    public static BotConfig load(Path configFile) {
        if (configFile == null) {
            throw new ConfigException("--config 不能为空");
        }
        return load(List.of(configFile));
    }

    /**
     * 按顺序合并一个或多个配置源（文件或目录）后装载 Bot 配置。
     * 目录会读取直接子级的 *.json 文件并按文件名升序合并。
     */
    public static BotConfig load(List<Path> configSources) {
        JsonConfigBundle.Bundle bundle = JsonConfigBundle.load(configSources);
        JsonNode root = bundle.root();
        requireObject(root, "配置根");
        rejectUnknown(root, ROOT_FIELDS, "配置根");
        Path baseDir = bundle.baseDir();
        Path dataDir = resolvePath(baseDir, requiredText(root, "dataDir", "配置根"), "dataDir");
        String accountId = requiredText(root, "accountId", "配置根");
        BotConfig.NapCat napcat = parseNapcat(requiredObject(root, "napcat", "配置根"));
        BotConfig.Control control = parseControl(requiredObject(root, "control", "配置根"));
        BotConfig.Inbox inbox = parseInbox(requiredObject(root, "inbox", "配置根"), baseDir, dataDir);
        BotConfig.Summary summary = optionalObject(root, "summary", "配置根")
                .map(BotConfigLoader::parseSummary)
                .orElseGet(BotConfig.Summary::defaults);
        BotConfig.Outbox outbox = optionalObject(root, "outbox", "配置根")
                .map(BotConfigLoader::parseOutbox)
                .orElseGet(BotConfig.Outbox::defaults);
        BotConfig.Weighting weighting = optionalObject(root, "weighting", "配置根")
                .map(BotConfigLoader::parseWeighting)
                .orElseGet(BotConfig.Weighting::defaults);
        try {
            return new BotConfig(dataDir, accountId, napcat, control, inbox, summary,
                    outbox, weighting);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static BotConfig.NapCat parseNapcat(JsonNode node) {
        rejectUnknown(node, NAPCAT_FIELDS, "napcat");
        URI ws = parseUri(requiredText(node, "eventWebSocketUri", "napcat"),
                "napcat.eventWebSocketUri", Set.of("ws", "wss"));
        URI http = parseUri(requiredText(node, "httpBaseUri", "napcat"),
                "napcat.httpBaseUri", Set.of("http", "https"));
        Optional<String> tokenEnv = optionalText(node, "accessTokenEnv");
        Optional<String> accountId = optionalText(node, "accountId");
        Optional<String> tempGroup = optionalText(node, "privateTempGroupId");
        boolean suppressSelf = optionalBoolean(node, "suppressSelfMessages", true);
        int requestTimeout = optionalInt(node, "requestTimeoutSeconds", 10);
        int maxTextLength = optionalInt(node, "maxTextLength", 2000);
        try {
            return new BotConfig.NapCat(ws, http, tokenEnv, accountId, tempGroup,
                    suppressSelf, requestTimeout, maxTextLength);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static BotConfig.Control parseControl(JsonNode node) {
        rejectUnknown(node, CONTROL_FIELDS, "control");
        String host = optionalText(node, "host").orElse("127.0.0.1");
        int port = optionalInt(node, "port", 8765);
        String path = optionalText(node, "path").orElse("/mcp/bot");
        String tokenEnv = requiredText(node, "bearerTokenEnv", "control");
        try {
            return new BotConfig.Control(host, port, path, tokenEnv);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static BotConfig.Inbox parseInbox(JsonNode node, Path baseDir, Path dataDir) {
        rejectUnknown(node, INBOX_FIELDS, "inbox");
        Path inboxDir = optionalText(node, "dataDir")
                .map(value -> resolvePath(baseDir, value, "inbox.dataDir"))
                .orElseGet(() -> dataDir.resolve("inbox"));
        String inboxId = optionalText(node, "inboxId").orElse("iibot-bot");
        String bindingLabel = optionalText(node, "bindingLabel").orElse("dsh-bot");
        Set<String> channels = optionalStringSet(node, "channels").orElse(Set.of("qq"));
        long ttlHours = optionalLong(node, "bindingTtlHours", 24L);
        String apiHost = optionalText(node, "apiHost")
                .orElse(BotConfig.Inbox.DEFAULT_HOST);
        int apiPort = optionalInt(node, "apiPort", BotConfig.Inbox.DEFAULT_PORT);
        String mcpHost = optionalText(node, "mcpHost")
                .orElse(BotConfig.Inbox.DEFAULT_HOST);
        int mcpPort = optionalInt(node, "mcpPort", BotConfig.Inbox.DEFAULT_PORT);
        String mcpPath = optionalText(node, "mcpPath")
                .orElse(BotConfig.Inbox.DEFAULT_MCP_PATH);
        long heartbeatMillis = optionalLong(node, "heartbeatMillis",
                BotConfig.Inbox.DEFAULT_HEARTBEAT_MILLIS);
        long claimSweepMillis = optionalLong(node, "claimSweepIntervalMillis",
                BotConfig.Inbox.DEFAULT_CLAIM_SWEEP_INTERVAL_MILLIS);
        try {
            return new BotConfig.Inbox(inboxDir, inboxId, bindingLabel, channels, ttlHours,
                    apiHost, apiPort, mcpHost, mcpPort, mcpPath,
                    heartbeatMillis, claimSweepMillis);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static BotConfig.Summary parseSummary(JsonNode node) {
        rejectUnknown(node, SUMMARY_FIELDS, "summary");
        long debounce = optionalLong(node, "debounceMillis", 2_000L);
        int maxMessages = optionalInt(node, "maxMessagesPerDigest", 100);
        try {
            return new BotConfig.Summary(debounce, maxMessages);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static BotConfig.Outbox parseOutbox(JsonNode node) {
        rejectUnknown(node, OUTBOX_FIELDS, "outbox");
        int sendTimeout = optionalInt(node, "sendTimeoutSeconds", 15);
        int minInterval = optionalInt(node, "minSendIntervalMillis", 150);
        try {
            return new BotConfig.Outbox(sendTimeout, minInterval);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static BotConfig.Weighting parseWeighting(JsonNode node) {
        rejectUnknown(node, WEIGHTING_FIELDS, "weighting");
        int privateWeight = optionalInt(node, "defaultPrivateSourceWeight", 300);
        int groupWeight = optionalInt(node, "defaultGroupSourceWeight", 0);
        int threshold = optionalInt(node, "defaultNotifyThreshold", 100);
        int maxMessages = optionalInt(node, "maxMessagesPerDigest", 20);
        long quiet = optionalLong(node, "quietMillis", 180_000L);
        long cooldown = optionalLong(node, "cooldownMillis", 60_000L);
        Map<String, Integer> signals = optionalWeightMap(node, "signalWeights",
                Map.of("DM", 200, "MENTION", 200, "REPLY_TO_BOT", 180,
                        "NAME", 120, "COMMAND", 250));
        Map<String, Integer> keywords = optionalWeightMap(node, "keywordWeights", Map.of());
        Set<String> aliases = optionalStringSet(node, "aliases")
                .orElse(Set.of("贾维洛", "洛洛"));
        Set<String> commands = optionalStringSet(node, "commandPrefixes")
                .orElse(Set.of("/", "!"));
        try {
            return new BotConfig.Weighting(privateWeight, groupWeight, threshold,
                    signals, keywords, aliases, commands, maxMessages, quiet, cooldown);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException(invalid.getMessage());
        }
    }

    private static Map<String, Integer> optionalWeightMap(JsonNode node, String field,
                                                           Map<String, Integer> fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        requireObject(value, "weighting." + field);
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isIntegralNumber()) {
                throw new ConfigException("weighting." + field + " 权重必须是整数");
            }
            result.put(entry.getKey(), entry.getValue().intValue());
        });
        return Map.copyOf(result);
    }

    private static Optional<JsonNode> optionalObject(JsonNode root, String field, String location) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        requireObject(node, location + "." + field);
        return Optional.of(node);
    }

    private static JsonNode requiredObject(JsonNode root, String field, String location) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new ConfigException(location + " 缺少 " + field);
        }
        requireObject(node, location + "." + field);
        return node;
    }

    private static void requireObject(JsonNode node, String location) {
        if (node == null || !node.isObject()) {
            throw new ConfigException(location + " 必须是对象");
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String location) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new ConfigException(location + " 含未知字段: " + field);
            }
        });
    }

    private static String requiredText(JsonNode root, String field, String location) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ConfigException(location + " 缺少非空字符串 " + field);
        }
        return value.asText().trim();
    }

    private static Optional<String> optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new ConfigException(field + " 必须是字符串");
        }
        String text = value.asText().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static int optionalInt(JsonNode root, String field, int fallback) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isInt()) {
            throw new ConfigException(field + " 必须是整数");
        }
        return value.asInt();
    }

    private static long optionalLong(JsonNode root, String field, long fallback) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new ConfigException(field + " 必须是整数");
        }
        return value.asLong();
    }

    private static boolean optionalBoolean(JsonNode root, String field, boolean fallback) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new ConfigException(field + " 必须是布尔值");
        }
        return value.asBoolean();
    }

    private static Optional<Set<String>> optionalStringSet(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isArray()) {
            throw new ConfigException(field + " 必须是字符串数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new ConfigException(field + " 不能包含空值");
            }
            values.add(item.asText().trim());
        }
        return Optional.of(Set.copyOf(new LinkedHashSet<>(values)));
    }

    private static URI parseUri(String value, String name, Set<String> schemes) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !schemes.contains(scheme.toLowerCase())) {
                throw new ConfigException(name + " 协议不合法");
            }
            if (uri.getHost() == null) {
                throw new ConfigException(name + " 缺少 host");
            }
            return uri;
        } catch (URISyntaxException invalid) {
            throw new ConfigException(name + " 不是合法 URI");
        }
    }

    private static Path resolvePath(Path baseDir, String value, String field) {
        try {
            Path path = Path.of(value);
            return (path.isAbsolute() ? path : baseDir.resolve(path)).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            throw new ConfigException(field + " 不是合法路径");
        }
    }
}
