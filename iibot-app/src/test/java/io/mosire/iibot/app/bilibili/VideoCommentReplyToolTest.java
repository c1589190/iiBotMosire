package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.video.core.VideoAccountRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VideoCommentReplyToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void sendsCommentReplyThroughExternalThread() throws Exception {
        AtomicReference<String> sentText = new AtomicReference<>();
        AtomicReference<String> sentReplyTo = new AtomicReference<>();
        BotReplyService service = new BotReplyService(new InMemoryMessageBoardStore(),
                (source, text, replyTo, key) -> {
                    sentText.set(text);
                    sentReplyTo.set(replyTo);
                    return new BotReplySendResult(DeliveryState.SENT,
                            List.of("rpid-1003"), "");
                }, Clock.systemUTC());
        VideoCommentReplyTool tool = new VideoCommentReplyTool(
                List.of(new VideoAccountRef("bili-1", tempDir.resolve("credential.json"))),
                service);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "rootRpid", "1001",
                "replyToRpid", "1002", "text", "回复", "idempotencyKey", "comment-1"));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("source").asText()).isEqualTo(
                MessageSourceAddress.biliComment("bili-1", "BV1EPnG6DEJC", "1001")
                        .canonical());
        assertThat(data.path("deliveryState").asText()).isEqualTo("SENT");
        assertThat(sentText.get()).isEqualTo("回复");
        assertThat(sentReplyTo.get()).isEqualTo("1002");
    }

    @Test
    void rejectsReplyToWithoutRoot() {
        BotReplyService service = new BotReplyService(new InMemoryMessageBoardStore(),
                (source, text, replyTo, key) -> new BotReplySendResult(
                        DeliveryState.SENT, List.of("rpid"), ""),
                Clock.systemUTC());
        VideoCommentReplyTool tool = new VideoCommentReplyTool(
                List.of(new VideoAccountRef("bili-1", tempDir.resolve("credential.json"))),
                service);

        ToolResult result = execute(tool, Map.of(
                "target", "BV1EPnG6DEJC", "replyToRpid", "1002", "text", "回复"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENTS");
    }

    private static ToolResult execute(VideoCommentReplyTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
