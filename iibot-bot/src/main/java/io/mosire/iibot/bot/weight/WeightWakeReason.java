package io.mosire.iibot.bot.weight;

/** 进入 Inbox 的主要原因；低于阈值的消息使用 BELOW_THRESHOLD。 */
public enum WeightWakeReason {
    FIRST_CONTACT,
    DM,
    MENTION,
    REPLY_TO_BOT,
    NAME,
    COMMAND,
    KEYWORD,
    SOURCE_WEIGHT,
    BELOW_THRESHOLD
}
