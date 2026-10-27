package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/** 可选的合并转发读取能力；不是所有 OneBot 11 实现都必须提供。 */
public interface ForwardCapability {

    CompletionStage<ForwardPage> forward(String forwardId, int limit);
}
