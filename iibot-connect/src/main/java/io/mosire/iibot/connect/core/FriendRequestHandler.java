package io.mosire.iibot.connect.core;

/** 入站好友申请回调。 */
@FunctionalInterface
public interface FriendRequestHandler {

    void onFriendRequest(FriendRequestEvent request);
}
