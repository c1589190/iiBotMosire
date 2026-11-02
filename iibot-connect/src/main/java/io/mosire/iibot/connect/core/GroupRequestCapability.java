package io.mosire.iibot.connect.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 可选群关系能力：读到「已经加入哪些群」、列出待处理的群邀请与入群申请，并能接受邀请或
 * 审批入群申请。
 *
 * <p>QQ 协议不支持「按群号主动申请加群」，因此这里没有 {@code joinByNumber}：
 * 加入一个陌生群只有两条现实路径——被群成员邀请（{@link #acceptGroupInvitation}），
 * 或者由群管理员审批 bot 的入群申请（{@link #decideGroupRequest}）。</p>
 */
public interface GroupRequestCapability {

    /** 已加入的群列表。 */
    CompletionStage<List<ChatConversation>> joinedGroups();

    /** 最近的群系统请求，按待处理优先、序列号倒序返回，最多 {@code limit} 条。 */
    CompletionStage<List<GroupSystemRequest>> listGroupRequests(int limit);

    /**
     * 处理一条群系统请求。
     *
     * @param sequence 通知序列号（OneBot {@code request_id}）
     * @param approve  接受/同意为 true，拒绝为 false
     * @param reason   拒绝理由，可空
     */
    CompletionStage<SendReceipt> decideGroupRequest(long sequence, boolean approve, String reason);

    /**
     * 接受一条群邀请，把 bot 加进群。
     *
     * <p>先按序列号走通知列表，通知已被消化时回退到内核入群请求，因此邀请卡片（未必出现在
     * 待处理通知里）也能处理。</p>
     *
     * @param groupId   群号
     * @param sequence  通知序列号；邀请卡片里的 msgseq
     * @param eventType QQ 通知类型，通常为 {@link GroupSystemRequest#EVENT_TYPE_INVITE}
     */
    CompletionStage<SendReceipt> acceptGroupInvitation(String groupId, long sequence, int eventType);
}
