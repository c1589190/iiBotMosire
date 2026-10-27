package io.mosire.iibot.bot.chat;

/** 私聊或群聊来源类型。 */
public enum ChatType {
    PRIVATE("private"),
    GROUP("group");

    private final String wire;

    ChatType(String wire) {
        this.wire = wire;
    }

    /** 稳定的线格式。 */
    public String wire() {
        return wire;
    }
}
