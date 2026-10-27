package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 按需把消息中的图片取回为 MCP ImageContent。 */
public final class BotMediaGetTool implements AgentTool {

    public static final String NAME = "bot_media_get";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 4,
                            "description", "最多返回图片数，默认 4")),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final MediaCapability mediaProvider;
    private final BotMediaStore mediaStore;

    public BotMediaGetTool(MessageBoardStore board, MediaCapability mediaProvider,
                           BotMediaStore mediaStore) {
        this.board = Objects.requireNonNull(board, "board");
        this.mediaProvider = Objects.requireNonNull(mediaProvider, "mediaProvider");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
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
            int limit = optionalInt(context.arguments(), "limit", 4);
            List<MessageSegment> images = board.segments(messageKey).stream()
                    .filter(segment -> segment.type() == MessageSegment.Type.IMAGE)
                    .limit(limit)
                    .toList();
            boolean fallbackUsed = false;
            if (images.isEmpty() && message.messageId() != null
                    && !message.messageId().isBlank()) {
                try {
                    images = mediaProvider.imageSegmentsForMessage(message.messageId())
                            .toCompletableFuture().get(20, TimeUnit.SECONDS).stream()
                            .filter(segment -> segment.type() == MessageSegment.Type.IMAGE)
                            .limit(limit)
                            .toList();
                    fallbackUsed = true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("MEDIA_UNAVAILABLE", "图片回退读取被中断");
                } catch (Exception failure) {
                    return ToolResult.error("MEDIA_UNAVAILABLE",
                            "OneBot 图片回退失败（内部原因已脱敏）");
                }
            }
            if (images.isEmpty()) {
                return ToolResult.error("MEDIA_NOT_FOUND", "消息中没有图片段");
            }
            List<String> assetIds = new ArrayList<>();
            List<Map<String, Object>> metadata = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            for (MessageSegment image : images) {
                try {
                    MediaAsset asset = mediaProvider.image(image)
                            .toCompletableFuture().get(20, TimeUnit.SECONDS);
                    String assetId = mediaStore.store(asset);
                    assetIds.add(assetId);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("segmentIndex", image.index());
                    item.put("mediaType", asset.mediaType());
                    item.put("size", asset.size());
                    metadata.add(item);
                } catch (Exception failure) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("segmentIndex", image.index());
                    item.put("code", "MEDIA_UNAVAILABLE");
                    errors.add(item);
                }
            }
            if (assetIds.isEmpty()) {
                return ToolResult.error("MEDIA_UNAVAILABLE", "图片读取失败（内部原因已脱敏）");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("threadId", message.source().threadId());
            data.put("messageKey", messageKey);
            data.put("count", assetIds.size());
            data.put("source", fallbackUsed ? "onebot_get_msg" : "local_segments");
            data.put("images", metadata);
            data.put("errors", errors);
            return ToolResult.ok(JSON.writeValueAsString(data), assetIds);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("MEDIA_UNAVAILABLE", "图片读取失败（内部原因已脱敏）");
        }
    }

    private static String required(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
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
