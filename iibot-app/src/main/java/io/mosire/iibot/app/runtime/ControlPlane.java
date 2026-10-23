package io.mosire.iibot.app.runtime;

/**
 * 外部控制面构造点。
 *
 * <p>本版 <b>不启动 MCP</b>（留给后续 {@code io.mosire.iibot.app.mcp} 责任区）。组合根只
 * 保留此接口，默认装配 {@link NoopControlPlane}；任何实现都不得在这里伪造“MCP 已启动”。
 * 未来 MCP 实现应从 {@link AppRuntime} 已装配的事件读取端口与受状态机保护的任务流服务
 * 构造，而不是绕过领域状态机。</p>
 */
public interface ControlPlane extends AutoCloseable {

    /** 启动控制面；默认空实现。 */
    default void start() {
        // 无控制面时不启动任何网络监听。
    }

    @Override
    default void close() {
        // 默认无资源。
    }
}
