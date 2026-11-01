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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BotMediaInspectToolTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @TempDir
    Path tempDir;

    @Test
    void gridInspectionReturnsBoundedJpegTiles() throws Exception {
        byte[] png = png(64, 32);
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-inspect", "qq-1", "Alice",
                T0, T0, "", "[图片×1]", "");
        board.append(message, List.of(MessageSegment.image(
                0, "file-inspect", "https://example.invalid/inspect.png", "image/png", png.length)));
        MediaCapability provider = segment -> CompletableFuture.completedFuture(
                new MediaAsset("inspect.png", "image/png", png));
        BotMediaStore store = new BotMediaStore(tempDir);
        BotMediaInspectTool tool = new BotMediaInspectTool(board, provider, store);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();

        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(),
                Map.of("messageKey", message.messageKey(), "mode", "grid",
                        "rows", 2, "columns", 2, "maxWidth", 320)));

        assertThat(result.success()).withFailMessage(result.message()).isTrue();
        assertThat(result.assetDocIds()).hasSize(4);
        assertThat(result.message()).contains("\"sourceWidth\"").doesNotContain("https://");
        for (String assetId : result.assetDocIds()) {
            ToolAsset asset = store.resolve(assetId).orElseThrow();
            assertThat(asset.mediaType()).isEqualTo("image/jpeg");
        }
    }

    @Test
    void assetIdFirstFrameIsSupported() throws Exception {
        BotMediaStore store = new BotMediaStore(tempDir);
        String assetId = store.store(new MediaAsset("large.png", "image/png", png(300, 100)));
        BotMediaInspectTool tool = new BotMediaInspectTool(
                new InMemoryMessageBoardStore(),
                segment -> CompletableFuture.failedFuture(new AssertionError("unused")),
                store);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();

        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(),
                Map.of("assetId", assetId, "mode", "first_frame", "maxWidth", 320)));

        assertThat(result.success()).withFailMessage(result.message()).isTrue();
        assertThat(result.assetDocIds()).hasSize(1);
        ToolAsset tile = store.resolve(result.assetDocIds().getFirst()).orElseThrow();
        assertThat(tile.mediaType()).isEqualTo("image/jpeg");
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, (x * 4) | (y * 8) << 8 | 0x336699);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
