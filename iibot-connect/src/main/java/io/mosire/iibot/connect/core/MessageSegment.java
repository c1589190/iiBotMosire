package io.mosire.iibot.connect.core;

import java.util.Objects;

/**
 * 协议中立的消息段。
 *
 * <p>{@code url} 和 {@code fileId} 只供程序内部取媒体，不进入 MCP 文本结果、
 * 摘要或日志；{@link #toString()} 也不输出这些字段。</p>
 */
public record MessageSegment(
        int index,
        Type type,
        String text,
        String qqId,
        String targetMessageId,
        String fileId,
        String url,
        String mediaType,
        long byteSize,
        String forwardId,
        String rawType) {

    public enum Type {
        TEXT,
        MENTION,
        REPLY,
        IMAGE,
        FORWARD,
        FILE,
        FACE,
        OTHER
    }

    public MessageSegment {
        if (index < 0) {
            throw new IllegalArgumentException("index 不能为负");
        }
        type = Objects.requireNonNull(type, "type");
        text = optional(text);
        qqId = optional(qqId);
        targetMessageId = optional(targetMessageId);
        fileId = optional(fileId);
        url = optional(url);
        mediaType = optional(mediaType);
        forwardId = optional(forwardId);
        rawType = optional(rawType);
        if (byteSize < 0L) {
            throw new IllegalArgumentException("byteSize 不能为负");
        }
    }

    public static MessageSegment text(int index, String text) {
        return new MessageSegment(index, Type.TEXT, text, "", "", "", "", "", 0L, "", "");
    }

    public static MessageSegment mention(int index, String qqId) {
        return new MessageSegment(index, Type.MENTION, "", qqId, "", "", "", "", 0L, "", "");
    }

    public static MessageSegment reply(int index, String messageId) {
        return new MessageSegment(index, Type.REPLY, "", "", messageId, "", "", "", 0L, "", "");
    }

    public static MessageSegment image(int index, String fileId, String url,
                                      String mediaType, long byteSize) {
        return new MessageSegment(index, Type.IMAGE, "", "", "", fileId, url,
                mediaType, byteSize, "", "image");
    }

    public static MessageSegment forward(int index, String forwardId) {
        return new MessageSegment(index, Type.FORWARD, "", "", "", "", "", "",
                0L, forwardId, "forward");
    }

    public boolean media() {
        return type == Type.IMAGE || type == Type.FILE;
    }

    /** 安全文本形状；故意不输出 url/fileId/forwardId。 */
    @Override
    public String toString() {
        return "MessageSegment{index=" + index
                + ", type=" + type
                + ", textLength=" + text.length()
                + ", qqId=" + (qqId.isEmpty() ? "" : "<set>")
                + ", hasTargetMessageId=" + !targetMessageId.isEmpty()
                + ", hasFileId=" + !fileId.isEmpty()
                + ", hasForwardId=" + !forwardId.isEmpty()
                + '}';
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
