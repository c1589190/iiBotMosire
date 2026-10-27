package io.mosire.iibot.connect.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 可选图片读取能力；实现负责把 file/url 解析为受控字节。 */
public interface MediaCapability {

    CompletionStage<MediaAsset> image(MessageSegment segment);

    /**
     * 按 QQ messageId 读取旧消息的图片段；用于本地消息板没有段元数据时的回退。
     * 默认实现表示 provider 不支持。
     */
    default CompletionStage<List<MessageSegment>> imageSegmentsForMessage(String messageId) {
        return CompletableFuture.completedFuture(List.of());
    }
}
