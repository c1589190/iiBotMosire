package io.mosire.iibot.app.world;

/** 世界事实文件装载失败；启动路径必须 fail-closed，不启动 QQ。 */
public final class WorldFactsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorldFactsException(String message) {
        super(message);
    }

    public WorldFactsException(String message, Throwable cause) {
        super(message, cause);
    }
}
