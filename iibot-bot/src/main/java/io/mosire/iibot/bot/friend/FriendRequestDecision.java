package io.mosire.iibot.bot.friend;

/** 好友申请处理结果的安全视图。 */
public record FriendRequestDecision(
        String requestId,
        String status,
        boolean idempotent,
        String deliveryState,
        String errorCode) {

    public FriendRequestDecision {
        requestId = requestId == null ? "" : requestId;
        status = status == null ? "" : status;
        deliveryState = deliveryState == null ? "" : deliveryState;
        errorCode = errorCode == null ? "" : errorCode;
    }
}
