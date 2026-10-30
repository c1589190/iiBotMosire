package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.ForwardMessage;
import io.mosire.iibot.connect.core.ForwardPage;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 读取合并转发节点内的图片，并以受控 MCP ImageContent 返回。 */
public final class BotForwardMediaGetTool implements AgentTool {

    public static final String NAME = "qq_forward_media_get";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_IMAGES = 4;
    private static final int MAX_FORWARD_NODES = 50;
    private static final long TIMEOUT_SECONDS = 20L;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1),
                    "segmentIndex", Map.of("type", "integer", "minimum", 0,
                            "description", "外层消息中的 forward 段下标；省略取第一个"),
                    "nodeIndex", Map.of("type", "integer", "minimum", 0,
                            "description", "顶层转发节点下标；省略时顺序扫描"),
                    "limit", Map.of("type", "integer", "minimum", 1,
                            "maximum", MAX_IMAGES,
                            "description", "最多返回图片数，默认 4")),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final Map<String, ForwardCapability> forwardProviders;
    private final Map<String, MediaCapability> mediaProviders;
    private final BotMediaStore mediaStore;

    public BotForwardMediaGetTool(MessageBoardStore board,
                                  Map<String, ForwardCapability> forwardProviders,
                                  Map<String, MediaCapability> mediaProviders,
                                  BotMediaStore mediaStore) {
        this.board = Objects.requireNonNull(board, "board");
        Objects.requireNonNull(forwardProviders, "forwardProviders");
        Objects.requireNonNull(mediaProviders, "mediaProviders");
        this.forwardProviders = Collections.unmodifiableMap(
                new LinkedHashMap<>(forwardProviders));
        this.mediaProviders = Collections.unmodifiableMap(
                new LinkedHashMap<>(mediaProviders));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取一条合并转发节点内的图片，并以 MCP ImageContent 返回；不暴露 URL、"
                + "file id 或本地路径。仅扫描顶层转发页，不支持递归转发内媒体。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageKey = requiredText(context.arguments(), "messageKey");
            ChatMessage message = board.find(messageKey).orElseThrow(() ->
                    new InvalidArgumentsException("messageKey 不存在"));
            String accountId = message.source().accountId();
            ForwardCapability forwardProvider = forwardProviders.get(accountId);
            MediaCapability mediaProvider = mediaProviders.get(accountId);
            if (forwardProvider == null || mediaProvider == null) {
                return ToolResult.error("ACCOUNT_UNAVAILABLE",
                        "该消息所属账号当前不可用或未提供转发媒体能力");
            }

            List<MessageSegment> segments = board.segments(messageKey);
            MessageSegment forward = selectForward(segments,
                    optionalInt(context.arguments(), "segmentIndex", -1, 0, Integer.MAX_VALUE));
            if (forward == null) {
                return ToolResult.error("FORWARD_NOT_FOUND", "消息中没有可读取的 forward 段");
            }
            int nodeIndex = optionalInt(context.arguments(), "nodeIndex", -1,
                    0, Integer.MAX_VALUE);
            int limit = optionalInt(context.arguments(), "limit", MAX_IMAGES,
                    1, MAX_IMAGES);

            ForwardPage page = forwardProvider.forward(forward.forwardId(), MAX_FORWARD_NODES)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (page.messages().isEmpty()) {
                return ToolResult.error("FORWARD_EMPTY", "合并转发没有可读取节点");
            }
            if (nodeIndex >= page.messages().size()) {
                throw new InvalidArgumentsException("nodeIndex 超出转发节点范围");
            }

            List<Candidate> candidates = new ArrayList<>();
            boolean nestedUnsupported = false;
            int first = nodeIndex >= 0 ? nodeIndex : 0;
            int last = nodeIndex >= 0 ? nodeIndex + 1 : page.messages().size();
            for (int index = first; index < last && candidates.size() < limit; index++) {
                ForwardMessage node = page.messages().get(index);
                for (MessageSegment segment : node.segments()) {
                    if (segment.type() == MessageSegment.Type.IMAGE
                            && candidates.size() < limit) {
                        candidates.add(new Candidate(index, node.senderName(), segment));
                    } else if (segment.type() == MessageSegment.Type.FORWARD) {
                        nestedUnsupported = true;
                    }
                }
            }
            if (candidates.isEmpty()) {
                return ToolResult.error("MEDIA_NOT_FOUND",
                        nestedUnsupported
                                ? "转发节点中没有顶层图片段；嵌套转发未展开"
                                : "转发节点中没有图片段");
            }

            List<String> assetIds = new ArrayList<>();
            List<Map<String, Object>> metadata = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            for (Candidate candidate : candidates) {
                try {
                    MediaAsset asset = mediaProvider.image(candidate.segment())
                            .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assetIds.add(mediaStore.store(asset));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("nodeIndex", candidate.nodeIndex());
                    item.put("segmentIndex", candidate.segment().index());
                    item.put("senderName", candidate.senderName());
                    item.put("mediaType", asset.mediaType());
                    item.put("size", asset.size());
                    metadata.add(item);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("MEDIA_UNAVAILABLE", "转发图片读取被中断");
                } catch (Exception failure) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("nodeIndex", candidate.nodeIndex());
                    item.put("segmentIndex", candidate.segment().index());
                    item.put("code", "MEDIA_UNAVAILABLE");
                    errors.add(item);
                }
            }
            if (assetIds.isEmpty()) {
                return ToolResult.error("MEDIA_UNAVAILABLE",
                        "转发图片读取失败（内部原因已脱敏）");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageKey", messageKey);
            data.put("forwardSegmentIndex", forward.index());
            data.put("nodeCount", page.messages().size());
            data.put("count", assetIds.size());
            data.put("truncated", page.truncated());
            data.put("nestedUnsupported", nestedUnsupported);
            data.put("images", metadata);
            data.put("errors", errors);
            return ToolResult.ok(JSON.writeValueAsString(data), assetIds);
        } catch (InvalidArgumentsException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error("MEDIA_UNAVAILABLE", "转发图片读取被中断");
        } catch (Exception failure) {
            return ToolResult.error("FORWARD_MEDIA_UNAVAILABLE",
                    "转发图片读取失败（内部原因已脱敏）");
        }
    }

    private static MessageSegment selectForward(List<MessageSegment> segments, int index) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        if (index >= 0) {
            return segments.stream()
                    .filter(segment -> segment.index() == index)
                    .filter(segment -> segment.type() == MessageSegment.Type.FORWARD)
                    .filter(segment -> !segment.forwardId().isBlank())
                    .findFirst().orElse(null);
        }
        return segments.stream()
                .filter(segment -> segment.type() == MessageSegment.Type.FORWARD)
                .filter(segment -> !segment.forwardId().isBlank())
                .findFirst().orElse(null);
    }

    private static String requiredText(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidArgumentsException(name + " 不能为空");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback,
                                   int minimum, int maximum) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidArgumentsException(name + " 必须是整数");
        }
        int result = number.intValue();
        if (result < minimum || result > maximum) {
            throw new InvalidArgumentsException(
                    name + " 必须在 " + minimum + ".." + maximum);
        }
        return result;
    }

    private record Candidate(int nodeIndex, String senderName, MessageSegment segment) {
    }

    private static final class InvalidArgumentsException extends RuntimeException {
        private InvalidArgumentsException(String message) {
            super(message);
        }
    }
}
