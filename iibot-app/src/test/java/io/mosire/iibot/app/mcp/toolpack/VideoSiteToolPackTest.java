package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginPollTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginStartTool;
import io.mosire.iibot.app.bilibili.BiliVideoFramesTool;
import io.mosire.iibot.app.bilibili.BiliVideoReadTool;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.connect.video.core.VideoAccountRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoSiteToolPackTest {

    @TempDir
    Path tempDir;

    @Test
    void standardRuntimeOnlyExposesAnonymousFrames() {
        VideoSiteToolPack pack = new VideoSiteToolPack(List.of(),
                new BotMediaStore(tempDir), false);

        assertThat(names(pack.frameTools())).containsExactly(BiliVideoFramesTool.NAME);
        assertThat(pack.accountTools()).isEmpty();
    }

    @Test
    void productionPackAddsReadAndLoginTools() {
        VideoSiteToolPack anonymous = new VideoSiteToolPack(List.of(),
                new BotMediaStore(tempDir), true);
        assertThat(names(anonymous.accountTools()))
                .containsExactly(BiliVideoReadTool.NAME);

        VideoAccountRef account = new VideoAccountRef("bili-1",
                tempDir.resolve("credential.json"));
        VideoSiteToolPack configured = new VideoSiteToolPack(List.of(account),
                new BotMediaStore(tempDir.resolve("configured")), true);
        assertThat(names(configured.accountTools())).containsExactly(
                BiliVideoReadTool.NAME,
                BiliAccountLoginStartTool.NAME,
                BiliAccountLoginPollTool.NAME);
    }

    private static List<String> names(List<AgentTool> tools) {
        return tools.stream().map(AgentTool::name).toList();
    }
}
