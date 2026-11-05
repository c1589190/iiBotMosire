package io.mosire.iibot.app.media;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.media.MediaAssetSink;
import io.mosire.iibot.bot.media.MediaPrewarm;
import io.mosire.iibot.bot.media.MediaSourceKeys;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.ops.OpsLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 媒体临时区的生命周期管理：入站预热 + 到期清理（D-019）。
 *
 * <p>「先存后看」：消息落板成功后立刻把图片/语音等字节抓进 {@link BotMediaStore}，
 * 之后 {@code qq_media_get}/{@code qq_media_inspect} 一律命中本地副本，不再依赖
 * QQ 的短时效直链。</p>
 *
 * <p>临时区按天滚动：默认保留 {@code retentionDays}（7）天，超期文件连来源映射一起删除。</p>
 */
public final class MediaCacheMaintenance implements MediaPrewarm, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MediaCacheMaintenance.class);

    private static final long FETCH_TIMEOUT_SECONDS = 20L;
    private static final int WORKER_THREADS = 2;

    /** 语音/文件默认只预热到这个大小（5 MiB）；超过就等 Agent 真要时再拉。 */
    public static final long DEFAULT_MAX_PREWARM_BYTES = 5L * 1024L * 1024L;

    private final MessageBoardStore board;
    private final Map<String, MediaCapability> providers;
    private final Map<String, AudioCapability> audioProviders;
    private final MediaAssetSink sink;
    private final BotMediaStore store;
    private final long maxPrewarmBytes;
    private final Duration retention;
    private final Clock clock;
    private final ExecutorService workers;
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public MediaCacheMaintenance(MessageBoardStore board,
                                 Map<String, MediaCapability> providers,
                                 MediaAssetSink sink,
                                 BotMediaStore store,
                                 int retentionDays,
                                 Clock clock) {
        this(board, providers, Map.of(), sink, store, retentionDays,
                DEFAULT_MAX_PREWARM_BYTES, clock);
    }

    public MediaCacheMaintenance(MessageBoardStore board,
                                 Map<String, MediaCapability> providers,
                                 Map<String, AudioCapability> audioProviders,
                                 MediaAssetSink sink,
                                 BotMediaStore store,
                                 int retentionDays,
                                 long maxPrewarmBytes,
                                 Clock clock) {
        this.board = board;
        // 图片不设大小上限（表情包/截图都很小）；语音、文件只预热小于上限的。
        this.audioProviders = withoutNulls(audioProviders);
        this.maxPrewarmBytes = maxPrewarmBytes <= 0L
                ? DEFAULT_MAX_PREWARM_BYTES : maxPrewarmBytes;
        this.providers = withoutNulls(providers);
        this.sink = sink;
        this.store = store;
        this.retention = Duration.ofDays(Math.max(1, retentionDays));
        this.clock = clock == null ? Clock.systemUTC() : clock;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "iibot-media-prewarm-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.workers = Executors.newFixedThreadPool(WORKER_THREADS, factory);
    }

    /** capabilityMap 里可能有 null（该账号没有这种能力）：Map.copyOf 不允许 null 值。 */
    private static <T> Map<String, T> withoutNulls(Map<String, T> source) {
        Map<String, T> resolved = new java.util.LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    resolved.put(key, value);
                }
            });
        }
        return Map.copyOf(resolved);
    }

    @Override
    public void prewarm(ChatMessage message) {
        if (message == null) {
            return;
        }
        String accountId = message.source().accountId();
        MediaCapability provider = providers.get(accountId);
        AudioCapability audioProvider = audioProviders.get(accountId);
        if (provider == null && audioProvider == null) {
            return;
        }
        List<MessageSegment> segments;
        try {
            segments = board.segments(message.messageKey());
        } catch (RuntimeException failure) {
            return;
        }
        if (segments == null || segments.isEmpty()) {
            return;
        }
        for (MessageSegment segment : segments) {
            if (segment == null || !segment.media() || !prewarmable(segment, provider,
                    audioProvider)) {
                continue;
            }
            String sourceKey = MediaSourceKeys.of(message.messageKey(), segment);
            if (inFlight.putIfAbsent(sourceKey, Boolean.TRUE) != null) {
                continue;
            }
            if (sink.findBySource(sourceKey).isPresent()) {
                inFlight.remove(sourceKey);
                continue;
            }
            workers.submit(() -> fetch(provider, audioProvider, segment, sourceKey));
        }
    }

    private boolean prewarmable(MessageSegment segment, MediaCapability imageProvider,
                                AudioCapability audioProvider) {
        return switch (segment.type()) {
            case IMAGE -> imageProvider != null;
            case AUDIO -> audioProvider != null
                    && segment.byteSize() > 0L
                    && segment.byteSize() <= maxPrewarmBytes;
            default -> false;
        };
    }

    private void fetch(MediaCapability imageProvider, AudioCapability audioProvider,
                       MessageSegment segment, String sourceKey) {
        try {
            MediaAsset asset = (segment.type() == MessageSegment.Type.AUDIO
                    ? audioProvider.audio(segment) : imageProvider.image(segment))
                    .toCompletableFuture()
                    .get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String assetId = sink.store(asset);
            sink.rememberSource(sourceKey, assetId);
            LOG.debug("event=media_prewarmed bytes={} mediaType={}",
                    asset.bytes() == null ? 0 : asset.bytes().length, asset.mediaType());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // 直链过期/NapCat 缓存被淘汰都会走到这里：只记事件，不影响入站与摘要。
            OpsLog.warn("event=media_prewarm_failed errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            inFlight.remove(sourceKey);
        }
    }

    /** 删除超过保留期的资产与来源映射，返回删除文件数。 */
    public int purgeExpired() {
        Instant cutoff = clock.instant().minus(retention);
        int removed = store.purgeOlderThan(cutoff);
        if (removed > 0) {
            OpsLog.info("event=media_purged files={} retentionDays={}",
                    removed, retention.toDays());
        }
        return removed;
    }

    /** 一次性完成预热收尾并释放线程池（测试/停机用）。 */
    public void shutdown() {
        workers.shutdownNow();
    }

    @Override
    public void close() {
        shutdown();
    }

}
