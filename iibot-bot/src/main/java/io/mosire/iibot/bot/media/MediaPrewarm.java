package io.mosire.iibot.bot.media;

import io.mosire.iibot.bot.chat.ChatMessage;

/**
 * 入站即落盘的媒体预热端口（D-019）。
 *
 * <p>QQ 的图片/文件直链只有几分钟到几小时的有效期，NapCat 的 file 缓存也会过期；
 * 等到 Agent 事后要看图时再去下载，两天前的图必然 404。因此入站写板成功后立即
 * 异步抓一份字节进媒体库，后续 {@code qq_media_get} 永远命中本地缓存。</p>
 *
 * <p>实现必须非阻塞、永不抛异常：预热失败只能记账，不能影响入站。</p>
 */
@FunctionalInterface
public interface MediaPrewarm {

    /** 异步预热一条消息里的媒体段；实现不得抛异常。 */
    void prewarm(ChatMessage message);
}
