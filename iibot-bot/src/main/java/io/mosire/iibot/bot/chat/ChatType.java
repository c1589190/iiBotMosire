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

    /** 解析稳定的线格式；未知值 fail-closed。 */
    public static ChatType fromWire(String value) {
        for (ChatType type : values()) {
            if (type.wire.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知 chatType: " + value);
    }
}
