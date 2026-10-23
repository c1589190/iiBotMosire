package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/**
 * 外部消息接入适配器的统一生命周期与消息发送 SPI。
 *
 * <p>实现必须把协议细节（例如 OneBot 的 JSON、CQ Code、echo、WebSocket URL）
 * 封装在内部；上层只依赖本包中的领域中立类型。</p>
 *
 * <p>线程安全：实现应允许状态查询、事件监听器增删与 {@link #close()}
 * 被多个线程调用。{@link #send(OutboundMessage)} 返回的 future 可能在不同线程完成。</p>
 */
public interface MessagingAdapter extends AutoCloseable {

    /** 稳定的适配器实例标识，用作 {@link InboundMessage#adapterId()} 等字段来源。 */
    String adapterId();

    /**
     * 启动接入适配器。该方法不应阻塞等待网络连接建立。
     *
     * @param handler 入站消息回调；同一适配器只接受一次启动
     * @throws IllegalStateException 已启动、已关闭或 handler 为 null 时
     */
    void start(InboundHandler handler);

    /**
     * 发送一条逻辑消息。实现负责分段、目标级限速与回执语义。
     *
     * <p>重复调用具有相同 {@link OutboundMessage#idempotencyKey()} 的消息时，
     * 适配器应返回同一发送操作的回执，而不是再次产生外部副作用。</p>
     *
     * @return 三态发送回执；失败不会抛出到调用线程，而是通过回执表达
     */
    CompletionStage<SendReceipt> send(OutboundMessage message);

    /** 返回当前连接状态的不可变快照。 */
    AdapterStatus status();

    /** 注册可观测事件监听器；实现应吞掉单个监听器的异常，避免影响连接。 */
    void addEventListener(AdapterEventListener listener);

    /** 移除之前注册的监听器；不存在时为空操作。 */
    void removeEventListener(AdapterEventListener listener);

    /**
     * 幂等关闭适配器。关闭后不再接收入站消息，并完成所有未完成发送 future：
     * 尚未发出请求的为 {@link SendReceipt.State#FAILED_BEFORE_SEND}，
     * 可能已发出的为 {@link SendReceipt.State#UNKNOWN}。
     */
    @Override
    void close();
}
