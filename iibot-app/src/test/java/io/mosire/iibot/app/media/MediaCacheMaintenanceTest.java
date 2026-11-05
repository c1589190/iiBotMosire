package io.mosire.iibot.app.media;

import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCacheMaintenanceTest {

    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void prewarmStoresImageBytesSoLaterReadsHitTheLocalCopy() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "1472304806", "qq-1", "Alice",
                T0, T0, "", "[图片×1]", "");
        board.append(message, List.of(MessageSegment.image(
                0, "a.jpg", "https://multimedia.nt.qq.com.cn/a.jpg", "image/jpeg", 4)));
        AtomicInteger fetches = new AtomicInteger();
        MediaCapability provider = provider(fetches, new byte[]{1, 2, 3, 4});
        BotMediaStore store = new BotMediaStore(tempDir);
        MediaCacheMaintenance maintenance = new MediaCacheMaintenance(
                board, Map.of("bot-1", provider), store, store, 7, Clock.systemUTC());
        try {
            maintenance.prewarm(message);
            maintenance.prewarm(message); // 第二次命中来源映射，不应再抓一次
            Path stored = waitForAsset(tempDir);
            assertThat(stored).isNotNull();
            assertThat(fetches.get()).isEqualTo(1);
        } finally {
            maintenance.close();
        }
    }

    @Test
    void prewarmCoversAudioOnlyBelowTheSizeLimit() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "1472304806", "qq-1", "Alice",
                T0, T0, "", "[语音×2]", "");
        board.append(message, List.of(
                MessageSegment.audio(0, "small.amr", "https://x/small.amr", "audio/amr", 1024L),
                MessageSegment.audio(1, "big.mp3", "https://x/big.mp3", "audio/mpeg", 9_000_000L)));
        AtomicInteger fetches = new AtomicInteger();
        AudioCapability audio = segment -> {
            fetches.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new MediaAsset("a.amr", "audio/amr", new byte[]{7}));
        };
        BotMediaStore store = new BotMediaStore(tempDir);
        MediaCacheMaintenance maintenance = new MediaCacheMaintenance(
                board, Map.of(), Map.of("bot-1", audio), store, store, 7,
                MediaCacheMaintenance.DEFAULT_MAX_PREWARM_BYTES, Clock.systemUTC());
        try {
            maintenance.prewarm(message);
            assertThat(waitForAsset(tempDir)).isNotNull();
            assertThat(fetches.get()).isEqualTo(1);
        } finally {
            maintenance.close();
        }
    }

    @Test
    void purgeExpiredDeletesAssetsOlderThanTheRetentionWindow() throws Exception {
        BotMediaStore store = new BotMediaStore(tempDir);
        store.store(new MediaAsset("a.jpg", "image/jpeg", new byte[]{9}));
        Path file;
        try (var files = Files.walk(tempDir.resolve("media"))) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-10-01T00:00:00Z")));

        Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC);
        MediaCacheMaintenance maintenance = new MediaCacheMaintenance(
                new InMemoryMessageBoardStore(), Map.of(), store, store, 7, clock);
        try {
            assertThat(maintenance.purgeExpired()).isEqualTo(1);
            assertThat(file).doesNotExist();
            assertThat(maintenance.purgeExpired()).isZero();
        } finally {
            maintenance.close();
        }
    }

    private static Path waitForAsset(Path dataDir) throws InterruptedException, IOException {
        Path media = dataDir.resolve("media");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(media)) {
                try (var files = Files.walk(media)) {
                    Path found = files.filter(Files::isRegularFile).findFirst().orElse(null);
                    if (found != null) {
                        return found;
                    }
                }
            }
            Thread.sleep(20L);
        }
        return null;
    }

    private static MediaCapability provider(AtomicInteger fetches, byte[] bytes) {
        return new MediaCapability() {
            @Override
            public CompletableFuture<MediaAsset> image(MessageSegment segment) {
                fetches.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new MediaAsset("a.jpg", "image/jpeg", bytes));
            }

            @Override
            public CompletableFuture<List<MessageSegment>> imageSegmentsForMessage(
                    String messageId) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
