package io.mosire.iibot.connect.core;

import java.util.concurrent.CompletionStage;

/** 可选语音/音频提取能力；实现返回受控字节，不返回 URL 或本地路径给模型。 */
public interface AudioCapability {

    CompletionStage<MediaAsset> audio(MessageSegment segment);
}
