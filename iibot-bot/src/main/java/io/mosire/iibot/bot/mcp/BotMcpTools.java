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
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                forwardProviders, recentProvider, friendRequests, inboxAuditStore,
                historyProvider, sourceWeights);
    }

    /**
     * 多账号工具装配：board 派生能力按消息 accountId 选择 provider。
     *
     * <p>{@code forwardProviders} 应包含所有已启动账号的 key；value 为 null 表示该账号在线
     * 但不支持 forward。直接 provider 工具（recent/history/friend request）仍在 P4 使用单一
     * active account，待 P6 统一工具面时多账号化。</p>
     */
    public static List<AgentTool> allForAccounts(
            MessageBoardStore board,
            UserNoteStore notes,
            InboxDigestService inboxDigests,
            BotReplyService replies,
            String defaultAccountId,
            Map<String, ForwardCapability> forwardProviders,
            RecentQqProvider recentProvider,
            FriendRequestService friendRequests,
            InboxDigestAuditStore inboxAuditStore,
            ChatHistoryProvider historyProvider,
            SourceWeightService sourceWeights) {
        return allForAccounts(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProviders, recentProvider, friendRequests, inboxAuditStore,
                historyProvider, sourceWeights, new InMemoryConsumerConversationStore());
    }

    /**
     * 带 consumer 游标存储的装配入口；不注入账号注册表时只生成消息板工具。
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
            RecentQqProvider recentProvider,
            FriendRequestService friendRequests,
            InboxDigestAuditStore inboxAuditStore,
            ChatHistoryProvider historyProvider,
            SourceWeightService sourceWeights,
            ConsumerConversationStore consumers) {
        return allForAccounts(board, notes, inboxDigests, replies, defaultAccountId,
                forwardProviders, recentProvider, friendRequests, inboxAuditStore,
                historyProvider, sourceWeights, consumers, null);
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
            RecentQqProvider recentProvider,
            FriendRequestService friendRequests,
            InboxDigestAuditStore inboxAuditStore,
            ChatHistoryProvider historyProvider,
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
        tools.addAll(List.of(
                new BotThreadListTool(board, sourceWeights),
                new BotThreadHistoryTool(board),
                new BotMessageGetTool(board),
                new BotMessageThreadTool(board),
                new BotReplyTool(replies),
                new BotQqConversationReadTool(board, consumers),
                new BotQqConversationAckTool(consumers, inboxDigests),
                new BotUserNoteGetTool(notes, defaultAccountId),
                new BotUserNoteListTool(notes, defaultAccountId),
                new BotUserNoteUpsertTool(notes, defaultAccountId)));
        if (forwardProviders != null
                && forwardProviders.values().stream().anyMatch(Objects::nonNull)) {
            tools.add(new BotForwardReadTool(board, forwardProviders));
        }
        if (recentProvider != null) {
            tools.add(new BotQqRecentTool(recentProvider, defaultAccountId));
        }
        if (friendRequests != null) {
            tools.add(new BotFriendRequestListTool(friendRequests, defaultAccountId));
            tools.add(new BotFriendRequestDecideTool(friendRequests, defaultAccountId));
        }
        if (inboxAuditStore != null) {
            tools.add(new BotInboxHistoryTool(inboxAuditStore));
        }
        if (historyProvider != null) {
            tools.add(new BotQqHistoryTool(historyProvider, defaultAccountId));
        }
        if (sourceWeights != null) {
            tools.add(new BotSourceWeightListTool(sourceWeights));
            tools.add(new BotSourceWeightGetTool(sourceWeights));
            tools.add(new BotSourceWeightSetTool(sourceWeights, inboxDigests));
        }
        return List.copyOf(tools);
    }
}
