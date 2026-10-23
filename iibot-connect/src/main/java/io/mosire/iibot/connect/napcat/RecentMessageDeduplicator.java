package io.mosire.iibot.connect.napcat;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有容量上限、按 TTL 过期的短期消息去重缓存。线程安全。 */
final class RecentMessageDeduplicator {

    private final long ttlNanos;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>(16, 0.75f);

    RecentMessageDeduplicator(Duration ttl, int maxEntries) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须为正数");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须大于 0");
        }
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
    }

    /** 首次见到返回 true 并记录；TTL 内重复返回 false。 */
    synchronized boolean markIfNew(String key, long nowNanos) {
        evictExpired(nowNanos);
        if (seen.containsKey(key)) {
            return false;
        }
        seen.put(key, nowNanos);
        while (seen.size() > maxEntries) {
            Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private void evictExpired(long nowNanos) {
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowNanos - entry.getValue() >= ttlNanos) {
                iterator.remove();
            } else {
                // access-order 链表按访问时间递增；后续条目都未过期。
                break;
            }
        }
    }
}
