package io.mosire.iibot.bot.media;

import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 把一条消息中的图片读成受控资产；{@code qq_media_get} 与 {@code qq_message_get} 共用。
 *
 * <p>先读消息板里的 IMAGE 段，缺失时回退一次 OneBot 历史消息读取；命中来源缓存时直接复用
 * assetId，不重复下载。单张失败只记录 {@link Error}，不影响其余图片。</p>
 */
public final class MessageImageAssets {

    /** 阅读工具默认附带的图片上限。 */
    public static final int DEFAULT_LIMIT = 4;

    private static final long TIMEOUT_SECONDS = 20L;

    private MessageImageAssets() {
    }

    /** 一张已落库的图片。 */
    public record Image(int segmentIndex, String assetId, String mediaType, int size,
                        boolean cached) {
        public Image {
            Objects.requireNonNull(assetId, "assetId");
            mediaType = mediaType == null || mediaType.isBlank()
                    ? "application/octet-stream" : mediaType;
            if (size < 0) {
                throw new IllegalArgumentException("size 不能为负");
            }
        }
    }

    /** 单张图片的失败信息，不携带内部原因。 */
    public record Error(int segmentIndex, String code) {
        public Error {
            Objects.requireNonNull(code, "code");
        }
    }

    /** 读取结果；{@code failureCode} 非空表示整体失败（例如没有图片段）。 */
    public record Result(List<Image> images, List<Error> errors, boolean fallbackUsed,
                         String failureCode, String failureMessage) {
        public Result {
            images = images == null ? List.of() : List.copyOf(images);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean failed() {
            return failureCode != null;
        }

        public boolean hasImages() {
            return !images.isEmpty();
        }
    }

    /**
     * 读取最多 {@code limit} 张图片并落库。
     *
     * @param board    消息板
     * @param provider 消息所属账号的媒体能力
     * @param sink     资产落库端口
     * @param message  目标消息
     * @param limit    图片上限（至少 1，至多 4）
     */
    public static Result read(MessageBoardStore board, MediaCapability provider,
                              MediaAssetSink sink, ChatMessage message, int limit) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(message, "message");
        int cappedLimit = Math.max(1, Math.min(DEFAULT_LIMIT, limit));
        String messageKey = message.messageKey();

        List<MessageSegment> images = imageSegments(board.segments(messageKey), cappedLimit);
        boolean fallbackUsed = false;
        if (images.isEmpty() && message.messageId() != null
                && !message.messageId().isBlank()) {
            try {
                images = imageSegments(
                        provider.imageSegmentsForMessage(message.messageId())
                                .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        cappedLimit);
                fallbackUsed = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return failure("MEDIA_UNAVAILABLE", "图片回退读取被中断");
            } catch (Exception failure) {
                return failure("MEDIA_UNAVAILABLE", "OneBot 图片回退失败（内部原因已脱敏）");
            }
        }
        if (images.isEmpty()) {
            return failure("MEDIA_NOT_FOUND", "消息中没有图片段");
        }

        List<Image> fetched = new ArrayList<>();
        List<Error> errors = new ArrayList<>();
        for (MessageSegment image : images) {
            try {
                String sourceKey = sourceKey(messageKey, image);
                Optional<String> cachedId = sink.findBySource(sourceKey);
                if (cachedId.isPresent()) {
                    Optional<ToolAsset> cached = sink.resolve(cachedId.orElseThrow());
                    if (cached.isPresent()) {
                        String assetId = cachedId.orElseThrow();
                        ToolAsset asset = cached.orElseThrow();
                        fetched.add(new Image(image.index(), assetId, asset.mediaType(),
                                asset.size(), true));
                        continue;
                    }
                }
                MediaAsset asset = provider.image(image)
                        .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String assetId = sink.store(asset);
                sink.rememberSource(sourceKey, assetId);
                fetched.add(new Image(image.index(), assetId, asset.mediaType(),
                        asset.size(), false));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                errors.add(new Error(image.index(), "MEDIA_UNAVAILABLE"));
            } catch (Exception failure) {
                errors.add(new Error(image.index(), "MEDIA_UNAVAILABLE"));
            }
        }
        if (fetched.isEmpty()) {
            return new Result(List.of(), errors, fallbackUsed,
                    "MEDIA_UNAVAILABLE", "图片读取失败（内部原因已脱敏）");
        }
        return new Result(fetched, errors, fallbackUsed, null, null);
    }

    private static List<MessageSegment> imageSegments(List<MessageSegment> segments, int limit) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        return segments.stream()
                .filter(segment -> segment.type() == MessageSegment.Type.IMAGE)
                .limit(limit)
                .toList();
    }

    private static Result failure(String code, String message) {
        return new Result(List.of(), List.of(), false, code, message);
    }

    private static String sourceKey(String messageKey, MessageSegment image) {
        return MediaSourceKeys.of(messageKey, image);
    }
}
