package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.media.MessageImageAssets;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按需把消息中的图片取回为 MCP ImageContent。 */
public final class BotMediaGetTool implements AgentTool {

    public static final String NAME = "qq_media_get";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 4,
                            "description", "最多返回图片数，默认 4"),
                    "saveTo", Map.of("type", "string", "maxLength", 512,
                            "description", "相对 downloads 的目录/文件名；提供时把图片落盘并"
                                    + "返回 saved（省略则只返回资产，不落盘）")),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final MediaCapability singleMediaProvider;
    private final Map<String, MediaCapability> mediaProviders;
    private final BotMediaStore mediaStore;
    private final MediaDownloadSink downloadSink;

    /** 单账号兼容构造器。 */
    public BotMediaGetTool(MessageBoardStore board, MediaCapability mediaProvider,
                           BotMediaStore mediaStore) {
        this(board, mediaProvider, mediaStore, null);
    }

    /** 单账号构造器，允许显式指定下载落点。 */
    public BotMediaGetTool(MessageBoardStore board, MediaCapability mediaProvider,
                           BotMediaStore mediaStore, MediaDownloadSink downloadSink) {
        this.board = Objects.requireNonNull(board, "board");
        this.singleMediaProvider = Objects.requireNonNull(mediaProvider, "mediaProvider");
        this.mediaProviders = Map.of();
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.downloadSink = downloadSink;
    }

    /**
     * 多账号构造器。Map 中应包含所有已启动账号；value=null 表示账号存在但不支持媒体读取。
     */
    public BotMediaGetTool(MessageBoardStore board,
                           Map<String, MediaCapability> mediaProviders,
                           BotMediaStore mediaStore) {
        this(board, mediaProviders, mediaStore, null);
    }

    /** 多账号构造器，允许显式指定下载落点。 */
    public BotMediaGetTool(MessageBoardStore board,
                           Map<String, MediaCapability> mediaProviders,
                           BotMediaStore mediaStore, MediaDownloadSink downloadSink) {
        this.board = Objects.requireNonNull(board, "board");
        Objects.requireNonNull(mediaProviders, "mediaProviders");
        this.singleMediaProvider = null;
        this.mediaProviders = Collections.unmodifiableMap(
                new LinkedHashMap<>(mediaProviders));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.downloadSink = downloadSink;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按需读取一条消息中的图片，并作为 MCP ImageContent 返回；不暴露原始 URL。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageKey = required(context.arguments(), "messageKey");
            ChatMessage message = board.find(messageKey).orElseThrow(() ->
                    new IllegalArgumentException("messageKey 不存在"));
            MediaCapability mediaProvider = providerFor(message.source().accountId());
            if (mediaProvider == null) {
                return ToolResult.error("ACCOUNT_UNAVAILABLE",
                        "该消息所属账号当前不可用或未提供媒体读取能力");
            }
            int limit = optionalInt(context.arguments(), "limit", 4);
            boolean saveRequested = context.arguments() != null
                    && context.arguments().containsKey("saveTo");
            if (saveRequested && downloadSink == null) {
                return ToolResult.error("MEDIA_UNAVAILABLE", "下载落点当前不可用");
            }
            String saveTo = saveRequested
                    ? optionalText(context.arguments(), "saveTo", 512)
                    : "";
            if (saveRequested) {
                downloadSink.validate(saveTo);
            }

            MessageImageAssets.Result result = MessageImageAssets.read(
                    board, mediaProvider, mediaStore, message, limit);
            if (result.failed()) {
                return ToolResult.error(result.failureCode(), result.failureMessage());
            }
            List<String> assetIds = new ArrayList<>();
            List<Map<String, Object>> metadata = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            for (MessageImageAssets.Image image : result.images()) {
                assetIds.add(image.assetId());
                metadata.add(mediaMetadata(image));
            }
            for (MessageImageAssets.Error error : result.errors()) {
                errors.add(error(error.segmentIndex(), error.code()));
            }
            List<String> saved = new ArrayList<>();
            if (saveRequested) {
                for (MessageImageAssets.Image image : result.images()) {
                    ToolAsset asset = mediaStore.resolve(image.assetId()).orElse(null);
                    if (asset == null) {
                        errors.add(error(image.segmentIndex(), "MEDIA_UNAVAILABLE"));
                        continue;
                    }
                    String fileName = MediaDownloadSink.defaultFileName(
                            messageKey, image.segmentIndex(), image.mediaType());
                    saved.add(downloadSink.save(saveTo, fileName, asset.bytes()).toString());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", message.source().sourceAddress().canonical());
            data.put("threadId", message.source().threadId());
            data.put("messageKey", messageKey);
            data.put("count", assetIds.size());
            data.put("retrievalSource",
                    result.fallbackUsed() ? "onebot_get_msg" : "local_segments");
            data.put("images", metadata);
            data.put("errors", errors);
            data.put("saved", saved);
            return ToolResult.ok(JSON.writeValueAsString(data), assetIds);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("MEDIA_UNAVAILABLE", "图片读取失败（内部原因已脱敏）");
        }
    }

    private static Map<String, Object> mediaMetadata(MessageImageAssets.Image image) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("segmentIndex", image.segmentIndex());
        item.put("assetId", image.assetId());
        item.put("sha256", sha256Of(image.assetId()));
        item.put("mediaType", image.mediaType());
        item.put("size", image.size());
        item.put("cached", image.cached());
        return item;
    }

    private static Map<String, Object> error(int segmentIndex, String code) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("segmentIndex", segmentIndex);
        item.put("code", code);
        return item;
    }

    private static String sha256Of(String assetId) {
        int underscore = assetId == null ? -1 : assetId.indexOf('_');
        return underscore >= 0 && underscore + 1 < assetId.length()
                ? assetId.substring(underscore + 1) : assetId;
    }

    private MediaCapability providerFor(String accountId) {
        if (singleMediaProvider != null) {
            return singleMediaProvider;
        }
        if (!mediaProviders.containsKey(accountId)) {
            return null;
        }
        return mediaProviders.get(accountId);
    }

    private static String required(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> args, String name, int maxLength) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " 必须是字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 超过长度上限");
        }
        return normalized;
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        int result = ((Number) value).intValue();
        if (result < 1 || result > 4) {
            throw new IllegalArgumentException(name + " 必须在 1..4");
        }
        return result;
    }
}
