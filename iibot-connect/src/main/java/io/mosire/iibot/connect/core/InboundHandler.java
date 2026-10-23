package io.mosire.iibot.connect.core;

/** 入站消息回调。实现方抛出的异常不会传播回协议连接线程。 */
@FunctionalInterface
public interface InboundHandler {
    void onMessage(InboundMessage message);
}
