package io.mosire.iibot.connect.core;

import java.util.Objects;

/**
 * 一条归一化的群系统请求：群邀请（有人邀 bot 入群）或入群申请（有人要进 bot 管的群）。
 *
 * <p>{@code sequence} 是 QQ 的通知序列号，同时也是 OneBot 的 {@code request_id}。它可以
 * 直接作为 {@code set_group_add_request} 的数字 flag，也可以作为内核 OIDB 0x10c8 的序列号。
 * 该值远小于 2^53，用 long 或字符串传递都不会丢精度。</p>
 *
 * <p>本记录不承载任何票据（token/flag 原文），可以安全落库与进上下文。</p>
 *
 * @param sequence      通知序列号，即 OneBot {@code request_id}
 * @param kind          请求类别
 * @param eventType     QQ 通知类型：1 入群申请，2 群邀请，22 被邀请入群
 * @param groupId       目标群号
 * @param groupName     目标群名
 * @param inviterId     邀请人 QQ
 * @param inviterName   邀请人昵称
 * @param requesterId   申请人 QQ
 * @param requesterName 申请人昵称
 * @param message       附加验证消息（已裁剪）
 * @param handled       服务端是否已标记处理过
 */
public record GroupSystemRequest(
        long sequence,
        Kind kind,
        int eventType,
        String groupId,
        String groupName,
        String inviterId,
        String inviterName,
        String requesterId,
        String requesterName,
        String message,
        boolean handled) {

    /** 群邀请的通知类型。 */
    public static final int EVENT_TYPE_INVITE = 2;
    /** 入群申请的通知类型。 */
    public static final int EVENT_TYPE_JOIN = 1;

    /** 请求类别。 */
    public enum Kind {
        /** 有人邀请 bot 进群。 */
        INVITE,
        /** 有人申请加入某个群。 */
        JOIN,
        /** 其它群系统通知。 */
        OTHER
    }

    public GroupSystemRequest {
        Objects.requireNonNull(kind, "kind");
        groupId = normalize(groupId);
        groupName = normalize(groupName);
        inviterId = normalize(inviterId);
        inviterName = normalize(inviterName);
        requesterId = normalize(requesterId);
        requesterName = normalize(requesterName);
        message = normalize(message);
    }

    /** 是否仍待处理：服务端未标记处理，并且序列号可用。 */
    public boolean pending() {
        return !handled && sequence > 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
