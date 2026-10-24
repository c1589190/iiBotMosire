package io.mosire.iibot.taskflow.cache;

import io.mosire.agentlib.llm.LlmMessage;

import java.util.List;
import java.util.Optional;

/**
 * 每个玩家一个的持久化秘书会话 cache 端口。
 *
 * <p>实现必须把 cacheId 与 playerId 的 owner 关系作为硬约束；切换 active cache
 * 不得改变玩家身份、权限或工具面。消息保存的是 {@link LlmMessage} 原文，因此可以
 * 在 app 重启后把同一窗口水合回来。</p>
 *
 * <p>本端口只负责 cache 读写；不发送 QQ、不生成回复、不决定世界状态。</p>
 */
public interface SecretaryCacheStore {

    /** 工具声明的资源命名空间。 */
    String RESOURCE_NAMESPACE = "secretary-cache";

    /**
     * 幂等取得玩家的默认/当前 cache；不存在时创建一个空默认 cache。
     */
    CacheSummary ensureDefaultCache(String playerId);

    /** 新建一个空 cache，但不自动切换 active。 */
    CacheSummary createCache(String playerId, String title, String source);

    /** 列出玩家所有 cache，按创建顺序稳定排序。 */
    List<CacheSummary> listCaches(String playerId);

    /** 查一个 cache；不存在返回空。 */
    Optional<CacheSummary> findCache(String playerId, String cacheId);

    /** 当前 active cacheId；未知玩家返回空串或抛出实现定义的稳定异常。 */
    String activeCacheId(String playerId);

    /** 切换 active cache；实现必须校验 owner，并在写入失败时不改变原 active。 */
    void setActiveCache(String playerId, String cacheId);

    /** 读取 cache 的完整 raw 消息历史。 */
    List<LlmMessage> loadMessages(String playerId, String cacheId);

    /** 追加 raw 消息；调用方负责串行化同一 cache 的写入。 */
    void appendMessages(String playerId, String cacheId, List<LlmMessage> messages);
}
