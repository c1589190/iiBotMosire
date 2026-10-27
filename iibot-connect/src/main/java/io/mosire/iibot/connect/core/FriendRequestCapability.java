package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/** 可选好友申请能力；标准 OneBot 11 只处理入站申请，不主动添加好友。 */
public interface FriendRequestCapability {

    void setFriendRequestHandler(FriendRequestHandler handler);

    CompletionStage<SendReceipt> decideFriendRequest(
            String flag, boolean approve, String remark);
}
