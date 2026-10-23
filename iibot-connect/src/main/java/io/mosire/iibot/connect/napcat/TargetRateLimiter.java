package io.mosire.iibot.connect.napcat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 每个发送目标独立的 token bucket。异步等待，不阻塞调用线程。 */
final class TargetRateLimiter {

    private final double tokensPerSecond;
    private final double burst;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    TargetRateLimiter(double tokensPerSecond, int burst, ScheduledExecutorService scheduler) {
        this.tokensPerSecond = tokensPerSecond;
        this.burst = burst;
        this.scheduler = scheduler;
    }

    /** 为目标获取一个发送 token；返回的 future 完成前不得开始外部请求。 */
    CompletableFuture<Void> acquire(String targetKey) {
        Bucket bucket = buckets.computeIfAbsent(targetKey, ignored -> new Bucket(burst, System.nanoTime()));
        long delayNanos;
        synchronized (bucket) {
            long now = System.nanoTime();
            refill(bucket, now);
            bucket.tokens -= 1.0;
            if (bucket.tokens >= 0.0) {
                return CompletableFuture.completedFuture(null);
            }
            delayNanos = (long) Math.ceil((-bucket.tokens / tokensPerSecond) * 1_000_000_000.0);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        scheduler.schedule(() -> result.complete(null), delayNanos, TimeUnit.NANOSECONDS);
        return result;
    }

    private void refill(Bucket bucket, long now) {
        long elapsed = Math.max(0L, now - bucket.lastRefillNanos);
        if (elapsed == 0L) {
            return;
        }
        bucket.tokens = Math.min(burst, bucket.tokens + (elapsed / 1_000_000_000.0) * tokensPerSecond);
        bucket.lastRefillNanos = now;
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
