package io.mosire.iibot.app.runtime;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.LlmClientFactory;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.PostDistributor;
import io.mosire.iibot.app.delivery.TurnResultDistributor;
import io.mosire.iibot.app.dispatch.AckPolicy;
import io.mosire.iibot.app.dispatch.InboundDispatcher;
import io.mosire.iibot.app.event.AgentLibEventSink;
import io.mosire.iibot.app.event.SqliteEventReadPort;
import io.mosire.iibot.app.event.TaskflowSnapshotStore;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.publication.JsonPostStore;
import io.mosire.iibot.app.world.WorldDocs;
import io.mosire.iibot.app.world.WorldFactsLoader;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.napcat.NapCatAdapter;
import io.mosire.iibot.connect.napcat.NapCatConfig;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.agent.AdjudicationService;
import io.mosire.iibot.taskflow.agent.GmAideAgent;
import io.mosire.iibot.taskflow.agent.InteractionTaskflowService;
import io.mosire.iibot.taskflow.agent.LlmBriefingService;
import io.mosire.iibot.taskflow.agent.MappedIdentity;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.event.EventSink;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 组合根装配器：把配置、事件审计、领域引擎、Agent、Docs 与消息接入串成一个可启动运行时。
 *
 * <p>启动先解析配置/密钥并打开事件存储、只读事件端口与 Taskflow 快照存储。存在
 * {@code dataDir/taskflow-state.json} 时严格校验事件库最大 seq 与快照 lastEventSeq 一致，
 * 恢复完整领域状态并核对配置身份；不存在快照时只允许空事件库执行 fresh bootstrap。
 * fresh 路径先安装提交监听器，再注册身份与装载事实，因此每个成功写命令都会原子落盘快照。
 * 任何恢复不一致都 fail-closed，不启动 QQ。</p>
 *
 * <p>真实 NapCat/LLM 只在未注入 Fake 时构造；构造器本身不发网。重启恢复本身不调用
 * LLM，也不重新注册身份或重复装载初始事实。</p>
 */
public final class AppBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(AppBootstrap.class);

    private AppBootstrap() {
    }

    /** 生产装配：环境变量密钥、真实 LLM 与 NapCat adapter。 */
    public static AppRuntime build(AppConfig config) {
        return build(config, BootstrapOptions.production(SecretProvider.environment()));
    }

    /** 可注入装配；测试用 Fake LLM/adapter 时不会连接真实 NapCat 或 LLM。 */
    public static AppRuntime build(AppConfig config, BootstrapOptions options) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(options, "options");
        SecretProvider secrets = options.secrets();
        Clock clock = options.clock();

        // 1) 解析已知密钥值（只用于事件脱敏兜底，不打印）。
        List<String> knownSecrets = new ArrayList<>();
        String napcatToken = resolveNapcatToken(config.napcat(), secrets);
        if (napcatToken != null) {
            knownSecrets.add(napcatToken);
        }
        config.effectiveLlmRoutes().values().stream()
                .flatMap(route -> route.apiKeyEnv().stream())
                .flatMap(env -> secrets.lookup(env).stream())
                .filter(value -> !value.isBlank())
                .forEach(knownSecrets::add);
        Map<String, LlmClient> llmClients = resolveLlmClients(config, options, secrets);
        LlmClient defaultLlm = llmClients.get(AppConfig.DEFAULT_LLM_ROUTE);
        String secretaryRoute = config.agentRoute(AppConfig.AGENT_SECRETARY);
        String adjudicatorRoute = config.agentRoute(AppConfig.AGENT_ADJUDICATOR);
        String gmAideRoute = config.agentRoute(AppConfig.AGENT_GM_AIDE);
        LlmClient secretaryLlm = routeClient(llmClients, secretaryRoute, defaultLlm);
        LlmClient adjudicatorLlm = routeClient(llmClients, adjudicatorRoute, defaultLlm);
        LlmClient gmAideLlm = routeClient(llmClients, gmAideRoute, defaultLlm);
        config.effectiveLlmRoutes().forEach((route, routeConfig) ->
                LOG.info("LLM route={} model={}", route, routeConfig.model()));
        LOG.info("Agent route 装配 secretary={} adjudicator={} gmAide={}",
                secretaryRoute, adjudicatorRoute, gmAideRoute);

        Path dataDir = config.dataDir();
        Path dbFile = dataDir.resolve("events.db");
        AgentLibEventSink eventSink = null;
        SqliteEventReadPort eventReadPort = null;
        WorldDocs worldDocs = null;
        InboundDispatcher dispatcher = null;
        AppRuntime runtime = null;
        try {
            ensureDirectory(dataDir);
            eventSink = new AgentLibEventSink(dbFile, knownSecrets);
            // 读写两个 SQLite 连接都先建立；即使不启用 MCP，也为控制面保留只读端口。
            eventReadPort = new SqliteEventReadPort(dbFile);
            TaskflowSnapshotStore snapshotStore = new TaskflowSnapshotStore(dataDir);
            PostService postService = new PostService(new JsonPostStore(dataDir), clock);

            Optional<TaskflowStateSnapshot> snapshot = snapshotStore.load();
            long lastStoredSeq = eventReadPort.lastStoredSeq();
            TaskflowEngine engine;
            Actor bootstrapDirector;
            boolean recovered;
            if (snapshot.isPresent()) {
                TaskflowStateSnapshot state = snapshot.orElseThrow();
                if (lastStoredSeq != state.lastEventSeq()) {
                    throw new IllegalStateException("RECOVERY_STATE_MISMATCH snapshotSeq="
                            + state.lastEventSeq() + " eventSeq=" + lastStoredSeq);
                }
                try {
                    engine = TaskflowEngine.restore(clock, eventSink,
                            config.world().promptVersion(), WorldDelta.DEFAULT_ALLOWED_TYPES,
                            64, 200_000, 8_192, state);
                } catch (IllegalArgumentException rejected) {
                    String detail = rejected.getMessage() == null ? "" : rejected.getMessage();
                    if (detail.contains("promptVersion")) {
                        throw new IllegalStateException("RECOVERY_PROMPT_VERSION_MISMATCH expected="
                                + config.world().promptVersion() + " snapshot="
                                + state.promptVersion());
                    }
                    throw new IllegalStateException("RECOVERY_SNAPSHOT_REJECTED " + detail);
                }
                requireConfiguredIdentities(engine, config);
                engine.setStateCommitListener((eng, result) ->
                        snapshotStore.save(eng.exportState()));
                bootstrapDirector = new Actor(config.directors().get(0).actorId(),
                        ActorRole.DIRECTOR);
                recovered = true;
                logRecovered(engine, state, lastStoredSeq);
            } else {
                if (lastStoredSeq > 0) {
                    throw new IllegalStateException("RECOVERY_SNAPSHOT_MISSING eventSeq="
                            + lastStoredSeq + " path=" + snapshotStore.path()
                            + "：事件库非空但状态快照缺失，拒绝静默重建");
                }
                // fresh bootstrap 才读取初始事实文件；恢复路径不依赖该文件。
                List<WorldFact> initialFacts = WorldFactsLoader.load(config.world().factsFile());
                engine = new TaskflowEngine(clock, eventSink,
                        config.world().promptVersion(), WorldDelta.DEFAULT_ALLOWED_TYPES,
                        64, 200_000, 8_192);
                bootstrapDirector = registerIdentities(engine, config);
                CommandResult factsLoaded = engine.loadWorldFacts(bootstrapDirector,
                        engine.status().worldVersion(), initialFacts,
                        engine.status().engineRevision(), "bootstrap-world-facts-v1");
                requireOk(factsLoaded, "装载世界事实");
                // 只有整套 fresh bootstrap 成功后才安装 listener 并落第一份快照：
                // 若 bootstrap 中途失败，事件库非空但快照缺失，下次启动会 fail-closed，
                // 不会把半初始化的身份/世界静默当成可恢复状态。
                engine.setStateCommitListener((eng, result) ->
                        snapshotStore.save(eng.exportState()));
                snapshotStore.save(engine.exportState());
                recovered = false;
                LOG.info("Taskflow fresh bootstrap 完成 recovery=false worldVersion={} "
                                + "eventSeq={} path={}",
                        engine.status().worldVersion(), engine.exportState().lastEventSeq(),
                        snapshotStore.path());
            }

            // 所有后续消费者都读取引擎动态事实，而不是启动时的静态列表。
            SecretaryWorkspaceStore secretaryWorkspaceStore = SecretaryWorkspaceStore.open(
                    config.dataDir(),
                    config.players().stream().map(AppConfig.Account::actorId).toList());
            LlmBriefingService briefingService = new LlmBriefingService(secretaryLlm);
            SecretarySessionRegistry secretaryRegistry = new SecretarySessionRegistry();
            for (AppConfig.Account player : config.players()) {
                Actor actor = new Actor(player.actorId(), ActorRole.PLAYER);
                secretaryRegistry.register(new SecretarySession(player.actorId(), actor, engine,
                        secretaryLlm, briefingService, bootstrapDirector, clock,
                        secretaryWorkspaceStore, postService));
            }
            AdjudicationService adjudicationService =
                    new AdjudicationService(engine, adjudicatorLlm);
            GmAideAgent gmAideAgent = new GmAideAgent(engine, gmAideLlm, eventReadPort,
                    secretaryWorkspaceStore, postService);
            Map<String, MappedIdentity> identities = buildIdentities(config);
            InteractionTaskflowService interactionService = new InteractionTaskflowService(
                    engine, identities, secretaryRegistry, adjudicationService,
                    engine::allWorldFacts, config.publicGroupId(), clock,
                    config.maxTurnMinutes(), bootstrapDirector);

            MessagingAdapter adapter = options.adapter() == null
                    ? new NapCatAdapter(napcatConfig(config.napcat(), napcatToken))
                    : options.adapter();
            OutboxSender outbox = new OutboxSender(adapter,
                    Duration.ofSeconds(config.outbox().sendTimeoutSeconds()));
            TurnResultDistributor resultDistributor = new TurnResultDistributor(
                    engine, outbox, config.publicGroupId(), playerQqById(config));
            PostDistributor postDistributor = new PostDistributor(postService, outbox,
                    config.publicGroupId(), playerQqById(config));
            AckPolicy ackPolicy = buildAckPolicy(config);
            dispatcher = new InboundDispatcher(interactionService::handle, outbox,
                    resultDistributor, ackPolicy,
                    config.dispatcher().maxConcurrentSessions(),
                    config.dispatcher().maxPendingPerUser(),
                    config.dispatcher().dedupeCapacity());
            AutoFreezeScheduler scheduler = new AutoFreezeScheduler(interactionService,
                    bootstrapDirector,
                    Duration.ofSeconds(config.scheduler().autoFreezeIntervalSeconds()));

            List<WorldFact> factsForDocs = engine.allWorldFacts();
            worldDocs = WorldDocs.open(dataDir, config.world().promptVersion(),
                    WorldDocs.publicSummary(factsForDocs), factsForDocs);

            ControlPlane controlPlane = resolveControlPlane(
                    options, config, engine, eventReadPort, secrets, clock,
                    adjudicationService, gmAideAgent, resultDistributor, postDistributor,
                    secretaryWorkspaceStore, secretaryRegistry, postService);
            runtime = new AppRuntime(config, engine, interactionService, adapter, dispatcher,
                    outbox, scheduler, eventSink, eventReadPort, snapshotStore, worldDocs,
                    controlPlane);
            LOG.info("组合根装配完成 dataDir={} recovered={} players={} directors={} "
                            + "worldFacts={} worldVersion={}",
                    dataDir, recovered, engine.status().playerCount(),
                    engine.status().directorCount(), engine.allWorldFacts().size(),
                    engine.status().worldVersion());
            return runtime;
        } catch (RuntimeException failure) {
            closeQuietly("dispatcher", dispatcher);
            closeQuietly("worldDocs", worldDocs);
            closeQuietly("eventReadPort", eventReadPort);
            closeQuietly("eventSink", eventSink);
            throw failure;
        }
    }

    /**
     * 构造 default + llmRoutes 映射；测试注入的 Fake 客户端优先，未覆盖的命名 route
     * 回退 default。生产路径由 LlmClientFactory 对每条真实 route 分别 fail-closed。
     */
    private static Map<String, LlmClient> resolveLlmClients(AppConfig config,
                                                            BootstrapOptions options,
                                                            SecretProvider secrets) {
        if (options.llm() == null && options.llmRoutes().isEmpty()) {
            return LlmClientFactory.createRoutes(config, secrets);
        }
        LlmClient defaultClient = options.llm();
        if (defaultClient == null) {
            defaultClient = options.llmRoutes().get(AppConfig.DEFAULT_LLM_ROUTE);
        }
        if (defaultClient == null) {
            throw new IllegalStateException(
                    "测试/显式注入路由时缺少 default LlmClient；请同时注入 default");
        }
        Map<String, LlmClient> clients = new LinkedHashMap<>();
        Map<String, AppConfig.Llm> configured = config.effectiveLlmRoutes();
        if (!configured.containsKey(AppConfig.DEFAULT_LLM_ROUTE)) {
            throw new IllegalStateException(
                    "LLM 配置缺失：真实启动必须提供 llm 配置块；拒绝在无模型时启动玩家聊天");
        }
        for (String route : configured.keySet()) {
            clients.put(route, options.llmForRoute(route, defaultClient));
        }
        return Collections.unmodifiableMap(clients);
    }

    private static LlmClient routeClient(Map<String, LlmClient> clients, String route,
                                         LlmClient fallback) {
        LlmClient selected = clients.get(route);
        return selected == null ? Objects.requireNonNull(fallback, "defaultLlm") : selected;
    }

    private static void logRecovered(TaskflowEngine engine, TaskflowStateSnapshot snapshot,
                                     long lastStoredSeq) {
        TurnView turn = engine.turnView().orElse(null);
        LOG.info("Taskflow 状态恢复完成 recovery=true worldVersion={} turnState={} turnId={} "
                        + "turnNo={} eventSeq={} eventCount={} playerCount={} directorCount={}",
                engine.status().worldVersion(), turn == null ? "NONE" : turn.state(),
                turn == null ? "-" : turn.turnId(),
                turn == null ? 0 : turn.turnNo(), lastStoredSeq, snapshot.eventCount(),
                engine.status().playerCount(), engine.status().directorCount());
    }

    private static void requireConfiguredIdentities(TaskflowEngine engine, AppConfig config) {
        Map<String, PlayerProfile> expectedPlayers = profilesFromAccounts(config.players());
        Map<String, PlayerProfile> expectedDirectors = profilesFromAccounts(config.directors());
        Map<String, PlayerProfile> actualPlayers = profilesFromEngine(engine.playerProfiles());
        Map<String, PlayerProfile> actualDirectors = profilesFromEngine(engine.directorProfiles());
        if (!expectedPlayers.equals(actualPlayers) || !expectedDirectors.equals(actualDirectors)) {
            throw new IllegalStateException("RECOVERY_IDENTITY_MISMATCH "
                    + "expectedPlayers=" + expectedPlayers.keySet()
                    + " snapshotPlayers=" + actualPlayers.keySet()
                    + " expectedDirectors=" + expectedDirectors.keySet()
                    + " snapshotDirectors=" + actualDirectors.keySet());
        }
    }

    private static Map<String, PlayerProfile> profilesFromAccounts(List<AppConfig.Account> accounts) {
        Map<String, PlayerProfile> result = new LinkedHashMap<>();
        for (AppConfig.Account account : accounts) {
            result.put(account.actorId(), profileOf(account));
        }
        return result;
    }

    private static Map<String, PlayerProfile> profilesFromEngine(List<PlayerProfile> profiles) {
        Map<String, PlayerProfile> result = new LinkedHashMap<>();
        for (PlayerProfile profile : profiles) {
            result.put(profile.playerId(), profile);
        }
        return result;
    }

    private static Map<String, String> playerQqById(AppConfig config) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AppConfig.Account player : config.players()) {
            result.put(player.actorId(), player.qqId());
        }
        return Map.copyOf(result);
    }

    /**
     * 仅按配置中的玩家/推演 QQ 映射构建 ACK 策略；不联网、不读取消息正文，也不记录 ACK 正文。
     */
    private static AckPolicy buildAckPolicy(AppConfig config) {
        Map<String, String> ackTextByQq = new LinkedHashMap<>();
        for (AppConfig.Account player : config.players()) {
            ackTextByQq.put(player.qqId(), AckPolicy.PLAYER_ACK_TEXT);
        }
        for (AppConfig.Account director : config.directors()) {
            ackTextByQq.put(director.qqId(), AckPolicy.DIRECTOR_ACK_TEXT);
        }
        return AckPolicy.configured(ackTextByQq);
    }

    private static ControlPlane resolveControlPlane(BootstrapOptions options, AppConfig config,
                                                    TaskflowEngine engine,
                                                    SqliteEventReadPort eventReadPort,
                                                    SecretProvider secrets, Clock clock,
                                                    AdjudicationService adjudicationService,
                                                    GmAideAgent gmAideAgent,
                                                    TurnResultDistributor resultDistributor,
                                                    PostDistributor postDistributor,
                                                    SecretaryWorkspaceStore secretaryWorkspaceStore,
                                                    SecretarySessionRegistry secretaryRegistry,
                                                    PostService postService) {
        if (!(options.controlPlane() instanceof NoopControlPlane)) {
            return options.controlPlane();
        }
        if (!config.control().enabled()) {
            return NoopControlPlane.INSTANCE;
        }
        Actor externalSupervisor = new Actor("external-supervisor", ActorRole.SUPERVISOR);
        LOG.info("外部控制 MCP 已启用 host={} port={} path={} tokenEnv={}",
                config.control().host(), config.control().port(), config.control().path(),
                config.control().bearerTokenEnv());
        return ControlMcpServer.builder(engine, eventReadPort, externalSupervisor,
                        config.control())
                .secretProvider(secrets)
                .clock(clock)
                .adjudicationService(adjudicationService)
                .gmAideAgent(gmAideAgent)
                .secretaryWorkspaceStore(secretaryWorkspaceStore)
                .secretarySessions(secretaryRegistry)
                .postService(postService)
                .afterMutation(() -> {
                    runAfterMutationSafely("turn-result-distributor",
                            resultDistributor::distributeIfReady);
                    runAfterMutationSafely("post-distributor",
                            postDistributor::distributeIfReady);
                })
                .build();
    }

    private static void runAfterMutationSafely(String component, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            // 任一宿主分发器失败都不能阻断另一个；只记录组件名与异常类型。
            LOG.warn("MCP afterMutation 组件失败 component={} type={}",
                    component, failure.getClass().getSimpleName());
        }
    }

    private static String resolveNapcatToken(AppConfig.NapCat napcat, SecretProvider secrets) {
        Optional<String> envName = napcat.accessTokenEnv();
        if (envName.isEmpty()) {
            return null;
        }
        String token = secrets.lookup(envName.get()).filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "NapCat access token 未提供：环境变量 " + envName.get()
                                + " 未设置或为空。请设置后重启（不会静默匿名连接）"));
        LOG.info("NapCat token 来源=env:{} 长度={}", envName.get(), token.length());
        return token;
    }

    private static NapCatConfig napcatConfig(AppConfig.NapCat config, String accessToken) {
        NapCatConfig.Builder builder = NapCatConfig.builder(config.eventWebSocketUri(),
                        config.httpBaseUri())
                .accountId(config.accountId().orElse(null))
                .requestTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .maxTextLength(config.maxTextLength())
                .suppressSelfMessages(config.suppressSelfMessages());
        if (accessToken != null) {
            builder.accessToken(accessToken);
        }
        return builder.build();
    }

    private static Actor registerIdentities(TaskflowEngine engine, AppConfig config) {
        AppConfig.Account firstDirector = config.directors().get(0);
        Actor bootstrapDirector = new Actor(firstDirector.actorId(), ActorRole.DIRECTOR);
        for (AppConfig.Account director : config.directors()) {
            PlayerProfile profile = profileOf(director);
            CommandResult result = engine.registerDirector(bootstrapDirector, profile,
                    engine.status().engineRevision(),
                    "bootstrap-register-director:" + director.actorId());
            requireOk(result, "注册推演 " + director.actorId());
        }
        for (AppConfig.Account player : config.players()) {
            PlayerProfile profile = profileOf(player);
            CommandResult result = engine.registerPlayer(bootstrapDirector, profile,
                    engine.status().engineRevision(),
                    "bootstrap-register-player:" + player.actorId());
            requireOk(result, "注册玩家 " + player.actorId());
        }
        return bootstrapDirector;
    }

    private static Map<String, MappedIdentity> buildIdentities(AppConfig config) {
        Map<String, MappedIdentity> identities = new LinkedHashMap<>();
        for (AppConfig.Account director : config.directors()) {
            Actor actor = new Actor(director.actorId(), ActorRole.DIRECTOR);
            identities.put(director.qqId(),
                    new MappedIdentity(director.qqId(), actor, profileOf(director)));
        }
        for (AppConfig.Account player : config.players()) {
            Actor actor = new Actor(player.actorId(), ActorRole.PLAYER);
            identities.put(player.qqId(),
                    new MappedIdentity(player.qqId(), actor, profileOf(player)));
        }
        return Map.copyOf(identities);
    }

    private static PlayerProfile profileOf(AppConfig.Account account) {
        return new PlayerProfile(account.actorId(), account.qqId(), account.displayName());
    }

    private static void requireOk(CommandResult result, String action) {
        if (result == null || !result.ok()) {
            String code = result == null ? "NULL_RESULT" : result.code().name();
            String message = result == null ? "无回执" : result.message();
            throw new IllegalStateException(action + "失败: [" + code + "] " + message);
        }
    }

    private static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建数据目录失败: " + dir);
        }
    }

    private static void closeQuietly(String name, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.warn("装配失败清理异常 name={} error={}", name, e.getClass().getSimpleName());
        }
    }
}
