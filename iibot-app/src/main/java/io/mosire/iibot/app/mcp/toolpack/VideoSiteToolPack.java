package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginPollTool;
import io.mosire.iibot.app.bilibili.BiliAccountLoginStartTool;
import io.mosire.iibot.app.bilibili.BiliVideoCommentsTool;
import io.mosire.iibot.app.bilibili.BiliVideoFramesTool;
import io.mosire.iibot.app.bilibili.BiliVideoReadTool;
import io.mosire.iibot.app.bot.BotConfig;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.connect.video.bilibili.BiliLoginService;
import io.mosire.iibot.connect.video.bilibili.BilibiliHttpClient;
import io.mosire.iibot.connect.video.core.VideoAccountRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** B 站/视频站工具包：匿名分帧、评论查询、通用读取与账号扫码登录。 */
public final class VideoSiteToolPack {

    private final List<VideoAccountRef> accounts;
    private final BotMediaStore mediaStore;
    private final boolean readEnabled;
    private final BotConfig.Video video;
    private final BiliLoginService loginService;

    public VideoSiteToolPack(List<VideoAccountRef> accounts, BotMediaStore mediaStore,
                             boolean readEnabled) {
        this(accounts, mediaStore, readEnabled, BotConfig.Video.defaults());
    }

    public VideoSiteToolPack(List<VideoAccountRef> accounts, BotMediaStore mediaStore,
                             boolean readEnabled, BotConfig.Video video) {
        this.accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.readEnabled = readEnabled;
        this.video = Objects.requireNonNull(video, "video");
        this.loginService = accounts.isEmpty() ? null : new BiliLoginService(accounts);
    }

    /** 分帧工具始终注册；保留 bili_* 兼容名并提供 video_* 标准名。 */
    public List<AgentTool> frameTools() {
        return List.of(
                new BiliVideoFramesTool(mediaStore, BiliVideoFramesTool.NAME,
                        video.defaultQuality(), video.maxHeight(),
                        video.defaultFrameCount(), video.maxFrameCount()),
                new BiliVideoFramesTool(mediaStore, "video_frames",
                        video.defaultQuality(), video.maxHeight(),
                        video.defaultFrameCount(), video.maxFrameCount()));
    }

    /** 评论按需读取；不做全量扫描。 */
    public List<AgentTool> commentTools() {
        BotConfig.Comments comments = video.comments();
        return List.of(new BiliVideoCommentsTool(
                BilibiliHttpClient.API_BASE, BilibiliHttpClient.VC_API_BASE,
                comments.defaultLimit(), comments.maxLimit(), comments.allowFullScan()));
    }

    /** 生产目录注册通用读取；有账号时才追加扫码登录工具。 */
    public List<AgentTool> accountTools() {
        if (!readEnabled) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new BiliVideoReadTool(accounts));
        tools.add(new BiliVideoReadTool(accounts, "video_read"));
        if (loginService != null) {
            tools.add(new BiliAccountLoginStartTool(loginService));
            tools.add(new BiliAccountLoginPollTool(loginService));
        }
        return List.copyOf(tools);
    }
}
