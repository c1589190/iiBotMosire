package io.mosire.iibot.app.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.app.mcp.ControlMcpConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 严格 JSON 配置装载器。
 *
 * <p><b>未知字段策略</b>：每一层对象都按显式白名单检查字段，出现未知字段立即抛
 * {@link ConfigException}。这是有意的 fail-closed：拼错的 {@code baseUrl} 不应该悄悄变成
 * 空值或默认值。顶层 JSON 必须是对象，不能是数组或标量。</p>
 *
 * <p><b>路径语义</b>：相对路径按配置文件所在目录解析，然后绝对化并归一；绝对路径原样保留。
 * {@code events.db}、{@code world/} 都由 {@code dataDir} 派生。</p>
 */
public final class AppConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> ROOT_FIELDS = Set.of(
            "dataDir", "publicGroupId", "maxTurnMinutes", "napcat", "players", "directors",
            "world", "llm", "dispatcher", "outbox", "scheduler", "control");
    private static final Set<String> NAPCAT_FIELDS = Set.of(
            "eventWebSocketUri", "httpBaseUri", "accessTokenEnv", "accountId",
            "suppressSelfMessages", "requestTimeoutSeconds", "maxTextLength");
    private static final Set<String> ACCOUNT_FIELDS = Set.of("playerId", "actorId", "qqId", "displayName");
    private static final Set<String> WORLD_FIELDS = Set.of("promptVersion", "factsFile");
    private static final Set<String> LLM_FIELDS = Set.of(
            "baseUrl", "model", "apiKeyEnv", "connectTimeoutSeconds", "readTimeoutSeconds",
            "echoReasoningContent");
    private static final Set<String> DISPATCHER_FIELDS = Set.of(
            "maxConcurrentSessions", "maxPendingPerUser", "dedupeCapacity");
    private static final Set<String> OUTBOX_FIELDS = Set.of("sendTimeoutSeconds");
    private static final Set<String> SCHEDULER_FIELDS = Set.of("autoFreezeIntervalSeconds");
    private static final Set<String> CONTROL_FIELDS = Set.of(
            "enabled", "host", "port", "path", "bearerTokenEnv", "maxRequestBytes");

    private AppConfigLoader() {
    }

    /**
     * 读取并校验配置文件。
     *
     * @param configFile 配置文件路径；可为相对路径，按进程工作目录解析
     * @return 已验证、路径已绝对化的配置
     * @throws ConfigException 文件不存在、JSON 非法、未知字段、缺字段或语义冲突
     */
    public static AppConfig load(Path configFile) {
        if (configFile == null) {
            throw new ConfigException("--config 不能为空");
        }
        Path file = configFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("配置文件不存在或不是普通文件: " + file);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (JsonProcessingException e) {
            // 不回显解析器片段：配置文件可能含敏感值；只给稳定的可操作错误。
            throw new ConfigException("配置文件不是合法 JSON: " + file);
        } catch (IOException e) {
            throw new ConfigException("读取配置文件失败: " + file);
        }
        requireObject(root, "配置根");
        rejectUnknown(root, ROOT_FIELDS, "配置根");

        Path baseDir = file.getParent() == null
                ? Path.of("").toAbsolutePath().normalize()
                : file.getParent().toAbsolutePath().normalize();

        Path dataDir = resolvePath(baseDir, requiredText(root, "dataDir", "配置根"), "dataDir");
        String publicGroupId = requiredText(root, "publicGroupId", "配置根");
        int maxTurnMinutes = requiredInt(root, "maxTurnMinutes", "配置根");

        AppConfig.NapCat napcat = parseNapcat(requiredObject(root, "napcat", "配置根"));
        List<AppConfig.Account> players = parseAccounts(requiredArray(root, "players", "配置根"), "players");
        List<AppConfig.Account> directors = parseAccounts(requiredArray(root, "directors", "配置根"), "directors");
        AppConfig.World world = parseWorld(requiredObject(root, "world", "配置根"), baseDir);
        Optional<AppConfig.Llm> llm = optionalObject(root, "llm", "配置根").map(AppConfigLoader::parseLlm);
        AppConfig.Dispatcher dispatcher = optionalObject(root, "dispatcher", "配置根")
                .map(AppConfigLoader::parseDispatcher)
                .orElseGet(AppConfig.Dispatcher::defaults);
        AppConfig.Outbox outbox = optionalObject(root, "outbox", "配置根")
                .map(AppConfigLoader::parseOutbox)
                .orElseGet(AppConfig.Outbox::defaults);
        AppConfig.Scheduler scheduler = optionalObject(root, "scheduler", "配置根")
                .map(AppConfigLoader::parseScheduler)
                .orElseGet(AppConfig.Scheduler::defaults);
        ControlMcpConfig control = optionalObject(root, "control", "配置根")
                .map(AppConfigLoader::parseControl)
                .orElseGet(ControlMcpConfig::disabled);

        return new AppConfig(dataDir, publicGroupId, maxTurnMinutes, napcat, players, directors,
                world, llm, dispatcher, outbox, scheduler, control);
    }

    private static AppConfig.NapCat parseNapcat(JsonNode node) {
        rejectUnknown(node, NAPCAT_FIELDS, "napcat");
        URI ws = parseUri(requiredText(node, "eventWebSocketUri", "napcat"), "napcat.eventWebSocketUri");
        URI http = parseUri(requiredText(node, "httpBaseUri", "napcat"), "napcat.httpBaseUri");
        Optional<String> tokenEnv = optionalText(node, "accessTokenEnv", "napcat");
        Optional<String> accountId = optionalText(node, "accountId", "napcat");
        boolean suppressSelf = optionalBoolean(node, "suppressSelfMessages", "napcat", true);
        int requestTimeout = optionalInt(node, "requestTimeoutSeconds", "napcat", 10);
        int maxTextLength = optionalInt(node, "maxTextLength", "napcat", 2_000);
        return new AppConfig.NapCat(ws, http, tokenEnv, accountId, suppressSelf, requestTimeout,
                maxTextLength);
    }

    private static List<AppConfig.Account> parseAccounts(JsonNode array, String field) {
        if (!array.isArray() || array.isEmpty()) {
            throw new ConfigException(field + " 不能为空数组");
        }
        List<AppConfig.Account> accounts = new ArrayList<>(array.size());
        int index = 0;
        for (JsonNode item : array) {
            String location = field + "[" + index + "]";
            requireObject(item, location);
            rejectUnknown(item, ACCOUNT_FIELDS, location);
            String playerId = optionalText(item, "playerId", location).orElse(null);
            String actorId = optionalText(item, "actorId", location).orElse(null);
            if (playerId == null && actorId == null) {
                throw new ConfigException(location + " 缺少 playerId");
            }
            if (playerId != null && actorId != null && !playerId.equals(actorId)) {
                throw new ConfigException(location + " 的 actorId 必须等于 playerId");
            }
            String qqId = requiredText(item, "qqId", location);
            String displayName = requiredText(item, "displayName", location);
            accounts.add(new AppConfig.Account(playerId == null ? actorId : playerId, qqId,
                    displayName));
            index++;
        }
        return List.copyOf(accounts);
    }

    private static AppConfig.World parseWorld(JsonNode node, Path baseDir) {
        rejectUnknown(node, WORLD_FIELDS, "world");
        String promptVersion = requiredText(node, "promptVersion", "world");
        Path factsFile = resolvePath(baseDir, requiredText(node, "factsFile", "world"),
                "world.factsFile");
        return new AppConfig.World(promptVersion, factsFile);
    }

    private static AppConfig.Llm parseLlm(JsonNode node) {
        rejectUnknown(node, LLM_FIELDS, "llm");
        String baseUrl = requiredText(node, "baseUrl", "llm");
        String model = requiredText(node, "model", "llm");
        Optional<String> apiKeyEnv = optionalText(node, "apiKeyEnv", "llm");
        int connect = optionalInt(node, "connectTimeoutSeconds", "llm", 10);
        int read = optionalInt(node, "readTimeoutSeconds", "llm", 60);
        boolean echo = optionalBoolean(node, "echoReasoningContent", "llm", false);
        return new AppConfig.Llm(baseUrl, model, apiKeyEnv, connect, read, echo);
    }

    private static AppConfig.Dispatcher parseDispatcher(JsonNode node) {
        rejectUnknown(node, DISPATCHER_FIELDS, "dispatcher");
        AppConfig.Dispatcher defaults = AppConfig.Dispatcher.defaults();
        return new AppConfig.Dispatcher(
                optionalInt(node, "maxConcurrentSessions", "dispatcher", defaults.maxConcurrentSessions()),
                optionalInt(node, "maxPendingPerUser", "dispatcher", defaults.maxPendingPerUser()),
                optionalInt(node, "dedupeCapacity", "dispatcher", defaults.dedupeCapacity()));
    }

    private static AppConfig.Outbox parseOutbox(JsonNode node) {
        rejectUnknown(node, OUTBOX_FIELDS, "outbox");
        return new AppConfig.Outbox(optionalInt(node, "sendTimeoutSeconds", "outbox",
                AppConfig.Outbox.defaults().sendTimeoutSeconds()));
    }

    private static AppConfig.Scheduler parseScheduler(JsonNode node) {
        rejectUnknown(node, SCHEDULER_FIELDS, "scheduler");
        return new AppConfig.Scheduler(optionalInt(node, "autoFreezeIntervalSeconds", "scheduler",
                AppConfig.Scheduler.defaults().autoFreezeIntervalSeconds()));
    }

    private static ControlMcpConfig parseControl(JsonNode node) {
        rejectUnknown(node, CONTROL_FIELDS, "control");
        boolean enabled = optionalBoolean(node, "enabled", "control", false);
        String host = optionalText(node, "host", "control")
                .orElse(ControlMcpConfig.DEFAULT_HOST);
        int port = optionalInt(node, "port", "control", 0);
        String path = optionalText(node, "path", "control")
                .orElse("/mcp/control");
        String tokenEnv = optionalText(node, "bearerTokenEnv", "control").orElse("");
        int maxBytes = optionalInt(node, "maxRequestBytes", "control",
                ControlMcpConfig.DEFAULT_MAX_REQUEST_BYTES);
        if (enabled && tokenEnv.isBlank()) {
            throw new ConfigException("control.enabled=true 时必须配置 control.bearerTokenEnv（token 只从环境变量读取）");
        }
        try {
            return new ControlMcpConfig(enabled, host, port, path, tokenEnv, maxBytes);
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException("control 配置非法: " + invalid.getMessage());
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw new ConfigException(location + " 缺少必填对象: " + field);
        }
        requireObject(node, location + "." + field);
        return node;
    }

    private static Optional<JsonNode> optionalObject(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        requireObject(node, location + "." + field);
        return Optional.of(node);
    }

    private static JsonNode requiredArray(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            throw new ConfigException(location + " 缺少必填数组: " + field);
        }
        return node;
    }

    private static String requiredText(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new ConfigException(location + " 缺少必填字符串: " + field);
        }
        return node.asText().strip();
    }

    private static Optional<String> optionalText(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual()) {
            throw new ConfigException(location + "." + field + " 必须是字符串");
        }
        return node.asText().isBlank() ? Optional.empty() : Optional.of(node.asText().strip());
    }

    private static int requiredInt(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new ConfigException(location + " 缺少必填整数: " + field);
        }
        return node.asInt();
    }

    private static int optionalInt(JsonNode parent, String field, String location, int defaultValue) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new ConfigException(location + "." + field + " 必须是整数");
        }
        return node.asInt();
    }

    private static boolean optionalBoolean(JsonNode parent, String field, String location,
                                           boolean defaultValue) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new ConfigException(location + "." + field + " 必须是布尔值");
        }
        return node.asBoolean();
    }

    private static void requireObject(JsonNode node, String location) {
        if (node == null || !node.isObject()) {
            throw new ConfigException(location + " 必须是 JSON 对象");
        }
    }

    private static void rejectUnknown(JsonNode object, Set<String> allowed, String location) {
        Set<String> unknown = new LinkedHashSet<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ConfigException(location + " 包含未知字段: " + String.join(", ", unknown));
        }
    }

    private static Path resolvePath(Path baseDir, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ConfigException(field + " 不能为空");
        }
        try {
            Path path = Path.of(raw);
            Path absolute = path.isAbsolute() ? path : baseDir.resolve(path);
            return absolute.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ConfigException(field + " 不是合法路径: " + safePathForError(raw));
        }
    }

    private static URI parseUri(String raw, String field) {
        try {
            return new URI(raw);
        } catch (URISyntaxException e) {
            throw new ConfigException(field + " 不是合法 URI");
        }
    }

    private static String safePathForError(String raw) {
        // 只回显长度与是否存在 NUL，避免把可能的敏感路径原样写进日志。
        return "<path length=" + raw.length() + ">";
    }
}
