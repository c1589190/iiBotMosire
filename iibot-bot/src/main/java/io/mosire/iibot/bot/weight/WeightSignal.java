package io.mosire.iibot.bot.weight;

/** 消息结构信号；只参与权重计算，不直接决定是否唤醒。 */
public enum WeightSignal {
    DM,
    MENTION,
    REPLY_TO_BOT,
    NAME,
    COMMAND
}
