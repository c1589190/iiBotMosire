package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginPollTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginStartTool;
import io.mosire.iibot.app.bilibili.BiliVideoCommentsTool;
import io.mosire.iibot.app.bilibili.BiliVideoFramesTool;
import io.mosire.iibot.app.bilibili.BiliVideoReadTool;
import io.mosire.iibot.app.bilibili.VideoCommentReplyTool;
import io.mosire.iibot.app.bilibili.VideoInteractionTool;
import io.mosire.iibot.app.bot.BotConfig;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.connect.video.core.VideoAccountRef;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.connect.video.core.VideoQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoSiteToolPackTest {

    @TempDir
    Path tempDir;

    @Test
    void standardRuntimeOnlyExposesAnonymousFrames() {
        VideoSiteToolPack pack = new VideoSiteToolPack(List.of(),
                new BotMediaStore(tempDir), false);

        assertThat(names(pack.frameTools())).containsExactly(
                BiliVideoFramesTool.NAME, "video_frames");
        assertThat(names(pack.commentTools())).containsExactly(BiliVideoCommentsTool.NAME);
        assertThat(pack.accountTools()).isEmpty();
    }

    @Test
    void productionPackAddsReadAndLoginTools() {
        VideoSiteToolPack anonymous = new VideoSiteToolPack(List.of(),
                new BotMediaStore(tempDir), true);
        assertThat(names(anonymous.accountTools()))
                .containsExactly(BiliVideoReadTool.NAME, "video_read");

        VideoAccountRef account = new VideoAccountRef("bili-1",
                tempDir.resolve("credential.json"));
        VideoSiteToolPack configured = new VideoSiteToolPack(List.of(account),
                new BotMediaStore(tempDir.resolve("configured")), true);
        assertThat(names(configured.accountTools())).containsExactly(
                BiliVideoReadTool.NAME, "video_read",
                BiliAccountLoginStartTool.NAME,
                BiliAccountLoginPollTool.NAME);
    }



    @Test
    void productionPackRegistersExplicitWriteTools() {
        VideoAccountRef account = new VideoAccountRef("bili-1",
                tempDir.resolve("credential.json"));
        BotReplyService replies = new BotReplyService(new InMemoryMessageBoardStore(),
                (source, text, replyTo, key) -> new BotReplySendResult(
                        DeliveryState.SENT, List.of("comment-1"), ""),
                Clock.systemUTC());
        VideoSiteToolPack pack = new VideoSiteToolPack(List.of(account),
                new BotMediaStore(tempDir.resolve("write")), true,
                BotConfig.Video.defaults(), tempDir, replies, new AccountRegistry());

        assertThat(names(pack.writeTools())).containsExactly(
                VideoCommentReplyTool.NAME,
                VideoInteractionTool.Action.LIKE.toolName(),
                VideoInteractionTool.Action.COIN.toolName(),
                VideoInteractionTool.Action.FAVORITE.toolName(),
                VideoInteractionTool.Action.FOLLOW.toolName());
    }

    @Test
    void remoteCdpConfigBuildsFrameToolsWithoutConnecting() {
        BotConfig.Browser browser = new BotConfig.Browser(true, "remote-cdp", "",
                "http://127.0.0.1:9222", true, 2, 60, "", 1280, 720);
        BotConfig.Video video = new BotConfig.Video(VideoQuality.AUTO, 720, 6, 9,
                BotConfig.Comments.defaults(), browser);
        VideoSiteToolPack pack = new VideoSiteToolPack(List.of(),
                new BotMediaStore(tempDir.resolve("remote")), false, video, tempDir);

        assertThat(names(pack.frameTools())).containsExactly(
                BiliVideoFramesTool.NAME, "video_frames");
    }

    private static List<String> names(List<AgentTool> tools) {
        return tools.stream().map(AgentTool::name).toList();
    }
}
