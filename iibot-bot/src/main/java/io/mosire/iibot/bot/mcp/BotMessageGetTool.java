package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.media.MediaAssetSink;
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

/** 按 messageKey 读取一条完整消息；消息含图片时默认附带受控图片资产。 */
public final class BotMessageGetTool implements AgentTool {

    public static final String NAME = "qq_message_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1)),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final Map<String, MediaCapability> mediaProviders;
    private final MediaAssetSink mediaSink;

    public BotMessageGetTool(MessageBoardStore board) {
        this(board, Map.of(), null);
    }

    /**
     * 带图片能力与资产落库端口的构造器；两者任一为 null 时退回纯文本返回形状。
     */
    public BotMessageGetTool(MessageBoardStore board,
                             Map<String, MediaCapability> mediaProviders,
                             MediaAssetSink mediaSink) {
        this.board = Objects.requireNonNull(board, "board");
        this.mediaProviders = mediaProviders == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(mediaProviders));
        this.mediaSink = mediaSink;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按稳定 messageKey 读取一条完整消息；含图片时默认附带最多 4 个图片资产。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageKey = BotToolSupport.requiredText(
                    context.arguments(), "messageKey", 1024);
            ChatMessage message = board.find(messageKey)
                    .orElseThrow(() -> new BotToolSupport.InvalidArgumentsException(
                            "messageKey 不存在"));
            List<MessageSegment> segments = board.segments(message.messageKey());
            Map<String, Object> data = BotToolSupport.messageMap(message, segments);
            List<String> assetIds = new ArrayList<>();
            MediaCapability provider = mediaProviders.get(message.source().accountId());
            boolean hasImage = segments.stream()
                    .anyMatch(segment -> segment.type() == MessageSegment.Type.IMAGE);
            if (mediaSink != null && provider != null && hasImage) {
                attachImages(data, assetIds, provider, message);
            }
            return BotToolSupport.ok(NAME, data, assetIds);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("MESSAGE_NOT_FOUND", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("MESSAGE_READ_UNAVAILABLE",
                    "消息读取暂不可用（内部原因已脱敏）");
        }
    }

    private void attachImages(Map<String, Object> data, List<String> assetIds,
                              MediaCapability provider, ChatMessage message) {
        MessageImageAssets.Result result;
        try {
            result = MessageImageAssets.read(
                    board, provider, mediaSink, message, MessageImageAssets.DEFAULT_LIMIT);
        } catch (RuntimeException failure) {
            data.put("errors", List.of(globalError("MEDIA_UNAVAILABLE",
                    "图片资产读取失败（内部原因已脱敏）")));
            return;
        }
        if (!result.hasImages()) {
            List<Map<String, Object>> errors = segmentErrors(result.errors());
            if (errors.isEmpty()) {
                errors = List.of(globalError(result.failureCode(),
                        result.failureMessage()));
            }
            data.put("errors", errors);
            return;
        }
        List<Map<String, Object>> images = new ArrayList<>();
        for (MessageImageAssets.Image image : result.images()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("segmentIndex", image.segmentIndex());
            item.put("assetId", image.assetId());
            item.put("mediaType", image.mediaType());
            item.put("size", image.size());
            images.add(item);
            assetIds.add(image.assetId());
        }
        data.put("images", images);
        List<Map<String, Object>> errors = segmentErrors(result.errors());
        if (!errors.isEmpty()) {
            data.put("errors", errors);
        }
    }

    private static List<Map<String, Object>> segmentErrors(
            List<MessageImageAssets.Error> source) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (MessageImageAssets.Error error : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("segmentIndex", error.segmentIndex());
            item.put("code", error.code());
            errors.add(item);
        }
        return errors;
    }

    private static Map<String, Object> globalError(String code, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code == null ? "MEDIA_UNAVAILABLE" : code);
        item.put("message", message == null ? "图片资产读取失败（内部原因已脱敏）" : message);
        return item;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (context.arguments().containsKey("messageKey")) {
            safe.put("messageKey", context.arguments().get("messageKey"));
        }
        return Map.copyOf(safe);
    }
}
