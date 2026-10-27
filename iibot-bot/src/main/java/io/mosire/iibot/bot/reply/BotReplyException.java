package io.mosire.iibot.bot.reply;

/** bot_reply 的稳定拒绝/未知错误。 */
public final class BotReplyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public BotReplyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BotReplyException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
