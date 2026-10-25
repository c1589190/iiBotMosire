package io.mosire.iibot.app.config;

import io.mosire.iibot.app.mcp.ControlMcpConfig;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
 *   <li><b>路由切换需重启</b>：v2-D 只支持修改本地配置并重启；当前不提供运行时热切换。</li>
 * </ul>
 *
 * @param dataDir        数据目录（绝对、已归一）；events.db、world/ 都在其下
 * @param publicGroupId  公共群标识；本版群聊不进入 Agent，仅保留发布边界
 * @param maxTurnMinutes 单回合最大分钟数
 * @param napcat         NapCat 接入配置
 * @param players        玩家身份映射
 * @param directors      推演/管理身份映射
 * @param world          世界提示版本与事实文件
 * @param llm            default LLM 配置；缺省时真实启动 fail-closed，Fake 测试可注入 LlmClient
 * @param llmRoutes      可选命名 LLM 路由覆盖；键为安全 route 名，值 schema 与 {@code llm} 相同
 * @param agentRoutes    可选 Agent 到 route 的选择；未配置的 Agent 回退 {@code default}
 * @param dispatcher     入站 mailbox 容量配置
 * @param outbox         发送等待上限配置
 * @param scheduler      自动冻结调度配置
 * @param control       外部控制 MCP 配置；缺省关闭，token 只保存环境变量名
 * @param inbox          InboxMosire Mode A 配置；缺省关闭，启用时数据目录显式持久化
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
        Optional<Map<String, Llm>> llmRoutes,
        Optional<AgentRoutes> agentRoutes,
        Dispatcher dispatcher,
        Outbox outbox,
        Scheduler scheduler,
        ControlMcpConfig control,
        Inbox inbox) {

    /** 现有 {@code llm} 配置对应的保留默认路由名。 */
    public static final String DEFAULT_LLM_ROUTE = "default";
    /** Agent 路由键。 */
    public static final String AGENT_SECRETARY = "secretary";
    public static final String AGENT_ADJUDICATOR = "adjudicator";
    public static final String AGENT_GM_AIDE = "gmAide";

    private static final Pattern ROUTE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern INBOX_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

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
        llmRoutes = normalizeLlmRoutes(llmRoutes);
        agentRoutes = agentRoutes == null ? Optional.empty() : agentRoutes;
        validateAgentRoutes(agentRoutes, llmRoutes);
        dispatcher = dispatcher == null ? Dispatcher.defaults() : dispatcher;
        outbox = outbox == null ? Outbox.defaults() : outbox;
        scheduler = scheduler == null ? Scheduler.defaults() : scheduler;
        control = control == null ? ControlMcpConfig.disabled() : control;
        inbox = inbox == null ? Inbox.disabled(dataDir) : inbox;
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
            Optional<String> privateTempGroupId,
            boolean suppressSelfMessages,
            int requestTimeoutSeconds,
            int maxTextLength) {

        public NapCat {
            eventWebSocketUri = requireUri(eventWebSocketUri, "napcat.eventWebSocketUri", "ws", "wss");
            httpBaseUri = requireUri(httpBaseUri, "napcat.httpBaseUri", "http", "https");
            accessTokenEnv = normalizeOptional(accessTokenEnv, "napcat.accessTokenEnv");
            accountId = normalizeOptional(accountId, "napcat.accountId");
            privateTempGroupId = normalizeOptional(privateTempGroupId,
                    "napcat.privateTempGroupId");
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
                    + ", privateTempGroupId=" + privateTempGroupId.orElse("<absent>")
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

    /**
     * Agent 到命名 LLM 路由的选择。三个字段都可省略；loader 会把省略项落为
     * {@link #DEFAULT_LLM_ROUTE}，因此运行时不存在 null 或隐式空路由。
     */
    public record AgentRoutes(String secretary, String adjudicator, String gmAide) {
        public AgentRoutes {
            secretary = normalizeRouteName(secretary, "agentRoutes.secretary");
            adjudicator = normalizeRouteName(adjudicator, "agentRoutes.adjudicator");
            gmAide = normalizeRouteName(gmAide, "agentRoutes.gmAide");
        }

        public static AgentRoutes defaults() {
            return new AgentRoutes(DEFAULT_LLM_ROUTE, DEFAULT_LLM_ROUTE, DEFAULT_LLM_ROUTE);
        }

        /** 返回给定 Agent 键对应的 route 名；未知键 fail-closed。 */
        public String routeFor(String agent) {
            if (agent == null || agent.isBlank()) {
                throw new IllegalArgumentException("agent 不能为空");
            }
            return switch (agent) {
                case AGENT_SECRETARY -> secretary;
                case AGENT_ADJUDICATOR -> adjudicator;
                case AGENT_GM_AIDE -> gmAide;
                default -> throw new IllegalArgumentException("未知 agent route 键: " + agent);
            };
        }

        @Override
        public String toString() {
            return "AgentRoutes{secretary=" + secretary
                    + ", adjudicator=" + adjudicator
                    + ", gmAide=" + gmAide + '}';
        }
    }

    /**
     * InboxMosire Mode A 配置。
     *
     * <p>整个配置块缺省时关闭。启用后 embedded server 使用显式持久 {@code dataDir}
     * （缺省为 app {@code dataDir/inbox}）；程序 token、wake token 与不含 raw token
     * 的 BindingDescriptor 都写入该目录。{@code channels} 为空集表示订阅全部 channel。</p>
     */
    public record Inbox(
            boolean enabled,
            Path dataDir,
            String inboxId,
            String bindingLabel,
            Set<String> channels,
            long pollIntervalMillis,
            long bindingTtlHours) {

        public static final String DEFAULT_INBOX_ID = "iibot-app";
        public static final String DEFAULT_BINDING_LABEL = "dsh-simulator-agent";
        public static final long DEFAULT_POLL_INTERVAL_MILLIS = 1_000L;
        public static final long DEFAULT_BINDING_TTL_HOURS = 24L;
        public static final Set<String> DEFAULT_CHANNELS =
                Set.of("ticket", "publication-review");

        public Inbox {
            dataDir = requireAbsolutePath(dataDir, "inbox.dataDir");
            inboxId = requireInboxId(inboxId, "inbox.inboxId");
            bindingLabel = requireBoundedText(bindingLabel, "inbox.bindingLabel", 200);
            channels = channels == null
                    ? DEFAULT_CHANNELS : Set.copyOf(new java.util.LinkedHashSet<>(channels));
            if (channels.stream().anyMatch(value -> value == null || !INBOX_ID.matcher(value).matches())) {
                throw new ConfigException(
                        "inbox.channels 每项必须为 1..64 位 [A-Za-z0-9._-]");
            }
            if (pollIntervalMillis <= 0 || pollIntervalMillis > 3_600_000L) {
                throw new ConfigException(
                        "inbox.pollIntervalMillis 必须在 1..3600000 范围内");
            }
            if (bindingTtlHours <= 0 || bindingTtlHours > 8_760L) {
                throw new ConfigException("inbox.bindingTtlHours 必须在 1..8760 范围内");
            }
        }

        public static Inbox disabled(Path appDataDir) {
            return new Inbox(false, appDataDir.resolve("inbox"), DEFAULT_INBOX_ID,
                    DEFAULT_BINDING_LABEL, DEFAULT_CHANNELS,
                    DEFAULT_POLL_INTERVAL_MILLIS, DEFAULT_BINDING_TTL_HOURS);
        }

        @Override
        public String toString() {
            return "Inbox{enabled=" + enabled
                    + ", dataDir=" + dataDir
                    + ", inboxId=" + inboxId
                    + ", bindingLabel=" + bindingLabel
                    + ", channels=" + channels
                    + ", pollIntervalMillis=" + pollIntervalMillis
                    + ", bindingTtlHours=" + bindingTtlHours + '}';
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

    /**
     * 出站节奏与等待上限。
     *
     * @param sendTimeoutSeconds 单条逻辑消息等待回执上限
     * @param minSendIntervalMillis 两条逻辑消息之间的最小间隔；生产范围 100..200ms
     */
    public record Outbox(int sendTimeoutSeconds, int minSendIntervalMillis) {
        public Outbox {
            if (sendTimeoutSeconds <= 0) {
                throw new ConfigException("outbox.sendTimeoutSeconds 必须为正整数");
            }
            if (minSendIntervalMillis < 100 || minSendIntervalMillis > 200) {
                throw new ConfigException(
                        "outbox.minSendIntervalMillis 必须在 100..200 之间");
            }
        }

        public static Outbox defaults() {
            return new Outbox(15, 150);
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

    /**
     * 返回包含保留默认路由的只读路由配置视图。路由名稳定可审计，不包含任何密钥值。
     */
    public Map<String, Llm> effectiveLlmRoutes() {
        Map<String, Llm> result = new LinkedHashMap<>();
        llm.ifPresent(value -> result.put(DEFAULT_LLM_ROUTE, value));
        llmRoutes.ifPresent(result::putAll);
        return Collections.unmodifiableMap(result);
    }

    /** 解析 Agent 使用的 route 名；未配置 agentRoutes 时恒为 {@code default}。 */
    public String agentRoute(String agent) {
        return agentRoutes.orElseGet(AgentRoutes::defaults).routeFor(agent);
    }

    /** 按 route 名读取配置；未定义时 fail-closed。 */
    public Llm llmForRoute(String routeName) {
        String normalized = normalizeRouteName(routeName, "route");
        if (DEFAULT_LLM_ROUTE.equals(normalized)) {
            return llm.orElseThrow(() -> new ConfigException(
                    "默认 LLM route 未配置：缺少 llm 配置块"));
        }
        return llmRoutes.flatMap(routes -> Optional.ofNullable(routes.get(normalized)))
                .orElseThrow(() -> new ConfigException("LLM route 未定义: " + normalized));
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
                + ", llmRoutes=" + llmRoutes.map(AppConfig::routeNames)
                        .orElse("<absent>")
                + ", agentRoutes=" + agentRoutes.map(Object::toString).orElse("<default>")
                + ", dispatcher=" + dispatcher
                + ", outbox=" + outbox
                + ", scheduler=" + scheduler
                + ", control=" + control
                + ", inbox=" + inbox + '}';
    }

    private static Optional<Map<String, Llm>> normalizeLlmRoutes(
            Optional<Map<String, Llm>> routes) {
        if (routes == null || routes.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Llm> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Llm> entry : routes.orElseThrow().entrySet()) {
            String name = normalizeRouteName(entry.getKey(), "llmRoutes route 名");
            if (DEFAULT_LLM_ROUTE.equals(name)) {
                throw new ConfigException(
                        "llmRoutes 不得重复定义保留路由 " + DEFAULT_LLM_ROUTE);
            }
            if (entry.getValue() == null) {
                throw new ConfigException("llmRoutes." + name + " 不能为 null");
            }
            copy.put(name, entry.getValue());
        }
        return Optional.of(Collections.unmodifiableMap(copy));
    }

    private static void validateAgentRoutes(Optional<AgentRoutes> routes,
                                            Optional<Map<String, Llm>> llmRoutes) {
        if (routes.isEmpty()) {
            return;
        }
        AgentRoutes selected = routes.orElseThrow();
        for (String agent : List.of(AGENT_SECRETARY, AGENT_ADJUDICATOR, AGENT_GM_AIDE)) {
            String route = selected.routeFor(agent);
            if (!DEFAULT_LLM_ROUTE.equals(route)
                    && (llmRoutes.isEmpty() || !llmRoutes.orElseThrow().containsKey(route))) {
                throw new ConfigException("agentRoutes." + agent
                        + " 引用了未定义的 LLM route: " + route);
            }
        }
    }

    private static String normalizeRouteName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigException(field + " 不能为空");
        }
        String name = value.strip();
        if (!ROUTE_NAME.matcher(name).matches()) {
            throw new ConfigException(field + " 必须是安全 route 名（[A-Za-z0-9_.-]+）");
        }
        return name;
    }

    private static String routeNames(Map<String, Llm> routes) {
        return routes.keySet().stream().sorted().toList().toString();
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

    private static String requireInboxId(String value, String field) {
        if (value == null || !INBOX_ID.matcher(value).matches()) {
            throw new ConfigException(field + " 必须为 1..64 位 [A-Za-z0-9._-]");
        }
        return value;
    }

    private static String requireBoundedText(String value, String field, int maxLength) {
        String normalized = requireText(value, field);
        if (normalized.length() > maxLength) {
            throw new ConfigException(field + " 超过长度上限 " + maxLength);
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ConfigException(field + " 不得含控制字符");
        }
        return normalized;
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
