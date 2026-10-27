package io.mosire.iibot.app.bot;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.inbox.BotInboxRuntime;
import io.mosire.iibot.app.media.BotMediaGetTool;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.app.reply.BotOutboxReplyPort;
import io.mosire.iibot.bot.friend.FriendRequestService;
import io.mosire.iibot.bot.friend.SqliteFriendRequestStore;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.inbox.InboxDigestScheduler;
import io.mosire.iibot.bot.inbox.SqliteInboxDigestAuditStore;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.inbox.SqliteInboxDigestStateStore;
import io.mosire.iibot.bot.ingest.BotIngestionService;
import io.mosire.iibot.bot.mcp.BotMcpServer;
import io.mosire.iibot.bot.mcp.BotMcpTools;
import io.mosire.iibot.bot.note.SqliteUserNoteStore;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.SqliteMessageBoardStore;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.bot.weight.SqliteSourceWeightStore;
import io.mosire.iibot.bot.summary.MessageSummaryBuilder;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.FriendRequestCapability;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.RecentQqProvider;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.taskflow.agent.PlayerFacingTextGuard;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot-first 的可运行组合根。
 *
 * <p>连接 adapter → SQLite 消息板 → Inbox digest 调度 → Bot MCP；不装配 taskflow、
 * secretary、player、turn 或 world 服务。</p>
 */
public final class BotRuntime implements AutoCloseable {

    private final BotRuntimeOptions options;
    private final MessagingAdapter adapter;
    private final SqliteMessageBoardStore board;
    private final SqliteUserNoteStore notes;
    private final SqliteInboxDigestStateStore digestStates;
    private final BotInboxRuntime inboxRuntime;
    private final InboxDigestScheduler digestScheduler;
    private final BotIngestionService ingestion;
    private final BotMcpServer mcpServer;
    private final SqliteFriendRequestStore friendRequests;
    private final SqliteInboxDigestAuditStore inboxAudit;
    private final SqliteSourceWeightStore sourceWeights;
    private final AtomicBoolean closed = new AtomicBoolean();

    private BotRuntime(BotRuntimeOptions options, MessagingAdapter adapter,
                       SqliteMessageBoardStore board, SqliteUserNoteStore notes,
                       SqliteInboxDigestStateStore digestStates, BotInboxRuntime inboxRuntime,
                       InboxDigestScheduler digestScheduler, BotIngestionService ingestion,
                       BotMcpServer mcpServer, SqliteFriendRequestStore friendRequests,
                       SqliteInboxDigestAuditStore inboxAudit,
                       SqliteSourceWeightStore sourceWeights) {
        this.options = options;
        this.adapter = adapter;
        this.board = board;
        this.notes = notes;
        this.digestStates = digestStates;
        this.inboxRuntime = inboxRuntime;
        this.digestScheduler = digestScheduler;
        this.ingestion = ingestion;
        this.mcpServer = mcpServer;
        this.friendRequests = friendRequests;
        this.inboxAudit = inboxAudit;
        this.sourceWeights = sourceWeights;
    }

    public static BotRuntime start(MessagingAdapter adapter, BotRuntimeOptions options)
            throws IOException {
        return start(adapter, options, Clock.systemUTC());
    }

    public static BotRuntime start(MessagingAdapter adapter, BotRuntimeOptions options,
                                   Clock clock) throws IOException {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(clock, "clock");
        Path dataDir = options.dataDir();
        SqliteMessageBoardStore board = null;
        SqliteUserNoteStore notes = null;
        SqliteInboxDigestStateStore digestStates = null;
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
            SourceWeightService weightService = new SourceWeightService(
                    sourceWeights, board, options.weighting(), clock);
            weightService.ensureExistingThreads(1000);
            inboxRuntime = BotInboxRuntime.start(options.inbox());
            int digestLimit = Math.min(options.maxMessagesPerDigest(),
                    options.weighting().maxMessagesPerDigest());
            InboxDigestService digestService = new InboxDigestService(
                    board, notes, digestStates, inboxRuntime.digestPort(),
                    new MessageSummaryBuilder(), digestLimit,
                    inboxAudit, clock, weightService);
            InboxDigestScheduler scheduler = new InboxDigestScheduler(
                    digestService, Duration.ofMillis(options.digestDebounceMillis()));
            digestScheduler = scheduler;
            BotIngestionService ingestion = new BotIngestionService(
                    board, scheduler::enqueue, weightService);

            OutboxSender outbox = new OutboxSender(adapter, options.outboxTimeout(),
                    options.privateTempGroupId(), options.minSendInterval());
            BotReplyService replies = new BotReplyService(board,
                    new BotOutboxReplyPort(outbox), clock,
                    (text, chatType) -> chatType == ChatType.PRIVATE
                            ? PlayerFacingTextGuard.normalizeForChat(text)
                            : PlayerFacingTextGuard.normalizeForPost(text));
            ForwardCapability forwardProvider = adapter instanceof ForwardCapability capability
                    ? capability : null;
            RecentQqProvider recentProvider = adapter instanceof RecentQqProvider provider
                    ? provider : null;
            FriendRequestService friendService = adapter instanceof FriendRequestCapability capability
                    ? new FriendRequestService(friendRequests, capability, clock) : null;
            ChatHistoryProvider historyProvider = adapter instanceof ChatHistoryProvider provider
                    ? provider : null;
            List<AgentTool> tools = new ArrayList<>(BotMcpTools.all(
                    board, notes, digestService, replies, options.accountId(),
                    forwardProvider, recentProvider, friendService, inboxAudit,
                    historyProvider, weightService));
            BotMediaStore mediaStore = new BotMediaStore(dataDir);
            if (adapter instanceof MediaCapability mediaProvider) {
                tools.add(new BotMediaGetTool(board, mediaProvider, mediaStore));
            }
            tools.addAll(inboxRuntime.mcpTools());
            mcpServer = new BotMcpServer(options.controlHost(), options.controlPort(),
                    options.controlPath(), options.controlToken(),
                    "iibot-bot", "0.1.0", tools, mediaStore);
            // 先恢复旧 state/item 空洞和停机期间消息，再开放 MCP，避免 agent 看到半迁移 item。
            // 单 thread 的 Inbox 冲突不能拖垮启动。
            try {
                digestService.flushAll(1000);
            } catch (RuntimeException ignored) {
                // 具体 thread 的冲突由下一次 ingest 或 MCP 状态诊断暴露。
            }
            mcpServer.start();
            if (adapter instanceof FriendRequestCapability capability) {
                capability.setFriendRequestHandler(friendService::receive);
            }
            adapter.start(message -> ingestion.ingest(message));
            return new BotRuntime(options, adapter, board, notes, digestStates, inboxRuntime,
                    digestScheduler, ingestion, mcpServer, friendRequests, inboxAudit,
                    sourceWeights);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(mcpServer);
            closeQuietly(friendRequests);
            closeQuietly(inboxAudit);
            closeQuietly(sourceWeights);
            closeQuietly(digestScheduler);
            closeQuietly(inboxRuntime);
            closeQuietly(digestStates);
            closeQuietly(notes);
            closeQuietly(board);
            throw failure;
        }
    }

    public BotRuntimeOptions options() {
        return options;
    }

    public MessagingAdapter adapter() {
        return adapter;
    }

    public SqliteMessageBoardStore board() {
        return board;
    }

    public SqliteUserNoteStore notes() {
        return notes;
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
        closeQuietly(adapter);
        closeQuietly(mcpServer);
        closeQuietly(friendRequests);
        closeQuietly(inboxAudit);
        closeQuietly(sourceWeights);
        closeQuietly(digestScheduler);
        closeQuietly(inboxRuntime);
        closeQuietly(digestStates);
        closeQuietly(notes);
        closeQuietly(board);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 生命周期收尾不能掩盖首个失败。
        }
    }
}
