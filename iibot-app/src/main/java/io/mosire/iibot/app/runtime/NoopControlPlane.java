package io.mosire.iibot.app.runtime;

/** 明确表示“本版未启动 MCP/控制面”的空实现。 */
public final class NoopControlPlane implements ControlPlane {

    public static final NoopControlPlane INSTANCE = new NoopControlPlane();

    private NoopControlPlane() {
    }
}
