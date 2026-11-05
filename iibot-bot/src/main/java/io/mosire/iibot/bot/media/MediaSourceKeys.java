package io.mosire.iibot.bot.media;

import io.mosire.iibot.connect.core.MessageSegment;

/**
 * 媒体资产的来源键：预热写入与事后读取必须用**同一套规则**，否则存了也读不出来。
 *
 * <p>优先级：{@code file:<fileId>} → {@code url:<url>} → {@code message:<messageKey>:<index>}。
 * 按 {@code fileId} 归并是有意的：同一张图/同一段语音在不同消息里复用同一个 fileId，
 * 只抓一份字节就够。</p>
 */
public final class MediaSourceKeys {

    private MediaSourceKeys() {
    }

    public static String of(String messageKey, MessageSegment segment) {
        String fileId = segment.fileId();
        if (fileId != null && !fileId.isBlank()) {
            return "file:" + fileId;
        }
        String url = segment.url();
        if (url != null && !url.isBlank()) {
            return "url:" + url;
        }
        return "message:" + messageKey + ":" + segment.index();
    }
}
