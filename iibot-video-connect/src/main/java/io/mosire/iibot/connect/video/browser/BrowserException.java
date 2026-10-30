package io.mosire.iibot.connect.video.browser;

/** 浏览器后端的稳定错误码异常；消息不得包含 Cookie、token 或页面 HTML。 */
public final class BrowserException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    public BrowserException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BrowserException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
