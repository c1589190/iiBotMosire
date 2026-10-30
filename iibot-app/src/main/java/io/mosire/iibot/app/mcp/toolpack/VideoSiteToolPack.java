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
import io.mosire.iibot.connect.video.bilibili.BilibiliBrowserTarget;
import io.mosire.iibot.connect.video.bilibili.BilibiliHttpClient;
import io.mosire.iibot.connect.video.browser.BrowserBackend;
import io.mosire.iibot.connect.video.browser.BrowserPolicy;
import io.mosire.iibot.connect.video.browser.BrowserProfile;
import io.mosire.iibot.connect.video.browser.PlaywrightBrowserBackend;
import io.mosire.iibot.connect.video.browser.RemoteCdpBrowserBackend;
import io.mosire.iibot.connect.video.core.VideoAccountRef;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** B 站/视频站工具包：匿名分帧、评论查询、通用读取与账号扫码登录。 */
public final class VideoSiteToolPack {

    private final List<VideoAccountRef> accounts;
    private final BotMediaStore mediaStore;
    private final boolean readEnabled;
    private final BotConfig.Video video;
    private final Path dataDir;
    private final BiliLoginService loginService;

    public VideoSiteToolPack(List<VideoAccountRef> accounts, BotMediaStore mediaStore,
                             boolean readEnabled) {
        this(accounts, mediaStore, readEnabled, BotConfig.Video.defaults());
    }

    public VideoSiteToolPack(List<VideoAccountRef> accounts, BotMediaStore mediaStore,
                             boolean readEnabled, BotConfig.Video video) {
        this(accounts, mediaStore, readEnabled, video,
                Path.of(System.getProperty("java.io.tmpdir")));
    }

    public VideoSiteToolPack(List<VideoAccountRef> accounts, BotMediaStore mediaStore,
                             boolean readEnabled, BotConfig.Video video, Path dataDir) {
        this.accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.readEnabled = readEnabled;
        this.video = Objects.requireNonNull(video, "video");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize();
        this.loginService = accounts.isEmpty() ? null : new BiliLoginService(accounts);
    }

    /** 分帧工具始终注册；浏览器仅在配置启用时作为 ffmpeg 失败 fallback。 */
    public List<AgentTool> frameTools() {
        BrowserProfile profile = browserProfile();
        BrowserBackend backend = browserBackend();
        return List.of(
                new BiliVideoFramesTool(mediaStore, BiliVideoFramesTool.NAME,
                        video.defaultQuality(), video.maxHeight(),
                        video.defaultFrameCount(), video.maxFrameCount(),
                        backend, profile),
                new BiliVideoFramesTool(mediaStore, "video_frames",
                        video.defaultQuality(), video.maxHeight(),
                        video.defaultFrameCount(), video.maxFrameCount(),
                        backend, profile));
    }

    private BrowserBackend browserBackend() {
        BotConfig.Browser browser = video.browser();
        if (!browser.enabled()) {
            return null;
        }
        if ("remote-cdp".equals(browser.mode())) {
            return new RemoteCdpBrowserBackend(new BilibiliBrowserTarget(),
                    BrowserPolicy.bilibili(), browser.cdpEndpoint(),
                    browser.maxConcurrentSessions());
        }
        Path executable = browser.executablePath().isBlank()
                ? null : Path.of(browser.executablePath());
        return new PlaywrightBrowserBackend(new BilibiliBrowserTarget(),
                BrowserPolicy.bilibili(), executable, browser.maxConcurrentSessions());
    }

    private BrowserProfile browserProfile() {
        BotConfig.Browser browser = video.browser();
        if (!browser.enabled()) {
            return null;
        }
        Path root = browser.profileRoot().isBlank()
                ? dataDir.resolve("browser")
                : Path.of(browser.profileRoot());
        if (!root.isAbsolute()) {
            root = dataDir.resolve(root);
        }
        return BrowserProfile.bilibili(browser.headless(), browser.viewportWidth(),
                browser.viewportHeight(), browser.timeoutSeconds(),
                root.resolve("bilibili"));
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
