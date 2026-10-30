package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginPollTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginStartTool;
import io.mosire.iibot.app.bilibili.BiliVideoFramesTool;
import io.mosire.iibot.app.bilibili.BiliVideoReadTool;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.connect.video.bilibili.BiliLoginService;
import io.mosire.iibot.connect.video.core.VideoAccountRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** B 站/视频站工具包：匿名分帧、通用读取与账号扫码登录。 */
public final class VideoSiteToolPack {

    private final List<VideoAccountRef> accounts;
    private final BotMediaStore mediaStore;
    private final boolean readEnabled;
    private final BiliLoginService loginService;

    public VideoSiteToolPack(List<VideoAccountRef> accounts, BotMediaStore mediaStore,
                             boolean readEnabled) {
        this.accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.readEnabled = readEnabled;
        this.loginService = accounts.isEmpty() ? null : new BiliLoginService(accounts);
    }

    /** 分帧工具始终注册；无账号时使用匿名公开视频流。 */
    public List<AgentTool> frameTools() {
        return List.of(new BiliVideoFramesTool(mediaStore));
    }

    /** 生产目录注册通用读取；有账号时才追加扫码登录工具。 */
    public List<AgentTool> accountTools() {
        if (!readEnabled) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new BiliVideoReadTool(accounts));
        if (loginService != null) {
            tools.add(new BiliAccountLoginStartTool(loginService));
            tools.add(new BiliAccountLoginPollTool(loginService));
        }
        return List.copyOf(tools);
    }
}
