package io.mosire.iibot.connect.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 平台级 QQ 只读/发送能力面。
 *
 * <p>这是 connect 模块中的最高权限 typed capability：它可以访问当前 Bot 账号通过
 * NapCat 能访问的会话，但不暴露 raw OneBot action、原始 JSON、数据库、shell 或任意 URL。
 * 具体 Agent 能调用哪些操作，由上层 MCP 权限策略决定。</p>
 */
public interface QqPlatformCapability extends ChatHistoryProvider {

    /** 最近会话；包含私聊、群聊以及群临时私聊。 */
    CompletionStage<List<ChatConversation>> recentConversations(int count);

    /** Bot 已加入的群。 */
    CompletionStage<List<ChatConversation>> joinedGroups();

    /**
     * 好友目录。该动作在当前 NapCat 实现上可能很慢；调用方必须设置超时/缓存。
     *
     * @param noCache 是否要求 NapCat 绕过好友缓存
     */
    CompletionStage<List<ChatConversation>> friends(boolean noCache);

    /** 群成员列表；群必须是 Bot 已加入的群。 */
    CompletionStage<List<GroupMember>> groupMembers(String groupId);

    /** 按消息 ID 定位一条消息；仅用于 Bot 可访问的会话。 */
    CompletionStage<ChatMessageDetail> message(String messageId);

    /** 发送普通或群临时私聊；temporaryGroupId 非空时走群临时会话。 */
    CompletionStage<SendReceipt> sendPrivate(String targetQqId, String temporaryGroupId,
                                             List<String> textSegments, String idempotencyKey);

    /** 发送群消息。 */
    CompletionStage<SendReceipt> sendGroup(String groupId, List<String> textSegments,
                                           String idempotencyKey);
}
