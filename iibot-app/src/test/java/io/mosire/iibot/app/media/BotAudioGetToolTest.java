package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MessageSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BotAudioGetToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-30T00:00:00Z");
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");

    @TempDir
    Path tempDir;

    @Test
    void extractsAudioRefWithoutUrlOrTranscript() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        ChatMessage message = ChatMessage.inbound(GROUP, "m-voice", "qq-1", "Alice",
                T0, T0, "", "[语音×1]", "");
        board.append(message, List.of(MessageSegment.audio(0, "voice.silk",
                "https://example.invalid/voice.silk", "audio/silk", 3L)));
        AudioCapability provider = segment -> CompletableFuture.completedFuture(
                new MediaAsset("voice.silk", "audio/silk", new byte[]{7, 8, 9}));
        BotMediaStore store = new BotMediaStore(tempDir);
        BotAudioGetTool tool = new BotAudioGetTool(
                board, Map.of("bot-1", provider), store);

        ToolResult result = execute(tool, Map.of("messageKey", message.messageKey()));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message());
        assertThat(data.path("asrConfigured").asBoolean()).isFalse();
        assertThat(data.path("transcriptAvailable").asBoolean()).isFalse();
        JsonNode audio = data.path("audios").get(0);
        assertThat(audio.path("mediaType").asText()).isEqualTo("audio/silk");
        assertThat(audio.path("size").asInt()).isEqualTo(3);
        assertThat(result.message())
                .doesNotContain("https://example.invalid", "voice.silk", "base64");
        assertThat(store.resolve(audio.path("audioRef").asText()).orElseThrow().bytes())
                .containsExactly(7, 8, 9);
        assertThat(result.assetDocIds()).isEmpty();
    }

    private static ToolResult execute(BotAudioGetTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
