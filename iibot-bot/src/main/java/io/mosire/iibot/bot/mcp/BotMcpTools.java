package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.bot.friend.FriendRequestService;
import io.mosire.iibot.bot.inbox.InboxDigestAuditStore;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.note.UserNoteStore;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.bot.store.ConsumerConversationStore;
import io.mosire.iibot.bot.store.InMemoryConsumerConversationStore;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.RecentQqProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bot-first MCP 工具集合。 */
public final class BotMcpTools {

    private BotMcpTools() {
    }

    public static List<AgentTool> all(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId) {
        return all(board, notes, inboxDigests, replies, defaultAccountId,
                null, null, null, null, null, null);
    }

    public static List<AgentTool> all(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            ForwardCapability forwardProvider) {
        return all(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProvider, null, null, null, null, null);
    }

    public static List<AgentTool> all(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            ForwardCapability forwardProvider,
            RecentQqProvider recentProvider) {
        return all(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProvider, recentProvider, null, null, null, null);
    }

    public static List<AgentTool> all(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            ForwardCapability forwardProvider,
            RecentQqProvider recentProvider,
            FriendRequestService friendRequests) {
        return all(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProvider, recentProvider, friendRequests, null, null, null);
    }

    public static List<AgentTool> all(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            ForwardCapability forwardProvider,
            RecentQqProvider recentProvider,
            FriendRequestService friendRequests,
            InboxDigestAuditStore inboxAuditStore) {
        return all(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProvider, recentProvider, friendRequests, inboxAuditStore, null,
                null);
    }

    /**
     * 单账号兼容入口：把每个直接 provider 包装为以 {@code defaultAccountId} 为唯一 key 的映射。
     *
     * <p>多账号装配请使用 {@link #allForAccounts} 的映射形式，避免跨账号串号。</p>
     */
    public static List<AgentTool> all(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            ForwardCapability forwardProvider,
            RecentQqProvider recentProvider,
            FriendRequestService friendRequests,
            InboxDigestAuditStore inboxAuditStore,
            ChatHistoryProvider historyProvider,
            SourceWeightService sourceWeights) {
        Map<String, ForwardCapability> forwardProviders = new LinkedHashMap<>();
        if (forwardProvider != null) {
            forwardProviders.put(defaultAccountId, forwardProvider);
        }
        return allForAccounts(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProviders, singleProvider(defaultAccountId, recentProvider),
                singleProvider(defaultAccountId, friendRequests), inboxAuditStore,
                singleProvider(defaultAccountId, historyProvider), sourceWeights,
                new InMemoryConsumerConversationStore());
    }

    /**
     * 多账号工具装配：board 派生能力按消息 accountId 选择 provider。
     *
     * <p>{@code forwardProviders} 应包含所有已启动账号的 key；value 为 null 表示该账号在线
     * 但不支持 forward。直接 provider 工具按同一 accountId 维度装配，省略参数时只有唯一可用
     * provider 才会默认命中，否则返回 {@code ACCOUNT_REQUIRED}。</p>
     */
    public static List<AgentTool> allForAccounts(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            Map<String, ForwardCapability> forwardProviders,
            Map<String, RecentQqProvider> recentProviders,
            Map<String, FriendRequestService> friendRequestServices,
            InboxDigestAuditStore inboxAuditStore,
            Map<String, ChatHistoryProvider> historyProviders,
            SourceWeightService sourceWeights) {
        return allForAccounts(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProviders, recentProviders, friendRequestServices, inboxAuditStore,
                historyProviders, sourceWeights, new InMemoryConsumerConversationStore());
    }

    /**
     * 带 consumer 游标存储的装配入口；不注入账号注册表时账号集合由 provider 映射和默认账号
     * 推导。
     *
     * <p>{@code qq_conversation_read/ack} 是 Agent 默认工具；旧 {@code bot_thread_read}
     * / {@code bot_thread_ack} 仅保留内部兼容类，不进入默认清单。</p>
     */
    public static List<AgentTool> allForAccounts(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            Map<String, ForwardCapability> forwardProviders,
            Map<String, RecentQqProvider> recentProviders,
            Map<String, FriendRequestService> friendRequestServices,
            InboxDigestAuditStore inboxAuditStore,
            Map<String, ChatHistoryProvider> historyProviders,
            SourceWeightService sourceWeights,
            ConsumerConversationStore consumers) {
        return allForAccounts(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProviders, recentProviders, friendRequestServices, inboxAuditStore,
                historyProviders, sourceWeights, consumers, null);
    }

    /**
     * 带账号注册表的完整装配入口。
     *
     * <p>账号工具只从 {@link AccountRegistry} 按稳定 accountId 查找 adapter，不接受模型
     * 传入 adapter、URL 或凭据。所有返回的默认工具均属于统一 {@code qq_*} 业务命名空间。</p>
     */
    public static List<AgentTool> allForAccounts(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            Map<String, ForwardCapability> forwardProviders,
            Map<String, RecentQqProvider> recentProviders,
            Map<String, FriendRequestService> friendRequestServices,
            InboxDigestAuditStore inboxAuditStore,
            Map<String, ChatHistoryProvider> historyProviders,
            SourceWeightService sourceWeights,
            ConsumerConversationStore consumers,
            AccountRegistry accounts) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(inboxDigests, "inboxDigests");
        Objects.requireNonNull(replies, "replies");
        Objects.requireNonNull(consumers, "consumers");
        List<AgentTool> tools = new ArrayList<>();
        if (accounts != null) {
            tools.add(new QqAccountListTool(accounts));
            tools.add(new QqAccountStatusTool(accounts));
            tools.add(new QqAccountLoginInfoTool(accounts));
        }
        BotAccountScope noteScope = BotAccountScope.of(knownAccountIds(accounts,
                defaultAccountId,
                List.of(forwardProviders == null ? Map.of() : forwardProviders,
                        recentProviders == null ? Map.of() : recentProviders,
                        friendRequestServices == null ? Map.of() : friendRequestServices,
                        historyProviders == null ? Map.of() : historyProviders)));
        tools.addAll(List.of(
                new BotThreadListTool(board, sourceWeights),
                new BotThreadHistoryTool(board),
                new BotMessageGetTool(board),
                new BotMessageThreadTool(board),
                new BotReplyTool(replies),
                new BotQqConversationReadTool(board, consumers),
                new BotQqConversationAckTool(consumers, inboxDigests),
                new BotUserNoteGetTool(notes, noteScope),
                new BotUserNoteListTool(notes, noteScope),
                new BotUserNoteUpsertTool(notes, noteScope)));
        if (hasCapability(forwardProviders)) {
            tools.add(new BotForwardReadTool(board, forwardProviders));
        }
        if (hasCapability(recentProviders)) {
            tools.add(new BotQqRecentTool(recentProviders));
        }
        if (hasCapability(friendRequestServices)) {
            tools.add(new BotFriendRequestListTool(friendRequestServices));
            tools.add(new BotFriendRequestDecideTool(friendRequestServices));
        }
        if (inboxAuditStore != null) {
            tools.add(new BotInboxHistoryTool(inboxAuditStore));
        }
        if (hasCapability(historyProviders)) {
            tools.add(new BotQqHistoryTool(historyProviders));
        }
        if (sourceWeights != null) {
            tools.add(new BotSourceWeightListTool(sourceWeights));
            tools.add(new BotSourceWeightGetTool(sourceWeights));
            tools.add(new BotSourceWeightSetTool(sourceWeights, inboxDigests));
        }
        return List.copyOf(tools);
    }

    private static <T> Map<String, T> singleProvider(String accountId, T provider) {
        if (provider == null) {
            return null;
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("单 provider 装配需要非空白 defaultAccountId");
        }
        Map<String, T> providers = new LinkedHashMap<>();
        providers.put(accountId.trim(), provider);
        return providers;
    }

    private static boolean hasCapability(Map<String, ?> providers) {
        return providers != null && providers.values().stream().anyMatch(Objects::nonNull);
    }

    /**
     * 汇总本次装配已知的账号集合：注册表优先，其次各 provider 映射的 key，最后默认账号。
     */
    private static Set<String> knownAccountIds(
            AccountRegistry accounts,
            String defaultAccountId,
            List<Map<String, ?>> maps) {
        Set<String> known = new LinkedHashSet<>();
        if (accounts != null) {
            accounts.snapshot().forEach(account -> known.add(account.accountId()));
        }
        for (Map<String, ?> map : maps) {
            known.addAll(map.keySet());
        }
        if (defaultAccountId != null && !defaultAccountId.isBlank()) {
            known.add(defaultAccountId.trim());
        }
        return known;
    }
}
