package io.mosire.iibot.app.media;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.ForwardCapability;
import io.mosire.iibot.connect.core.ForwardMessage;
import io.mosire.iibot.connect.core.ForwardPage;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BotForwardMediaGetToolTest {

    private static final Instant T0 = Instant.parse("2026-10-29T00:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @TempDir
    Path tempDir;

    @Test
    void returnsForwardImagesAsAssetsWithoutRawLocators() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-forward", "qq-1", "Alice",
                T0, T0, "", "[转发×1]", "");
        board.append(message, List.of(MessageSegment.forward(0, "forward-secret")));

        MessageSegment firstImage = MessageSegment.image(1, "file-a",
                "https://example.invalid/a.png", "image/png", 2);
        MessageSegment secondImage = MessageSegment.image(0, "file-b",
                "https://example.invalid/b.jpg", "image/jpeg", 3);
        ForwardCapability forwards = (forwardId, limit) -> {
            assertThat(forwardId).isEqualTo("forward-secret");
            return CompletableFuture.completedFuture(new ForwardPage(List.of(
                    new ForwardMessage("qq-a", "Alice", T0, "A", List.of(
                            MessageSegment.text(0, "A"), firstImage)),
                    new ForwardMessage("qq-b", "Bob", T0, "B", List.of(secondImage))
            ), false));
        };
        AtomicInteger downloads = new AtomicInteger();
        MediaCapability media = segment -> {
            downloads.incrementAndGet();
            assertThat(segment.type()).isEqualTo(MessageSegment.Type.IMAGE);
            return CompletableFuture.completedFuture(new MediaAsset(
                    segment.fileId() + ".bin", segment.mediaType(),
                    segment.fileId().equals("file-a")
                            ? new byte[]{1, 2} : new byte[]{3, 4, 5}));
        };
        BotMediaStore store = new BotMediaStore(tempDir);
        BotForwardMediaGetTool tool = new BotForwardMediaGetTool(board,
                Map.of("bot-1", forwards), Map.of("bot-1", media), store);

        ToolResult result = execute(tool, Map.of("messageKey", message.messageKey()));

        assertThat(result.success()).isTrue();
        assertThat(result.assetDocIds()).hasSize(2);
        assertThat(downloads).hasValue(2);
        assertThat(result.message())
                .contains("\"nodeIndex\":0", "\"nodeIndex\":1", "\"count\":2")
                .doesNotContain("forward-secret", "file-a", "file-b",
                        "https://example.invalid");
        assertThat(store.resolve(result.assetDocIds().get(0)).orElseThrow().mediaType())
                .isEqualTo("image/png");
    }

    private static ToolResult execute(BotForwardMediaGetTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
