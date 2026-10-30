package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.mcp.toolpack.InboxToolPack;
import io.mosire.iibot.app.mcp.toolpack.MediaToolPack;
import io.mosire.iibot.app.mcp.toolpack.QqToolPack;
import io.mosire.iibot.app.bot.BotConfig;
import io.mosire.iibot.app.mcp.toolpack.VideoSiteToolPack;
import io.mosire.iibot.connect.ops.OpsLog;
import io.mosire.iibot.connect.video.core.VideoAccountRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App 层唯一的 MCP 工具注册目录。
 *
 * <p>运行时只负责构造 {@link BotToolContext}；QQ、媒体、Inbox、视频站工具的选择和
 * 注册全部在这里完成。工具顺序：QQ → 媒体上传 → 视频分帧 → 视频评论 → 条件媒体/
 * 群文件/撤回 → 视频账号工具 → 兼容 extraTools → Inbox。</p>
 */
public final class BotToolCatalog implements AutoCloseable {

    private final List<VideoAccountRef> videoAccounts;
    private final List<AgentTool> extraTools;
    private final boolean videoReadEnabled;
    private final BotConfig.Video video;
    private final AtomicBoolean closed = new AtomicBoolean();
    private List<AgentTool> createdTools = List.of();

    private BotToolCatalog(List<VideoAccountRef> videoAccounts,
                           List<AgentTool> extraTools, boolean videoReadEnabled,
                           BotConfig.Video video) {
        this.videoAccounts = List.copyOf(Objects.requireNonNull(videoAccounts,
                "videoAccounts"));
        this.extraTools = List.copyOf(Objects.requireNonNull(extraTools, "extraTools"));
        this.videoReadEnabled = videoReadEnabled;
        this.video = Objects.requireNonNull(video, "video");
    }

    /** 无 B 站账号的默认目录；注册匿名分帧与评论查询，保持运行时测试入口行为。 */
    public static BotToolCatalog standard() {
        return new BotToolCatalog(List.of(), List.of(), false,
                BotConfig.Video.defaults());
    }

    /** 生产组合根目录：注册通用读取；有账号时再加登录工具。 */
    public static BotToolCatalog withVideoAccounts(List<VideoAccountRef> videoAccounts) {
        return withVideoAccounts(videoAccounts, BotConfig.Video.defaults());
    }

    public static BotToolCatalog withVideoAccounts(List<VideoAccountRef> videoAccounts,
                                                   BotConfig.Video video) {
        return new BotToolCatalog(videoAccounts, List.of(), true, video);
    }

    /**
     * 兼容旧 {@code BotRuntime.start(..., extraTools, ...)} 入口。
     *
     * <p>extraTools 会放在视频账号工具之后、Inbox 工具之前，保持旧顺序；生产路径应使用
     * {@link #withVideoAccounts(List)}。</p>
     */
    public static BotToolCatalog withExtraTools(List<AgentTool> extraTools) {
        return new BotToolCatalog(List.of(),
                extraTools == null ? List.of() : extraTools, false,
                BotConfig.Video.defaults());
    }

    /** 组装本次运行时的完整工具清单。 */
    public List<AgentTool> create(BotToolContext context) {
        Objects.requireNonNull(context, "context");
        List<AgentTool> tools = new ArrayList<>();
        tools.addAll(QqToolPack.tools(context));
        MediaToolPack media = new MediaToolPack(context);
        tools.addAll(media.uploadTools());
        VideoSiteToolPack video = new VideoSiteToolPack(videoAccounts,
                context.mediaStore(), videoReadEnabled, this.video, context.dataDir());
        tools.addAll(video.frameTools());
        tools.addAll(video.commentTools());
        tools.addAll(media.capabilityTools());
        tools.addAll(video.accountTools());
        tools.addAll(extraTools);
        tools.addAll(InboxToolPack.tools(context));
        createdTools = List.copyOf(tools);
        return createdTools;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<AgentTool> toClose = createdTools.isEmpty() ? extraTools : createdTools;
        for (int index = toClose.size() - 1; index >= 0; index--) {
            AgentTool tool = toClose.get(index);
            if (tool instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    OpsLog.warn("event=bot_tool_close_failed tool={} errorType={}",
                            tool.name(), failure.getClass().getSimpleName());
                }
            }
        }
    }
}
