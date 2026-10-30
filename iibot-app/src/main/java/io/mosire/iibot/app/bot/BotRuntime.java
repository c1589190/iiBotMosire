package io.mosire.iibot.app.bot;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.bilibili.BiliVideoFramesTool;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.inbox.BotInboxRuntime;
import io.mosire.iibot.app.inbox.InboxBindingDescriptorTool;
import io.mosire.iibot.app.media.BotAudioGetTool;
import io.mosire.iibot.app.media.BotForwardMediaGetTool;
import io.mosire.iibot.app.media.BotGroupFileGetTool;
import io.mosire.iibot.app.media.BotGroupFileHandles;
import io.mosire.iibot.app.media.BotGroupFilesTool;
import io.mosire.iibot.app.media.BotMediaGetTool;
import io.mosire.iibot.app.media.BotMediaUploadTool;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.app.reply.BotGroupFileUploadTool;
import io.mosire.iibot.app.reply.BotMessageRecallTool;
import io.mosire.iibot.app.reply.BotOutboxReplyPort;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.friend.FriendRequestService;
import io.mosire.iibot.bot.friend.SqliteFriendRequestStore;
import io.mosire.iibot.bot.inbox.InboxDigestScheduler;
import io.mosire.iibot.bot.inbox.SqliteInboxDigestAuditStore;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.inbox.SqliteInboxDigestStateStore;
import io.mosire.iibot.bot.ingest.BotIngestionService;
import io.mosire.iibot.bot.mcp.BotMcpServer;
import io.mosire.iibot.bot.mcp.BotMcpTools;
import io.mosire.iibot.bot.note.SqliteUserNoteStore;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.bot.store.SqliteConsumerConversationStore;
import io.mosire.iibot.bot.store.SqliteMessageBoardStore;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.bot.weight.SqliteSourceWeightStore;
import io.mosire.iibot.bot.summary.MessageSummaryBuilder;
import io.mosire.iibot.connect.core.AccountInfo;
import io.mosire.iibot.connect.core.AccountInfoProvider;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.FriendRequestCapability;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.GroupFileCapability;
import io.mosire.iibot.connect.core.GroupFileUploadCapability;
import io.mosire.iibot.connect.core.RecallCapability;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.RecentQqProvider;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.ops.LogContext;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot-first 的可运行组合根。
 *
 * <p>连接多个账号 Adapter → SQLite 消息板 → Inbox digest 调度 → Bot MCP；不装配
 * taskflow、secretary、player、turn 或 world 服务。每个 Adapter 的入站 handler 绑定其
 * 稳定 accountId，出站按 thread 的 accountId 选择独立 OutboxSender。</p>
 */
public final class BotRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BotRuntime.class);
    private static final Duration ACCOUNT_INFO_TIMEOUT = Duration.ofSeconds(5);

    private final BotRuntimeOptions options;
    private final AccountRegistry accountRegistry;
    private final List<AdapterBinding> bindings;
    private final Map<String, OutboxSender> outboxes;
    private static final long THREAD_PROFILE_SYNC_TIMEOUT_SECONDS = 20L;

    private final SqliteMessageBoardStore board;
    private final SqliteUserNoteStore notes;
    private final SqliteInboxDigestStateStore digestStates;
    private final SqliteConsumerConversationStore consumerStates;
    private final BotInboxRuntime inboxRuntime;
    private final InboxDigestScheduler digestScheduler;
    private final BotIngestionService ingestion;
    private final BotMcpServer mcpServer;
    private final SqliteFriendRequestStore friendRequests;
    private final SqliteInboxDigestAuditStore inboxAudit;
    private final SqliteSourceWeightStore sourceWeights;
    private final List<AgentTool> extraTools;
    private final AtomicBoolean closed = new AtomicBoolean();

    private BotRuntime(BotRuntimeOptions options, AccountRegistry accountRegistry,
                       List<AdapterBinding> bindings, Map<String, OutboxSender> outboxes,
                       SqliteMessageBoardStore board, SqliteUserNoteStore notes,
                       SqliteInboxDigestStateStore digestStates,
                       SqliteConsumerConversationStore consumerStates,
                       BotInboxRuntime inboxRuntime,
                       InboxDigestScheduler digestScheduler, BotIngestionService ingestion,
                       BotMcpServer mcpServer, SqliteFriendRequestStore friendRequests,
                       SqliteInboxDigestAuditStore inboxAudit,
                       SqliteSourceWeightStore sourceWeights,
                       List<AgentTool> extraTools) {
        this.options = options;
        this.accountRegistry = accountRegistry;
        this.bindings = List.copyOf(bindings);
        this.outboxes = Map.copyOf(outboxes);
        this.board = board;
        this.notes = notes;
        this.digestStates = digestStates;
        this.consumerStates = consumerStates;
        this.inboxRuntime = inboxRuntime;
        this.digestScheduler = digestScheduler;
        this.ingestion = ingestion;
        this.mcpServer = mcpServer;
        this.friendRequests = friendRequests;
        this.inboxAudit = inboxAudit;
        this.sourceWeights = sourceWeights;
        this.extraTools = extraTools == null ? List.of() : List.copyOf(extraTools);
    }

    /** 单 adapter 兼容入口；内部会建立只含该账号的 AccountRegistry。 */
    public static BotRuntime start(MessagingAdapter adapter, BotRuntimeOptions options)
            throws IOException {
        return start(adapter, options, Clock.systemUTC());
    }

    /** 单 adapter 兼容入口；内部会建立只含该账号的 AccountRegistry。 */
    public static BotRuntime start(MessagingAdapter adapter, BotRuntimeOptions options,
                                   Clock clock) throws IOException {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(options, "options");
        AccountRegistry registry = new AccountRegistry();
        registry.register(options.accountId(), adapter);
        return start(registry, options, clock);
    }

    /** 启动 AccountRegistry 中的全部账号。 */
    public static BotRuntime start(AccountRegistry accountRegistry, BotRuntimeOptions options)
            throws IOException {
        return start(accountRegistry, options, Clock.systemUTC());
    }

    /** 启动 AccountRegistry 中的全部账号。 */
    public static BotRuntime start(AccountRegistry accountRegistry, BotRuntimeOptions options,
                                   Clock clock) throws IOException {
        return start(accountRegistry, options, List.of(), clock);
    }

    /** 启动账号并注册额外的产品工具（例如通用 B 站视频阅读工具）。 */
    public static BotRuntime start(AccountRegistry accountRegistry, BotRuntimeOptions options,
                                   List<AgentTool> extraTools, Clock clock)
            throws IOException {
        Objects.requireNonNull(accountRegistry, "accountRegistry");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(clock, "clock");
        try (LogContext.Scope ignored = LogContext.openTrace(
                LogContext.newTraceId("startup"))) {
            return startScoped(accountRegistry, options, extraTools, clock);
        }
    }

    private static BotRuntime startScoped(AccountRegistry accountRegistry,
                                          BotRuntimeOptions options,
                                          List<AgentTool> extraTools, Clock clock)
            throws IOException {
        Path dataDir = options.dataDir();
        List<AdapterBinding> bindings = bindings(accountRegistry, options);
        SqliteMessageBoardStore board = null;
        SqliteUserNoteStore notes = null;
        SqliteInboxDigestStateStore digestStates = null;
        SqliteConsumerConversationStore consumerStates = null;
        BotInboxRuntime inboxRuntime = null;
        InboxDigestScheduler digestScheduler = null;
        BotMcpServer mcpServer = null;
        SqliteFriendRequestStore friendRequests = null;
        SqliteInboxDigestAuditStore inboxAudit = null;
        SqliteSourceWeightStore sourceWeights = null;
        try {
            board = new SqliteMessageBoardStore(dataDir.resolve("bot.db"));
            friendRequests = new SqliteFriendRequestStore(dataDir.resolve("bot.db"));
            inboxAudit = new SqliteInboxDigestAuditStore(dataDir.resolve("bot.db"));
            sourceWeights = new SqliteSourceWeightStore(dataDir.resolve("bot.db"));
            notes = new SqliteUserNoteStore(dataDir.resolve("bot.db"), clock);
            digestStates = new SqliteInboxDigestStateStore(dataDir.resolve("bot.db"));
            consumerStates = new SqliteConsumerConversationStore(dataDir.resolve("bot.db"));
            LOG.debug("event=bot_datastores_ready dataDir={} dbFile={}",
                    dataDir, dataDir.resolve("bot.db"));
            OpsLog.info("event=bot_runtime_starting accountCount={} dataDir={} "
                            + "accounts={} control={}:{}{} inboxId={} inboxDataDir={}",
                    bindings.size(), dataDir,
                    bindings.stream().map(AdapterBinding::accountId).toList(),
                    options.controlHost(), options.controlPort(), options.controlPath(),
                    options.inbox().inboxId(), options.inbox().dataDir());
            SourceWeightService weightService = new SourceWeightService(
                    sourceWeights, board, options.weighting(), clock);
            weightService.ensureExistingThreads(1000);
            OpsLog.info("event=bot_threads_loaded threads={}", board.threads(1000).size());
            inboxRuntime = BotInboxRuntime.start(options.inbox());
            int digestLimit = Math.min(options.maxMessagesPerDigest(),
                    options.weighting().maxMessagesPerDigest());
            InboxDigestService digestService = new InboxDigestService(
                    board, notes, digestStates, inboxRuntime.digestPort(),
                    new MessageSummaryBuilder(), digestLimit,
                    inboxAudit, clock, weightService, options.inlineMaxChars());
            InboxDigestScheduler scheduler = new InboxDigestScheduler(
                    digestService, Duration.ofMillis(options.digestDebounceMillis()));
            digestScheduler = scheduler;
            LOG.debug("event=digest_scheduler_configured debounceMs={} "
                            + "requestedLimit={} effectiveLimit={}",
                    options.digestDebounceMillis(), options.maxMessagesPerDigest(),
                    digestLimit);
            BotIngestionService ingestion = new BotIngestionService(
                    board, scheduler::enqueue, weightService);

            Map<String, OutboxSender> outboxes = new LinkedHashMap<>();
            for (AdapterBinding binding : bindings) {
                outboxes.put(binding.accountId(), binding.outbox());
            }
            BotMediaStore mediaStore = new BotMediaStore(dataDir);
            BotReplyService replies = new BotReplyService(board,
                    new BotOutboxReplyPort(outboxes, mediaStore), clock);

            Map<String, ForwardCapability> forwardByAccount = capabilityMap(
                    bindings, ForwardCapability.class);
            Map<String, MediaCapability> mediaByAccount = capabilityMap(
                    bindings, MediaCapability.class);
            Map<String, AudioCapability> audioByAccount = capabilityMap(
                    bindings, AudioCapability.class);
            Map<String, GroupFileCapability> groupFilesByAccount = capabilityMap(
                    bindings, GroupFileCapability.class);
            Map<String, RecallCapability> recallByAccount = capabilityMap(
                    bindings, RecallCapability.class);
            Map<String, GroupFileUploadCapability> groupFileUploadByAccount =
                    capabilityMap(bindings, GroupFileUploadCapability.class);
            Map<String, RecentQqProvider> recentByAccount = capabilityMap(
                    bindings, RecentQqProvider.class);
            Map<String, ChatHistoryProvider> historyByAccount = capabilityMap(
                    bindings, ChatHistoryProvider.class);
            // 每个账号一套 FriendRequestService：store 按 accountId 分区，入站 handler 只绑定
            // 本账号 service，审批也只会作用于本账号的记录。
            Map<String, FriendRequestService> friendRequestByAccount = new LinkedHashMap<>();
            for (AdapterBinding binding : bindings) {
                FriendRequestService service = binding.adapter()
                        instanceof FriendRequestCapability capability
                        ? new FriendRequestService(binding.accountId(), friendRequests,
                                capability, clock) : null;
                friendRequestByAccount.put(binding.accountId(), service);
            }
            List<AgentTool> tools = new ArrayList<>(BotMcpTools.allForAccounts(
                    board, notes, digestService, replies, options.accountId(),
                    forwardByAccount, recentByAccount, friendRequestByAccount, inboxAudit,
                    historyByAccount, weightService, consumerStates, accountRegistry));
            tools.add(new BotMediaUploadTool(mediaStore));
            tools.add(new BiliVideoFramesTool(mediaStore));
            if (mediaByAccount.values().stream().anyMatch(Objects::nonNull)) {
                tools.add(new BotMediaGetTool(board, mediaByAccount, mediaStore));
            }
            if (audioByAccount.values().stream().anyMatch(Objects::nonNull)) {
                tools.add(new BotAudioGetTool(board, audioByAccount, mediaStore));
            }
            if (mediaByAccount.values().stream().anyMatch(Objects::nonNull)
                    && forwardByAccount.values().stream().anyMatch(Objects::nonNull)) {
                tools.add(new BotForwardMediaGetTool(board, forwardByAccount,
                        mediaByAccount, mediaStore));
            }
            if (groupFilesByAccount.values().stream().anyMatch(Objects::nonNull)) {
                BotGroupFileHandles groupFileHandles = new BotGroupFileHandles();
                tools.add(new BotGroupFilesTool(groupFilesByAccount, groupFileHandles));
                tools.add(new BotGroupFileGetTool(groupFilesByAccount,
                        groupFileHandles, mediaStore));
            }
            if (groupFileUploadByAccount.values().stream().anyMatch(Objects::nonNull)) {
                tools.add(new BotGroupFileUploadTool(board, groupFileUploadByAccount,
                        mediaStore));
            }
            if (recallByAccount.values().stream().anyMatch(Objects::nonNull)) {
                tools.add(new BotMessageRecallTool(recallByAccount));
            }
            tools.addAll(extraTools);
            // 默认 Agent MCP 只暴露统一 qq_* 业务工具、唯一通用 Inbox 工具
            // inbox_rest，以及暂留宿主交接的 descriptor 工具；不得全量倒入 16 个
            // Inbox claim/append/update 工具。
            tools.addAll(inboxRuntime.mcpTools().stream()
                    .filter(tool -> "inbox_rest".equals(tool.name())
                            || InboxBindingDescriptorTool.NAME.equals(tool.name()))
                    .toList());
            OpsLog.info("event=bot_tools_prepared tools={} digestLimit={}",
                    tools.size(), digestLimit);
            mcpServer = new BotMcpServer(options.controlHost(), options.controlPort(),
                    options.controlPath(), options.controlToken(),
                    "iibot-bot", "0.1.0", tools, mediaStore);
            // 先恢复旧 state/item 空洞和停机期间消息，再开放 MCP，避免 agent 看到半迁移 item。
            // 单 thread 的 Inbox 冲突不能拖垮启动。
            try {
                var recovered = digestService.flushAll(1000);
                OpsLog.info("event=bot_recovery_flush threads={} published={}",
                        recovered.size(), recovered.stream()
                                .filter(result -> result.published()).count());
            } catch (RuntimeException failure) {
                // 具体 thread 的冲突由下一次 ingest 或 MCP 状态诊断暴露。
                OpsLog.warn("event=bot_recovery_flush_failed errorType={}",
                        failure.getClass().getSimpleName());
            }
            mcpServer.start();
            for (AdapterBinding binding : bindings) {
                FriendRequestService service =
                        friendRequestByAccount.get(binding.accountId());
                if (service != null
                        && binding.adapter() instanceof FriendRequestCapability capability) {
                    capability.setFriendRequestHandler(service::receive);
                    LOG.debug("event=friend_request_handler_bound accountId={} adapter={}",
                            binding.accountId(), binding.adapter().adapterId());
                }
            }
            for (AdapterBinding binding : bindings) {
                LOG.debug("event=adapter_state state=starting adapter={} accountId={}",
                        binding.adapter().adapterId(), binding.accountId());
                binding.adapter().start(message -> ingestion.ingest(
                        binding.accountId(), message));
                verifyAccountIdentity(binding.accountId(), binding.adapter());
                OpsLog.info("event=account_started alias={} accountId={} adapter={}",
                        binding.alias(), binding.accountId(), binding.adapter().adapterId());
                LOG.debug("event=adapter_state state=started adapter={} accountId={} "
                                + "inboundHandler=registered",
                        binding.adapter().adapterId(), binding.accountId());
                syncThreadProfiles(binding, board);
            }
            OpsLog.info("event=bot_runtime_ready accountCount={} controlPort={} "
                            + "inboxApiPort={} inboxMcpPort={} tools={}",
                    bindings.size(), mcpServer.boundPort(), inboxRuntime.apiPort(),
                    inboxRuntime.mcpPort(), tools.size());
            return new BotRuntime(options, accountRegistry, bindings, outboxes,
                    board, notes, digestStates, consumerStates, inboxRuntime,
                    digestScheduler, ingestion, mcpServer, friendRequests, inboxAudit,
                    sourceWeights, extraTools);
        } catch (IOException | RuntimeException failure) {
            OpsLog.error("event=bot_runtime_start_failed errorType={}",
                    failure.getClass().getSimpleName());
            closeAdaptersReverse(bindings.stream().map(AdapterBinding::adapter).toList());
            for (int index = extraTools.size() - 1; index >= 0; index--) {
                AgentTool tool = extraTools.get(index);
                if (tool instanceof AutoCloseable closeable) {
                    closeQuietly(closeable);
                }
            }
            closeQuietly(mcpServer);
            closeQuietly(friendRequests);
            closeQuietly(inboxAudit);
            closeQuietly(sourceWeights);
            closeQuietly(digestScheduler);
            closeQuietly(inboxRuntime);
            closeQuietly(consumerStates);
            closeQuietly(digestStates);
            closeQuietly(notes);
            closeQuietly(board);
            throw failure;
        }
    }

    private static List<AdapterBinding> bindings(AccountRegistry registry,
                                                 BotRuntimeOptions options) {
        Set<String> configuredIds = new LinkedHashSet<>();
        Map<String, BotRuntimeOptions.AccountOptions> configured = new LinkedHashMap<>();
        for (BotRuntimeOptions.AccountOptions account : options.accounts()) {
            configuredIds.add(account.accountId());
            configured.put(account.accountId(), account);
        }
        Set<String> registeredIds = new LinkedHashSet<>();
        registry.snapshot().forEach(entry -> registeredIds.add(entry.accountId()));
        if (!configuredIds.equals(registeredIds)) {
            Set<String> missing = new LinkedHashSet<>(configuredIds);
            missing.removeAll(registeredIds);
            Set<String> extra = new LinkedHashSet<>(registeredIds);
            extra.removeAll(configuredIds);
            throw new IllegalArgumentException("AccountRegistry 与 runtime 配置不一致: missing="
                    + missing + " extra=" + extra);
        }
        List<BotRuntimeOptions.AccountOptions> ordered = new ArrayList<>(configured.size());
        BotRuntimeOptions.AccountOptions active = configured.get(options.accountId());
        ordered.add(active);
        for (BotRuntimeOptions.AccountOptions account : configured.values()) {
            if (!account.accountId().equals(active.accountId())) {
                ordered.add(account);
            }
        }
        List<AdapterBinding> bindings = new ArrayList<>(ordered.size());
        for (BotRuntimeOptions.AccountOptions account : ordered) {
            MessagingAdapter adapter = registry.find(account.accountId()).orElseThrow();
            OutboxSender outbox = new OutboxSender(adapter, options.outboxTimeout(),
                    account.privateTempGroupId(), options.minSendInterval());
            bindings.add(new AdapterBinding(account.alias(), account.accountId(),
                    adapter, outbox));
        }
        return List.copyOf(bindings);
    }

    private static <T> Map<String, T> capabilityMap(List<AdapterBinding> bindings,
                                                     Class<T> capabilityType) {
        Map<String, T> capabilities = new LinkedHashMap<>();
        for (AdapterBinding binding : bindings) {
            capabilities.put(binding.accountId(), capabilityType.isInstance(binding.adapter())
                    ? capabilityType.cast(binding.adapter()) : null);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }

    /**
     * 连接后异步同步已加入群/好友的展示名；只更新已存在的 thread，不阻塞启动，
     * 失败保持 targetId 回退值。
     */
    private static void syncThreadProfiles(AdapterBinding binding, MessageBoardStore board) {
        if (!(binding.adapter() instanceof QqPlatformCapability platform)) {
            return;
        }
        platform.joinedGroups()
                .thenCombine(platform.friends(false), (groups, friends) -> {
                    List<ChatConversation> all = new ArrayList<>();
                    all.addAll(groups);
                    all.addAll(friends);
                    return all;
                })
                .toCompletableFuture()
                .orTimeout(THREAD_PROFILE_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenAccept(conversations -> {
                    int updated = 0;
                    for (ChatConversation conversation : conversations) {
                        String displayName = conversation.remark().isBlank()
                                ? conversation.displayName() : conversation.remark();
                        if (displayName.isBlank()) {
                            continue;
                        }
                        ChatType type = switch (conversation.sessionType()) {
                            case GROUP -> ChatType.GROUP;
                            case PRIVATE -> ChatType.PRIVATE;
                            case EXTERNAL -> null;
                        };
                        if (type == null) {
                            continue;
                        }
                        ChatSourceKey key = new ChatSourceKey(binding.accountId(),
                                type, conversation.targetId());
                        if (board.updateThreadDisplayName(key.threadId(), displayName)) {
                            updated++;
                        }
                    }
                    OpsLog.info("event=thread_profiles_synced accountId={} "
                                    + "conversations={} updated={}",
                            binding.accountId(), conversations.size(), updated);
                })
                .exceptionally(failure -> {
                    OpsLog.warn("event=thread_profiles_sync_failed accountId={} "
                                    + "errorType={}", binding.accountId(),
                            failure.getClass().getSimpleName());
                    return null;
                });
    }

    private static void verifyAccountIdentity(String expectedAccountId,
                                              MessagingAdapter adapter) {
        if (!(adapter instanceof AccountInfoProvider provider)) {
            return;
        }
        AccountInfo info;
        try {
            info = provider.accountInfo().toCompletableFuture()
                    .get(ACCOUNT_INFO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            OpsLog.error("event=account_identity_check_failed expectedAccountId={} "
                            + "adapter={} reason=interrupted", expectedAccountId,
                    adapter.adapterId());
            throw new IllegalStateException("账号身份校验被中断: " + expectedAccountId);
        } catch (TimeoutException | ExecutionException | RuntimeException failure) {
            OpsLog.error("event=account_identity_check_failed expectedAccountId={} "
                            + "adapter={} reason={}", expectedAccountId, adapter.adapterId(),
                    failure.getClass().getSimpleName());
            throw new IllegalStateException("账号身份校验失败: " + expectedAccountId);
        }
        if (!expectedAccountId.equals(info.accountId())) {
            OpsLog.error("event=account_identity_mismatch expectedAccountId={} "
                            + "actualAccountId={} adapter={}", expectedAccountId,
                    info.accountId(), adapter.adapterId());
            throw new IllegalStateException("登录账号与配置 accountId 不一致: "
                    + expectedAccountId);
        }
        OpsLog.info("event=account_identity_verified accountId={} adapter={}",
                expectedAccountId, adapter.adapterId());
    }

    public BotRuntimeOptions options() {
        return options;
    }

    /** 兼容单账号调用：返回 active/first account adapter。 */
    public MessagingAdapter adapter() {
        return bindings.getFirst().adapter();
    }

    /** @return 完整多账号注册表。 */
    public AccountRegistry accountRegistry() {
        return accountRegistry;
    }

    /** 按稳定 accountId 查询本次启动的 sender。 */
    public Optional<OutboxSender> outboxSender(String accountId) {
        return Optional.ofNullable(outboxes.get(accountId));
    }

    /** @return 本次启动的账号配置顺序快照。 */
    public List<BotRuntimeOptions.AccountOptions> accounts() {
        return bindings.stream()
                .map(binding -> new BotRuntimeOptions.AccountOptions(
                        binding.alias(), binding.accountId(),
                        options.accountOptions(binding.accountId())
                                .map(BotRuntimeOptions.AccountOptions::privateTempGroupId)
                                .orElse(null)))
                .toList();
    }

    public SqliteMessageBoardStore board() {
        return board;
    }

    public SqliteUserNoteStore notes() {
        return notes;
    }

    public SqliteConsumerConversationStore consumerStateStore() {
        return consumerStates;
    }

    public BotInboxRuntime inboxRuntime() {
        return inboxRuntime;
    }

    public InboxDigestScheduler digestScheduler() {
        return digestScheduler;
    }

    public BotIngestionService ingestion() {
        return ingestion;
    }

    public BotMcpServer mcpServer() {
        return mcpServer;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.debug("event=bot_runtime_close phase=starting accounts={}", bindings.size());
        OpsLog.info("event=bot_runtime_close phase=starting accountCount={}",
                bindings.size());
        closeAdaptersReverse(bindings.stream().map(AdapterBinding::adapter).toList());
        for (int index = extraTools.size() - 1; index >= 0; index--) {
            AgentTool tool = extraTools.get(index);
            if (tool instanceof AutoCloseable closeable) {
                closeQuietly(closeable);
            }
        }
        closeQuietly(mcpServer);
        closeQuietly(friendRequests);
        closeQuietly(inboxAudit);
        closeQuietly(sourceWeights);
        closeQuietly(digestScheduler);
        closeQuietly(inboxRuntime);
        closeQuietly(consumerStates);
        closeQuietly(digestStates);
        closeQuietly(notes);
        closeQuietly(board);
        OpsLog.info("event=bot_runtime_close phase=completed");
    }

    private static void closeAdaptersReverse(List<MessagingAdapter> adapters) {
        for (int index = adapters.size() - 1; index >= 0; index--) {
            closeQuietly(adapters.get(index));
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception failure) {
            // 生命周期收尾不能掩盖首个失败。
            OpsLog.warn("event=component_close_failed component={} errorType={}",
                    closeable.getClass().getSimpleName(), failure.getClass().getSimpleName());
        }
    }

    private record AdapterBinding(String alias, String accountId,
                                  MessagingAdapter adapter, OutboxSender outbox) {
        private AdapterBinding {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(outbox, "outbox");
        }
    }
}
