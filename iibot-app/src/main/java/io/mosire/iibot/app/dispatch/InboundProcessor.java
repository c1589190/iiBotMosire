package io.mosire.iibot.app.dispatch;

import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.agent.InteractionReply;

import java.util.List;

/** 入站处理端口；生产实现为 {@code InteractionTaskflowService::handle}。 */
@FunctionalInterface
public interface InboundProcessor {

    /** 处理一条入站消息并返回需要发送的回复；空列表表示无需发送。 */
    List<InteractionReply> handle(InboundMessage message);
}
