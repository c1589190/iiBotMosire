package io.mosire.iibot.bot.friend;

/** 本地存储记录；flag 只允许 Service 内部使用。 */
public record StoredFriendRequest(FriendRequestRecord request, String flag) {
}
