package io.mosire.iibot.app.media;

import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MediaCapability;
import io.mosire.iibot.connect.core.MessageSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BotMediaGetToolTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToGetMessageForLegacyImageWithoutSegments() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "1472304806", "qq-1", "Alice",
                T0, T0, "", "[图片×1]", "");
        board.append(message);
        MessageSegment fallbackImage = MessageSegment.image(
                0, "old.jpg", "https://example.invalid/old.jpg", "image/jpeg", 4);
        MediaCapability provider = new MediaCapability() {
            @Override
            public CompletableFuture<MediaAsset> image(MessageSegment segment) {
                return CompletableFuture.completedFuture(
                        new MediaAsset("old.jpg", "image/jpeg", new byte[]{4, 5, 6, 7}));
            }

            @Override
            public CompletableFuture<List<MessageSegment>> imageSegmentsForMessage(
                    String messageId) {
                return CompletableFuture.completedFuture(List.of(fallbackImage));
            }
        };
        BotMediaStore store = new BotMediaStore(tempDir);
        BotMediaGetTool tool = new BotMediaGetTool(board, provider, store);

        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(),
                Map.of("messageKey", message.messageKey())));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("onebot_get_msg")
                .doesNotContain("https://", "old.jpg");
        ToolAsset asset = store.resolve(result.assetDocIds().getFirst()).orElseThrow();
        assertThat(asset.mediaType()).isEqualTo("image/jpeg");
        assertThat(asset.bytes()).containsExactly(4, 5, 6, 7);
    }

    @Test
    void returnsImageAssetsThroughResolverWithoutExposingUrl() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "", "[图片×1]", "");
        board.append(message, List.of(MessageSegment.image(
                0, "file-1", "https://example.invalid/image.png", "image/png", 3)));
        MediaCapability provider = segment -> CompletableFuture.completedFuture(
                new MediaAsset("image.png", "image/png", new byte[]{1, 2, 3}));
        BotMediaStore store = new BotMediaStore(tempDir);
        BotMediaGetTool tool = new BotMediaGetTool(board, provider, store);

        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(),
                Map.of("messageKey", message.messageKey())));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).hasSize(1);
        assertThat(result.message()).doesNotContain("https://", "file-1");
        ToolAsset asset = store.resolve(result.assetDocIds().getFirst()).orElseThrow();
        assertThat(asset.mediaType()).isEqualTo("image/png");
        assertThat(asset.bytes()).containsExactly(1, 2, 3);
    }
    @Test
    void routesMediaLookupByMessageAccount() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatSourceKey accountB = ChatSourceKey.groupChat("bot-b", "group-b");
        ChatMessage message = ChatMessage.inbound(accountB, "m-b", "qq-b", "Bob",
                T0, T0, "", "[图片×1]", "");
        board.append(message, List.of(MessageSegment.image(
                0, "file-b", "https://example.invalid/b.png", "image/png", 2)));
        MediaCapability providerA = segment -> CompletableFuture.completedFuture(
                new MediaAsset("a.png", "image/png", new byte[]{9, 9}));
        MediaCapability providerB = segment -> CompletableFuture.completedFuture(
                new MediaAsset("b.png", "image/png", new byte[]{7, 8}));
        BotMediaStore store = new BotMediaStore(tempDir);
        BotMediaGetTool tool = new BotMediaGetTool(board,
                Map.of("bot-a", providerA, "bot-b", providerB), store);

        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(),
                Map.of("messageKey", message.messageKey())));

        assertThat(result.success()).isTrue();
        ToolAsset asset = store.resolve(result.assetDocIds().getFirst()).orElseThrow();
        assertThat(asset.bytes()).containsExactly(7, 8);
    }

}
