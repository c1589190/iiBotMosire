package io.mosire.iibot.taskflow.agent;

import java.util.Objects;

/** 交互服务返回的纯文本回复；发送由上层 outbox 负责。 */
public record InteractionReply(String text) {

    public InteractionReply {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("回复文本不能为空白");
        }
    }
}
