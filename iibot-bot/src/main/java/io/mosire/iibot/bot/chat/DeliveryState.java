package io.mosire.iibot.bot.chat;

/** 出站投递状态；入站消息使用 {@link #NONE}。 */
public enum DeliveryState {
    NONE,
    PENDING,
    SENT,
    FAILED_BEFORE_SEND,
    UNKNOWN
}
