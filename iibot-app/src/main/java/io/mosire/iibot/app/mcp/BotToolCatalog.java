package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.mcp.toolpack.InboxToolPack;
import io.mosire.iibot.app.mcp.toolpack.MediaToolPack;
import io.mosire.iibot.app.mcp.toolpack.QqToolPack;
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
 * 注册全部在这里完成。工具顺序保持 V1 行为：QQ → 媒体上传 → 视频分帧 → 条件媒体/
 * 群文件/撤回 → 视频账号工具 → 兼容 extraTools → Inbox。</p>
 */
public final class BotToolCatalog implements AutoCloseable {

    private final List<VideoAccountRef> videoAccounts;
    private final List<AgentTool> extraTools;
    private final boolean videoReadEnabled;
    private final AtomicBoolean closed = new AtomicBoolean();

    private BotToolCatalog(List<VideoAccountRef> videoAccounts,
                           List<AgentTool> extraTools, boolean videoReadEnabled) {
        this.videoAccounts = List.copyOf(Objects.requireNonNull(videoAccounts,
                "videoAccounts"));
        this.extraTools = List.copyOf(Objects.requireNonNull(extraTools, "extraTools"));
        this.videoReadEnabled = videoReadEnabled;
    }

    /** 无 B 站账号的默认目录；只注册匿名分帧工具，保持运行时测试入口行为。 */
    public static BotToolCatalog standard() {
        return new BotToolCatalog(List.of(), List.of(), false);
    }

    /** 生产组合根目录：注册通用读取；有账号时再加登录工具。 */
    public static BotToolCatalog withVideoAccounts(List<VideoAccountRef> videoAccounts) {
        return new BotToolCatalog(videoAccounts, List.of(), true);
    }

    /**
     * 兼容旧 {@code BotRuntime.start(..., extraTools, ...)} 入口。
     *
     * <p>extraTools 会放在视频账号工具之后、Inbox 工具之前，保持旧顺序；生产路径应使用
     * {@link #withVideoAccounts(List)}。</p>
     */
    public static BotToolCatalog withExtraTools(List<AgentTool> extraTools) {
        return new BotToolCatalog(List.of(),
                extraTools == null ? List.of() : extraTools, false);
    }

    /** 组装本次运行时的完整工具清单。 */
    public List<AgentTool> create(BotToolContext context) {
        Objects.requireNonNull(context, "context");
        List<AgentTool> tools = new ArrayList<>();
        tools.addAll(QqToolPack.tools(context));
        MediaToolPack media = new MediaToolPack(context);
        tools.addAll(media.uploadTools());
        VideoSiteToolPack video = new VideoSiteToolPack(videoAccounts,
                context.mediaStore(), videoReadEnabled);
        tools.addAll(video.frameTools());
        tools.addAll(media.capabilityTools());
        tools.addAll(video.accountTools());
        tools.addAll(extraTools);
        tools.addAll(InboxToolPack.tools(context));
        return List.copyOf(tools);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (int index = extraTools.size() - 1; index >= 0; index--) {
            AgentTool tool = extraTools.get(index);
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
