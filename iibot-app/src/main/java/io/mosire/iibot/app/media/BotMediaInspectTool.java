package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 大图/GIF 的有界查看工具：按 assetId 或消息图片段读取首帧，降采样并切块。
 *
 * <p>不返回原始 URL；原图仍由 {@link BotMediaGetTool} 管理。</p>
 */
public final class BotMediaInspectTool implements AgentTool {

    public static final String NAME = "qq_media_inspect";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1,
                            "description", "消息 key；与 assetId 二选一"),
                    "assetId", Map.of("type", "string", "minLength", 1,
                            "description", "已缓存的图片 assetId；与 messageKey 二选一"),
                    "segmentIndex", Map.of("type", "integer", "minimum", 0,
                            "description", "多图消息中的段下标，默认 0"),
                    "mode", Map.of("type", "string", "enum", List.of("first_frame", "grid"),
                            "description", "first_frame 只返回首帧；grid 返回分块，默认 grid"),
                    "rows", Map.of("type", "integer", "minimum", 1, "maximum", 3,
                            "description", "grid 行数，默认 2"),
                    "columns", Map.of("type", "integer", "minimum", 1, "maximum", 3,
                            "description", "grid 列数，默认 2"),
                    "maxWidth", Map.of("type", "integer", "minimum", 320, "maximum", 1600,
                            "description", "输出最大边，默认 1280")),
            "required", List.of(),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final MediaCapability singleProvider;
    private final Map<String, MediaCapability> providers;
    private final BotMediaStore store;

    public BotMediaInspectTool(MessageBoardStore board, MediaCapability provider,
                               BotMediaStore store) {
        this.board = Objects.requireNonNull(board, "board");
        this.singleProvider = Objects.requireNonNull(provider, "provider");
        this.providers = Map.of();
        this.store = Objects.requireNonNull(store, "store");
    }

    public BotMediaInspectTool(MessageBoardStore board, Map<String, MediaCapability> providers,
                               BotMediaStore store) {
        this.board = Objects.requireNonNull(board, "board");
        Objects.requireNonNull(providers, "providers");
        this.singleProvider = null;
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "查看大图/GIF/长截图：只解码首帧，降采样后返回有界分块；用于普通图片读取失败或过大时。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            Loaded loaded = load(context.arguments());
            boolean grid = !"first_frame".equals(optionalString(context.arguments(), "mode", "grid"));
            int rows = optionalInt(context.arguments(), "rows", 2, 1, 3);
            int columns = optionalInt(context.arguments(), "columns", 2, 1, 3);
            int maxWidth = optionalInt(context.arguments(), "maxWidth", 1280, 320, 1600);
            MediaInspector.Result result = MediaInspector.inspect(
                    loaded.bytes(), loaded.mediaType(), grid, rows, columns, maxWidth);
            List<String> assetIds = new ArrayList<>();
            List<Map<String, Object>> tiles = new ArrayList<>();
            for (MediaInspector.Tile tile : result.tiles()) {
                String assetId = store.store(new MediaAsset(
                        "inspect-" + tile.row() + "-" + tile.column() + ".jpg",
                        "image/jpeg", tile.bytes()));
                assetIds.add(assetId);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("assetId", assetId);
                item.put("row", tile.row());
                item.put("column", tile.column());
                item.put("width", tile.width());
                item.put("height", tile.height());
                tiles.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sourceAssetId", loaded.assetId());
            data.put("mode", grid ? "grid" : "first_frame");
            data.put("rows", grid ? rows : 1);
            data.put("columns", grid ? columns : 1);
            data.put("sourceWidth", result.sourceWidth());
            data.put("sourceHeight", result.sourceHeight());
            data.put("count", assetIds.size());
            data.put("tiles", tiles);
            return ToolResult.ok(JSON.writeValueAsString(data), assetIds);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("MEDIA_INSPECT_FAILED", "图片分块查看失败（内部原因已脱敏）");
        }
    }

    private Loaded load(Map<String, Object> args) throws Exception {
        String assetId = optionalString(args, "assetId", "");
        if (!assetId.isBlank()) {
            ToolAsset asset = store.resolve(assetId).orElseThrow(() ->
                    new IllegalArgumentException("assetId 不存在或已过期"));
            return new Loaded(assetId, asset.mediaType(), asset.bytes());
        }
        String messageKey = required(args, "messageKey");
        ChatMessage message = board.find(messageKey).orElseThrow(() ->
                new IllegalArgumentException("messageKey 不存在"));
        MediaCapability provider = providerFor(message.source().accountId());
        if (provider == null) {
            throw new IllegalArgumentException("该消息所属账号当前不可用或未提供媒体读取能力");
        }
        List<MessageSegment> images = board.segments(messageKey).stream()
                .filter(segment -> segment.type() == MessageSegment.Type.IMAGE)
                .toList();
        int segmentIndex = optionalInt(args, "segmentIndex", 0, 0, Integer.MAX_VALUE);
        MessageSegment image = images.stream()
                .filter(candidate -> candidate.index() == segmentIndex)
                .findFirst()
                .orElseGet(() -> images.isEmpty() ? null : images.get(0));
        if (image == null) {
            throw new IllegalArgumentException("消息中没有图片段");
        }
        String sourceKey = sourceKey(messageKey, image);
        Optional<String> cachedId = store.findBySource(sourceKey);
        if (cachedId.isPresent()) {
            Optional<ToolAsset> cached = store.resolve(cachedId.orElseThrow());
            if (cached.isPresent()) {
                ToolAsset asset = cached.orElseThrow();
                return new Loaded(cachedId.orElseThrow(), asset.mediaType(), asset.bytes());
            }
        }
        MediaAsset asset = provider.image(image).toCompletableFuture().get(20, TimeUnit.SECONDS);
        String storedId = store.store(asset);
        store.rememberSource(sourceKey, storedId);
        return new Loaded(storedId, asset.mediaType(), asset.bytes());
    }

    private MediaCapability providerFor(String accountId) {
        if (singleProvider != null) {
            return singleProvider;
        }
        return providers.get(accountId);
    }

    private static String sourceKey(String messageKey, MessageSegment image) {
        if (!image.fileId().isBlank()) {
            return "file:" + image.fileId();
        }
        if (!image.url().isBlank()) {
            return "url:" + image.url();
        }
        return "message:" + messageKey + ":" + image.index();
    }

    private static String required(Map<String, Object> args, String name) {
        String value = optionalString(args, name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> args, String name, String fallback) {
        Object value = args == null ? null : args.get(name);
        String text = value == null ? "" : value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int optionalInt(Map<String, Object> args, String name,
                                   int fallback, int minimum, int maximum) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        int result = ((Number) value).intValue();
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(name + " 必须在 " + minimum + ".." + maximum);
        }
        return result;
    }

    private record Loaded(String assetId, String mediaType, byte[] bytes) {
    }
}
