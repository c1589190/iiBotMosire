package io.mosire.iibot.app.config;

/**
 * 配置装载失败的安全异常。
 *
 * <p>消息只包含字段名、路径与可操作的修正建议，绝不包含环境变量值、访问令牌或 API key。
 * 启动路径应把它视为 fail-closed：不启动 QQ，也不构造 LLM 客户端。</p>
 */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
