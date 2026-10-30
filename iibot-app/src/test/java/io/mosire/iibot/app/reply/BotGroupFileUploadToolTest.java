package io.mosire.iibot.app.reply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.GroupFileUploadCapability;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotGroupFileUploadToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void uploadsControlledAssetAndPersistsOutboundThreeState() throws Exception {
        BotMediaStore media = new BotMediaStore(tempDir);
        String assetRef = media.storeUpload(new MediaAsset(
                "三国.json", "application/json", new byte[]{1, 2, 3}));
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        AtomicReference<String> sentName = new AtomicReference<>();
        AtomicReference<byte[]> sentBytes = new AtomicReference<>();
        GroupFileUploadCapability provider = (groupId, fileName, mediaType, bytes,
                                               idempotencyKey) -> {
            sentName.set(fileName);
            sentBytes.set(bytes.clone());
            return CompletableFuture.completedFuture(
                    SendReceipt.sent(List.of("qq-file-1")));
        };
        BotGroupFileUploadTool tool = new BotGroupFileUploadTool(
                board, Map.of("bot-a", provider), media);

        ToolResult result = execute(tool, Map.of(
                "source", "qq_group/bot-a/30003",
                "assetRef", assetRef,
                "fileName", "三国.json",
                "idempotencyKey", "group-file-1"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message());
        assertThat(data.path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(sentName).hasValue("三国.json");
        assertThat(sentBytes.get()).containsExactly(1, 2, 3);
        ChatMessage stored = board.find(ChatMessage.outboundKey(
                ChatSourceKey.groupChat("bot-a", "30003"), "group-file-1"))
                .orElseThrow();
        assertThat(stored.deliveryState()).isEqualTo(DeliveryState.SENT);
        assertThat(stored.nonTextSummary()).contains("三国.json");
        assertThat(board.segments(stored.messageKey()))
                .singleElement().satisfies(segment -> {
                    assertThat(segment.type()).isEqualTo(MessageSegment.Type.FILE);
                    assertThat(segment.text()).isEqualTo("三国.json");
                    assertThat(segment.fileId()).isEqualTo(assetRef);
                });
    }

    @Test
    void rejectsNonGroupSourceAndUnknownAsset() {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        BotGroupFileUploadTool tool = new BotGroupFileUploadTool(board,
                Map.of("bot-a", (groupId, fileName, mediaType, bytes, idempotencyKey) ->
                        CompletableFuture.completedFuture(
                                SendReceipt.sent(List.of("qq-file")))),
                new BotMediaStore(tempDir));

        ToolResult privateSource = execute(tool, Map.of(
                "source", "qq_private/bot-a/777",
                "assetRef", "missing",
                "fileName", "x.bin"));
        assertThat(privateSource.success()).isFalse();
        assertThat(privateSource.code()).isEqualTo("INVALID_ARGUMENTS");

        ToolResult missingAsset = execute(tool, Map.of(
                "source", "qq_group/bot-a/30003",
                "assetRef", "missing",
                "fileName", "x.bin"));
        assertThat(missingAsset.success()).isFalse();
        assertThat(missingAsset.code()).isEqualTo("ASSET_NOT_FOUND");
    }

    private static ToolResult execute(BotGroupFileUploadTool tool,
                                      Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
