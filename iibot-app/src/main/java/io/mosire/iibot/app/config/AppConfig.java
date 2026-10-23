package io.mosire.iibot.app.config;

import io.mosire.iibot.app.mcp.ControlMcpConfig;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 应用组合根的不可变配置快照。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li><b>未知字段显式拒绝</b>：装载器对每一层对象启用严格字段白名单，拼错的字段不会静默变成默认值；</li>
 *   <li><b>路径绝对化</b>：{@link #dataDir()} 与 {@link World#factsFile()} 在装载时按配置文件父目录解析并归一；</li>
 *   <li><b>身份只来自配置</b>：playerId/actorId 相同，QQ 号、玩家 ID、推演 ID 在整份配置内全局唯一；</li>
 *   <li><b>密钥零内嵌</b>：本类型只保存环境变量名（{@code accessTokenEnv}/{@code apiKeyEnv}），
 *       真实值由 {@link SecretProvider} 在装配时读取，不进 record 分量，也不进 {@link #toString()}。</li>
 * </ul>
 *
 * @param dataDir        数据目录（绝对、已归一）；events.db、world/ 都在其下
 * @param publicGroupId  公共群标识；本版群聊不进入 Agent，仅保留发布边界
 * @param maxTurnMinutes 单回合最大分钟数
 * @param napcat         NapCat 接入配置
 * @param players        玩家身份映射
 * @param directors      推演/管理身份映射
 * @param world          世界提示版本与事实文件
 * @param llm            LLM 配置；缺省时真实启动 fail-closed，Fake 测试可注入 LlmClient
 * @param dispatcher     入站 mailbox 容量配置
 * @param outbox         发送等待上限配置
 * @param scheduler      自动冻结调度配置
 * @param control       外部控制 MCP 配置；缺省关闭，token 只保存环境变量名
 */
public record AppConfig(
        Path dataDir,
        String publicGroupId,
        int maxTurnMinutes,
        NapCat napcat,
        List<Account> players,
        List<Account> directors,
        World world,
        Optional<Llm> llm,
        Dispatcher dispatcher,
        Outbox outbox,
        Scheduler scheduler,
        ControlMcpConfig control) {

    public AppConfig {
        dataDir = requireAbsolutePath(dataDir, "dataDir");
        publicGroupId = requireText(publicGroupId, "publicGroupId");
        if (maxTurnMinutes <= 0) {
            throw new ConfigException("maxTurnMinutes 必须为正整数");
        }
        napcat = Objects.requireNonNull(napcat, "napcat");
        players = copyAccounts(players, "players");
        directors = copyAccounts(directors, "directors");
        if (directors.isEmpty()) {
            throw new ConfigException("directors 不能为空（至少需要一个推演身份执行开局/冻结/推演）");
        }
        world = Objects.requireNonNull(world, "world");
        llm = llm == null ? Optional.empty() : llm;
        dispatcher = dispatcher == null ? Dispatcher.defaults() : dispatcher;
        outbox = outbox == null ? Outbox.defaults() : outbox;
        scheduler = scheduler == null ? Scheduler.defaults() : scheduler;
        control = control == null ? ControlMcpConfig.disabled() : control;
        validateUniqueIdentities(players, directors);
    }

    /** 玩家或推演的稳定身份映射。actorId 与 playerId 相同，避免两套身份键漂移。 */
    public record Account(String playerId, String qqId, String displayName) {
        public Account {
            playerId = requireText(playerId, "playerId");
            qqId = requireText(qqId, "qqId");
            displayName = requireText(displayName, "displayName");
            if (qqId.chars().anyMatch(Character::isWhitespace)) {
                throw new ConfigException("qqId 不得含空白: '" + qqId + "'");
            }
        }

        /** 领域 Actor ID 与 playerId 等值。 */
        public String actorId() {
            return playerId;
        }

        @Override
        public String toString() {
            return "Account{playerId=" + playerId + ", qqId=<configured>, displayName=" + displayName + "}";
        }
    }

    /** NapCat / OneBot 11 接入配置；不含真实 token。 */
    public record NapCat(
            URI eventWebSocketUri,
            URI httpBaseUri,
            Optional<String> accessTokenEnv,
            Optional<String> accountId,
            boolean suppressSelfMessages,
            int requestTimeoutSeconds,
            int maxTextLength) {

        public NapCat {
            eventWebSocketUri = requireUri(eventWebSocketUri, "napcat.eventWebSocketUri", "ws", "wss");
            httpBaseUri = requireUri(httpBaseUri, "napcat.httpBaseUri", "http", "https");
            accessTokenEnv = normalizeOptional(accessTokenEnv, "napcat.accessTokenEnv");
            accountId = normalizeOptional(accountId, "napcat.accountId");
            if (requestTimeoutSeconds <= 0) {
                throw new ConfigException("napcat.requestTimeoutSeconds 必须为正整数");
            }
            if (maxTextLength <= 0) {
                throw new ConfigException("napcat.maxTextLength 必须为正整数");
            }
        }

        @Override
        public String toString() {
            return "NapCat{eventWebSocketUri=" + safeUri(eventWebSocketUri)
                    + ", httpBaseUri=" + safeUri(httpBaseUri)
                    + ", accessTokenEnv=" + accessTokenEnv.map(name -> "<env:" + name + ">")
                            .orElse("<absent>")
                    + ", accountId=" + accountId.orElse("<absent>")
                    + ", suppressSelfMessages=" + suppressSelfMessages
                    + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                    + ", maxTextLength=" + maxTextLength + '}';
        }
    }

    /** 世界配置。factsFile 必须是绝对路径（装载器已相对配置目录解析）。 */
    public record World(String promptVersion, Path factsFile) {
        public World {
            promptVersion = requireText(promptVersion, "world.promptVersion");
            factsFile = requireAbsolutePath(factsFile, "world.factsFile");
        }
    }

    /** OpenAI 兼容 LLM 配置；密钥只引用环境变量名。 */
    public record Llm(
            String baseUrl,
            String model,
            Optional<String> apiKeyEnv,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            boolean echoReasoningContent) {

        public Llm {
            baseUrl = requireText(baseUrl, "llm.baseUrl");
            model = requireText(model, "llm.model");
            apiKeyEnv = normalizeOptional(apiKeyEnv, "llm.apiKeyEnv");
            if (connectTimeoutSeconds <= 0) {
                throw new ConfigException("llm.connectTimeoutSeconds 必须为正整数");
            }
            if (readTimeoutSeconds <= 0) {
                throw new ConfigException("llm.readTimeoutSeconds 必须为正整数");
            }
            URI uri;
            try {
                uri = requireUri(URI.create(baseUrl), "llm.baseUrl", "http", "https");
            } catch (IllegalArgumentException invalid) {
                throw new ConfigException("llm.baseUrl 不是合法 URI");
            }
            if (uri.getRawPath() != null && uri.getRawPath().endsWith("/chat/completions")) {
                throw new ConfigException("llm.baseUrl 必须是 API 根，不能直接写 /chat/completions");
            }
        }

        @Override
        public String toString() {
            return "Llm{baseUrl=" + baseUrl + ", model=" + model
                    + ", apiKeyEnv=" + apiKeyEnv.map(name -> "<env:" + name + ">").orElse("<absent>")
                    + ", connectTimeoutSeconds=" + connectTimeoutSeconds
                    + ", readTimeoutSeconds=" + readTimeoutSeconds
                    + ", echoReasoningContent=" + echoReasoningContent + '}';
        }
    }

    /** 入站 mailbox 配置；队列有界，满时记录并拒绝，不悄悄积压。 */
    public record Dispatcher(int maxConcurrentSessions, int maxPendingPerUser, int dedupeCapacity) {
        public Dispatcher {
            if (maxConcurrentSessions <= 0) {
                throw new ConfigException("dispatcher.maxConcurrentSessions 必须为正整数");
            }
            if (maxPendingPerUser <= 0) {
                throw new ConfigException("dispatcher.maxPendingPerUser 必须为正整数");
            }
            if (dedupeCapacity <= 0) {
                throw new ConfigException("dispatcher.dedupeCapacity 必须为正整数");
            }
        }

        public static Dispatcher defaults() {
            return new Dispatcher(64, 16, 10_000);
        }
    }

    /** 发送等待上限；超时进入 UNKNOWN，绝不自动重发。 */
    public record Outbox(int sendTimeoutSeconds) {
        public Outbox {
            if (sendTimeoutSeconds <= 0) {
                throw new ConfigException("outbox.sendTimeoutSeconds 必须为正整数");
            }
        }

        public static Outbox defaults() {
            return new Outbox(15);
        }
    }

    /** 自动冻结调度配置。 */
    public record Scheduler(int autoFreezeIntervalSeconds) {
        public Scheduler {
            if (autoFreezeIntervalSeconds <= 0) {
                throw new ConfigException("scheduler.autoFreezeIntervalSeconds 必须为正整数");
            }
        }

        public static Scheduler defaults() {
            return new Scheduler(30);
        }
    }

    /** 所有已映射 QQ 的只读视图，便于装配身份解析器。 */
    public Map<String, Account> accountsByQq() {
        Map<String, Account> result = new LinkedHashMap<>();
        directors.forEach(account -> result.put(account.qqId(), account));
        players.forEach(account -> result.put(account.qqId(), account));
        return Map.copyOf(result);
    }

    /** 玩家账号列表。 */
    public List<Account> playerAccounts() {
        return players;
    }

    @Override
    public String toString() {
        return "AppConfig{dataDir=" + dataDir
                + ", publicGroupId=" + publicGroupId
                + ", maxTurnMinutes=" + maxTurnMinutes
                + ", napcat=" + napcat
                + ", players=" + players
                + ", directors=" + directors
                + ", world=" + world
                + ", llm=" + llm.map(Object::toString).orElse("<absent>")
                + ", dispatcher=" + dispatcher
                + ", outbox=" + outbox
                + ", scheduler=" + scheduler
                + ", control=" + control + '}';
    }

    private static List<Account> copyAccounts(List<Account> accounts, String field) {
        if (accounts == null) {
            throw new ConfigException(field + " 不能为空");
        }
        List<Account> copy = new ArrayList<>(accounts.size());
        for (Account account : accounts) {
            if (account == null) {
                throw new ConfigException(field + " 不能包含 null");
            }
            copy.add(account);
        }
        return List.copyOf(copy);
    }

    private static void validateUniqueIdentities(List<Account> players, List<Account> directors) {
        Set<String> qqIds = new HashSet<>();
        Set<String> actorIds = new HashSet<>();
        for (Account account : concat(players, directors)) {
            if (!qqIds.add(account.qqId())) {
                throw new ConfigException("重复 QQ 账号: " + account.qqId());
            }
            // actorId 与 playerId 相同；这里按 actorId 口径明确报错。
            if (!actorIds.add(account.actorId())) {
                throw new ConfigException("重复 actor/playerId: " + account.actorId());
            }
        }
    }

    private static List<Account> concat(List<Account> first, List<Account> second) {
        List<Account> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigException(field + " 不能为空");
        }
        return value.strip();
    }

    private static Path requireAbsolutePath(Path path, String field) {
        Objects.requireNonNull(path, field);
        if (!path.isAbsolute()) {
            throw new ConfigException(field + " 必须是绝对路径");
        }
        Path normalized = path.normalize();
        if (!normalized.equals(path)) {
            throw new ConfigException(field + " 必须已归一（不接受 '.'/'..' 或重复分隔符）: " + path);
        }
        return normalized;
    }

    private static URI requireUri(URI uri, String field, String... schemes) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new ConfigException(field + " 必须是绝对 URI");
        }
        for (String scheme : schemes) {
            if (scheme.equalsIgnoreCase(uri.getScheme())) {
                return uri;
            }
        }
        throw new ConfigException(field + " 的 scheme 不受支持: " + uri.getScheme());
    }

    private static Optional<String> normalizeOptional(Optional<String> value, String field) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String text = value.get();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (text.chars().anyMatch(Character::isWhitespace)) {
            throw new ConfigException(field + " 不得含空白");
        }
        return Optional.of(text.strip());
    }

    private static String safeUri(URI uri) {
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
    }
}
