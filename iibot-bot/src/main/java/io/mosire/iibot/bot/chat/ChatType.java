package io.mosire.iibot.bot.chat;

/** 会话来源类型；EXTERNAL 承载非 QQ 平台的统一 source。 */
public enum ChatType {
    PRIVATE("private"),
    GROUP("group"),
    EXTERNAL("external");

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
