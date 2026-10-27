package io.mosire.iibot.connect.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 合并转发中的一个节点。 */
public record ForwardMessage(
        String senderId,
        String senderName,
        Instant sentAt,
        String text,
        List<MessageSegment> segments) {

    public ForwardMessage {
        senderId = senderId == null ? "" : senderId.trim();
        senderName = senderName == null ? "" : senderName.trim();
        text = text == null ? "" : text;
        segments = segments == null ? List.of() : List.copyOf(segments);
        if (text.isBlank() && segments.isEmpty()) {
            throw new IllegalArgumentException("转发节点没有可展示内容");
        }
    }

    public ForwardMessage(String senderId, String senderName, Instant sentAt,
                          String text) {
        this(senderId, senderName, sentAt, text, List.of());
    }

    public boolean hasNestedForward() {
        return segments.stream().anyMatch(segment ->
                segment.type() == MessageSegment.Type.FORWARD
                        && !segment.forwardId().isEmpty());
    }
}
