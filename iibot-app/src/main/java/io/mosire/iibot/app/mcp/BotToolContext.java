package io.mosire.iibot.app.mcp;

import io.mosire.iibot.app.inbox.BotInboxRuntime;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.bot.friend.FriendRequestService;
import io.mosire.iibot.bot.inbox.InboxDigestAuditStore;
import io.mosire.iibot.bot.inbox.InboxDigestService;
import io.mosire.iibot.bot.note.UserNoteStore;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.ConsumerConversationStore;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.connect.core.ChatHistoryProvider;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.GroupFileCapability;
import io.mosire.iibot.connect.core.GroupFileUploadCapability;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.RecallCapability;
import io.mosire.iibot.connect.core.RecentQqProvider;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * App 组合根暴露给 MCP 工具目录的运行时服务快照。
 *
 * <p>这里只放已经装配好的服务与按账号能力映射；工具目录不得反向创建连接、数据库或
 * Inbox 运行时。B 站账号配置由 {@link BotToolCatalog} 持有，避免运行时核心直接依赖
 * 视频站领域类。</p>
 */
public record BotToolContext(
        MessageBoardStore board,
        UserNoteStore notes,
        InboxDigestService inboxDigests,
        BotReplyService replies,
        String defaultAccountId,
        Map<String, ForwardCapability> forwardByAccount,
        Map<String, RecentQqProvider> recentByAccount,
        Map<String, FriendRequestService> friendRequestByAccount,
        InboxDigestAuditStore inboxAudit,
        Map<String, ChatHistoryProvider> historyByAccount,
        SourceWeightService sourceWeights,
        ConsumerConversationStore consumerStates,
        AccountRegistry accountRegistry,
        Path dataDir,
        BotMediaStore mediaStore,
        Map<String, MediaCapability> mediaByAccount,
        Map<String, AudioCapability> audioByAccount,
        Map<String, GroupFileCapability> groupFilesByAccount,
        Map<String, RecallCapability> recallByAccount,
        Map<String, GroupFileUploadCapability> groupFileUploadByAccount,
        BotInboxRuntime inboxRuntime) {

    public BotToolContext {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(inboxDigests, "inboxDigests");
        Objects.requireNonNull(replies, "replies");
        Objects.requireNonNull(defaultAccountId, "defaultAccountId");
        Objects.requireNonNull(forwardByAccount, "forwardByAccount");
        Objects.requireNonNull(recentByAccount, "recentByAccount");
        Objects.requireNonNull(friendRequestByAccount, "friendRequestByAccount");
        Objects.requireNonNull(historyByAccount, "historyByAccount");
        Objects.requireNonNull(sourceWeights, "sourceWeights");
        Objects.requireNonNull(consumerStates, "consumerStates");
        Objects.requireNonNull(accountRegistry, "accountRegistry");
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(mediaStore, "mediaStore");
        Objects.requireNonNull(mediaByAccount, "mediaByAccount");
        Objects.requireNonNull(audioByAccount, "audioByAccount");
        Objects.requireNonNull(groupFilesByAccount, "groupFilesByAccount");
        Objects.requireNonNull(recallByAccount, "recallByAccount");
        Objects.requireNonNull(groupFileUploadByAccount, "groupFileUploadByAccount");
        Objects.requireNonNull(inboxRuntime, "inboxRuntime");
    }
}
