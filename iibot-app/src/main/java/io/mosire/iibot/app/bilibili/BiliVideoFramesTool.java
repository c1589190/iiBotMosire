package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.connect.bilibili.BilibiliCredential;
import io.mosire.iibot.connect.bilibili.BilibiliHttpClient;
import io.mosire.iibot.connect.bilibili.BilibiliModels;
import io.mosire.iibot.connect.bilibili.BilibiliTarget;
import io.mosire.iibot.connect.bilibili.BilibiliTargetResolver;
import io.mosire.iibot.connect.core.MediaAsset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 从任意 B 站视频均匀抽帧，并把 JPEG 作为 MCP ImageContent 返回给模型。 */
public final class BiliVideoFramesTool implements AgentTool {

    public static final String NAME = "bili_video_frames";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 120L * 1024 * 1024;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "target", Map.of("type", "string", "minLength", 1, "maxLength", 2048,
                            "description", "BV/av、bilibili.com/video URL 或 b23.tv 短链"),
                    "count", Map.of("type", "integer", "minimum", 1, "maximum", 9,
                            "description", "均匀抽取的帧数，默认 6"),
                    "page", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "分P，默认 1"),
                    "maxHeight", Map.of("type", "integer", "minimum", 144,
                            "maximum", 1080, "description", "优先选择的视频流高度上限")),
            "required", List.of("target"),
            "additionalProperties", false);

    private final BotMediaStore mediaStore;
    private final BiliFrameExtractor extractor;
    private final BilibiliTargetResolver resolver = new BilibiliTargetResolver();
    private final String apiBase;
    private final String vcApiBase;

    public BiliVideoFramesTool(BotMediaStore mediaStore) {
        this(mediaStore, new FfmpegBiliFrameExtractor(),
                BilibiliHttpClient.API_BASE, BilibiliHttpClient.VC_API_BASE);
    }

    /** 测试/隔离部署入口。 */
    public BiliVideoFramesTool(BotMediaStore mediaStore, BiliFrameExtractor extractor,
                               String apiBase, String vcApiBase) {
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
        this.vcApiBase = Objects.requireNonNull(vcApiBase, "vcApiBase");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "从任意来源的 B 站视频均匀抽取关键帧，并把画面作为 MCP ImageContent 返回。"
                + "适合无字幕、需要看动作/界面/贴图/画面的视频；同时返回视频元数据和每帧时间戳。"
                + "只读工具，不依赖 QQ/Inbox，不产生消息板记录，不发送 B 站内容。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        Path tempDir = null;
        try {
            Map<String, Object> args = context.arguments();
            String targetRaw = requiredText(args, "target", 2048);
            int count = optionalInt(args, "count", 6, 1, 9);
            int page = optionalInt(args, "page", 1, 1, 100);
            int maxHeight = optionalInt(args, "maxHeight", 720, 144, 1080);
            BilibiliTarget target;
            try {
                target = resolver.resolve(targetRaw);
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error("INVALID_TARGET", invalid.getMessage());
            }
            try (BilibiliHttpClient client = new BilibiliHttpClient(
                    BilibiliCredential.anonymous(), null, apiBase, vcApiBase)) {
                BilibiliModels.VideoInfo video = target.bvid().isBlank()
                        ? client.video(null, target.aid(), page)
                        : client.video(target.bvid(), null, page);
                BilibiliModels.PlayStream stream = client.playStream(
                        video.bvid(), video.cid(), maxHeight);
                List<Double> times = timestamps(video.pageDurationSeconds(), count);
                tempDir = Files.createTempDirectory("iibot-bili-frames-");
                Path videoFile = tempDir.resolve("video.mp4");
                client.downloadStream(stream, videoFile, MAX_VIDEO_BYTES);
                List<String> assetIds = new ArrayList<>();
                List<Map<String, Object>> frames = new ArrayList<>();
                List<Map<String, Object>> errors = new ArrayList<>();
                for (int index = 0; index < times.size(); index++) {
                    Path output = tempDir.resolve("frame-" + index + ".jpg");
                    try {
                        BiliFrameExtractor.Frame frame = extractor.extract(
                                videoFile, times.get(index), output);
                        byte[] bytes = Files.readAllBytes(frame.file());
                        if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) {
                            throw new IllegalStateException("抽帧大小非法");
                        }
                        String assetId = mediaStore.store(new MediaAsset(
                                "bili-" + video.bvid() + "-" + index + ".jpg",
                                "image/jpeg", bytes));
                        assetIds.add(assetId);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("index", index);
                        item.put("timestampSeconds", round(times.get(index)));
                        item.put("size", bytes.length);
                        frames.add(item);
                    } catch (Exception failure) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("index", index);
                        item.put("timestampSeconds", round(times.get(index)));
                        item.put("error", "FRAME_UNAVAILABLE");
                        errors.add(item);
                    }
                }
                if (assetIds.isEmpty()) {
                    return ToolResult.error("BILI_FRAMES_UNAVAILABLE",
                            "B站视频抽帧失败");
                }
                Map<String, Object> videoMap = new LinkedHashMap<>();
                videoMap.put("bvid", video.bvid());
                videoMap.put("aid", Long.toString(video.aid()));
                videoMap.put("cid", Long.toString(video.cid()));
                videoMap.put("title", video.title());
                videoMap.put("owner", video.ownerName());
                videoMap.put("durationSeconds", video.durationSeconds());
                videoMap.put("page", video.selectedPage());
                videoMap.put("pages", video.pageCount());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("video", videoMap);
                data.put("streamHeight", stream.height());
                data.put("frames", frames);
                data.put("errors", errors);
                data.put("untrustedContent", true);
                return ToolResult.ok(json(Map.of(
                        "tool", NAME, "ok", true, "data", data)), assetIds);
            }
        } catch (BilibiliHttpClient.BilibiliApiException rejected) {
            return ToolResult.error(rejected.code(), safeMessage(rejected.getMessage()));
        } catch (BilibiliHttpClient.BilibiliTransportException uncertain) {
            return ToolResult.error("BILI_UPSTREAM_UNAVAILABLE", "B站网络请求失败");
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("BILI_FRAMES_FAILED", "B站视频抽帧失败");
        } finally {
            deleteTree(tempDir);
        }
    }

    private static List<Double> timestamps(long durationSeconds, int count) {
        long duration = Math.max(1L, durationSeconds);
        List<Double> values = new ArrayList<>();
        if (count == 1) {
            values.add(duration * 0.5);
            return values;
        }
        double start = duration * 0.05;
        double end = duration * 0.95;
        for (int index = 0; index < count; index++) {
            values.add(start + (end - start) * index / (count - 1));
        }
        return values;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响工具结果。
                }
            });
        } catch (Exception ignored) {
            // 同上。
        }
    }

    private static String requiredText(Map<String, Object> args, String name, int max) {
        Object value = args == null ? null : args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " 必须是非空字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " 过长");
        return normalized;
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback,
                                   int min, int max) {
        Object value = args == null ? null : args.get(name);
        int result = fallback;
        if (value != null) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(name + " 必须是整数");
            }
            result = number.intValue();
        }
        if (result < min || result > max) {
            throw new IllegalArgumentException(name + " 超出范围");
        }
        return result;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("B站抽帧结果序列化失败", failure);
        }
    }

    private static String safeMessage(String value) {
        return value == null || value.isBlank() ? "B站请求失败" : value;
    }
}
