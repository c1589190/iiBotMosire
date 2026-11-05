package io.mosire.iibot.bot.media;

import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.iibot.connect.core.MediaAsset;

import java.util.Optional;

/**
 * Bot 侧媒体资产落库端口。
 *
 * <p>只暴露 assetId 与受控字节，不暴露磁盘路径或来源 URL；实现方负责去重与访问控制。
 * 该接口放在 {@code iibot-bot}，由 {@code iibot-app} 的媒体库实现，保持
 * connect → bot → app 的依赖方向。</p>
 */
public interface MediaAssetSink {

    /** 落库一段媒体资产并返回稳定 assetId。 */
    String store(MediaAsset asset);

    /** 按来源键（file:/url:/message:）查已缓存 assetId。 */
    Optional<String> findBySource(String sourceKey);

    /** 按 assetId 取回受控资产内容。 */
    Optional<ToolAsset> resolve(String assetId);

    /** 记住来源键到 assetId 的映射，供后续消息复用。 */
    void rememberSource(String sourceKey, String assetId);
}
