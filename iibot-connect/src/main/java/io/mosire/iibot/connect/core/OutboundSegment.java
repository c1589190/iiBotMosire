package io.mosire.iibot.connect.core;

import java.util.Objects;

/**
 * 协议中立的出站消息段。
 *
 * <p>适配器必须把本类型映射为对应协议的消息段，不允许把它拼成 CQ 字符串。
 * 当前支持 text/at/reply；图片发送、合并转发等后续通过扩展段加入。</p>
 */
public sealed interface OutboundSegment {

    record Text(String text) implements OutboundSegment {
        public Text {
            Objects.requireNonNull(text, "text");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("text segment 不能为空字符串");
            }
        }
    }

    record Mention(String qqId) implements OutboundSegment {
        public Mention {
            qqId = requireText(qqId, "qqId");
        }
    }

    record Reply(String messageId) implements OutboundSegment {
        public Reply {
            messageId = requireText(messageId, "messageId");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value.trim();
    }
}
