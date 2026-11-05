package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.media.MediaAssetSink;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BotMessageGetToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @Test
    void imageMessageReturnsImagesWithAssetIds() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-img", "qq-1", "Alice",
                T0, T0, "看图", "[图片×1]", "");
        board.append(message, List.of(MessageSegment.image(
                0, "file-1", "https://example.invalid/a.png", "image/png", 3)));
        MediaCapability provider = segment -> CompletableFuture.completedFuture(
                new MediaAsset("a.png", "image/png", new byte[]{4, 5, 6}));
        MemorySink sink = new MemorySink();
        BotMessageGetTool tool = new BotMessageGetTool(board,
                Map.of("bot-1", provider), sink);

        ToolResult result = tool.execute(context(tool,
                Map.of("messageKey", message.messageKey())));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).hasSize(1);
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("text").asText()).isEqualTo("看图");
        assertThat(data.path("images")).hasSize(1);
        JsonNode image = data.path("images").get(0);
        assertThat(image.path("segmentIndex").asInt()).isZero();
        assertThat(image.path("assetId").asText())
                .isEqualTo(result.assetDocIds().getFirst());
        assertThat(image.path("mediaType").asText()).isEqualTo("image/png");
        assertThat(image.path("size").asInt()).isEqualTo(3);
        assertThat(image.has("cached")).isFalse();
    }

    @Test
    void plainTextMessageKeepsOriginalShape() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-text", "qq-1", "Alice",
                T0, T0, "纯文本", "", "");
        board.append(message);
        BotMessageGetTool tool = new BotMessageGetTool(board,
                Map.of("bot-1", segment -> {
                    throw new AssertionError("不应读取图片");
                }), new MemorySink());

        ToolResult result = tool.execute(context(tool,
                Map.of("messageKey", message.messageKey())));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).isEmpty();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("text").asText()).isEqualTo("纯文本");
        assertThat(data.has("images")).isFalse();
        assertThat(data.has("errors")).isFalse();
    }

    @Test
    void imageFailureOnlyAddsErrorsWithoutBreakingText() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-img-fail", "qq-1", "Alice",
                T0, T0, "仍在", "[图片×1]", "");
        board.append(message, List.of(MessageSegment.image(
                0, "file-fail", "https://example.invalid/fail.png", "image/png", 3)));
        MediaCapability provider = segment -> CompletableFuture.failedFuture(
                new IllegalStateException("boom"));
        BotMessageGetTool tool = new BotMessageGetTool(board,
                Map.of("bot-1", provider), new MemorySink());

        ToolResult result = tool.execute(context(tool,
                Map.of("messageKey", message.messageKey())));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).isEmpty();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("text").asText()).isEqualTo("仍在");
        assertThat(data.path("images")).isEmpty();
        assertThat(data.path("errors")).hasSize(1);
        assertThat(data.path("errors").get(0).path("code").asText())
                .isEqualTo("MEDIA_UNAVAILABLE");
    }

    private static ToolContext context(BotMessageGetTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args);
    }

    private static final class MemorySink implements MediaAssetSink {
        private final Map<String, ToolAsset> assets = new LinkedHashMap<>();
        private final Map<String, String> sources = new HashMap<>();

        @Override
        public String store(MediaAsset asset) {
            String assetId = "img_" + (assets.size() + 1);
            assets.put(assetId, new ToolAsset(asset.mediaType(), asset.bytes()));
            return assetId;
        }

        @Override
        public Optional<String> findBySource(String sourceKey) {
            return Optional.ofNullable(sources.get(sourceKey));
        }

        @Override
        public Optional<ToolAsset> resolve(String assetId) {
            return Optional.ofNullable(assets.get(assetId));
        }

        @Override
        public void rememberSource(String sourceKey, String assetId) {
            sources.put(sourceKey, assetId);
        }
    }
}
