package io.mosire.iibot.connect.core;

import java.util.Objects;

/**
 * 协议中立的出站消息段。
 *
 * <p>适配器必须把本类型映射为对应协议的消息段，不允许把它拼成 CQ 字符串。
 * 图片段携带受控字节；assetRef 只用于服务内部引用和审计，不进入日志正文。</p>
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

    /** 群聊 @全体成员；适配器映射为标准 at 段 {@code {"qq":"all"}}。 */
    record MentionAll() implements OutboundSegment {
    }

    record Reply(String messageId) implements OutboundSegment {
        public Reply {
            messageId = requireText(messageId, "messageId");
        }
    }

    /** 受控出站图片；字节由 BotMediaStore 解析后注入。 */
    record Image(String assetRef, String fileName, String mediaType, byte[] bytes)
            implements OutboundSegment {
        public Image {
            assetRef = requireText(assetRef, "assetRef");
            fileName = optionalText(fileName, "image");
            mediaType = optionalText(mediaType, "application/octet-stream");
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        public Image(String assetRef, String fileName, String mediaType) {
            this(assetRef, fileName, mediaType, new byte[0]);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "Image{assetRef=" + assetRef + ", fileName=" + fileName
                    + ", mediaType=" + mediaType + ", size=" + bytes.length + '}';
        }
    }

    /** QQ 表情/贴纸；只接受受限 face/mface id，不接受任意图片 URL。 */
    record Sticker(String faceId) implements OutboundSegment {
        public Sticker {
            faceId = requireText(faceId, "faceId");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value.trim();
    }

    private static String optionalText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
