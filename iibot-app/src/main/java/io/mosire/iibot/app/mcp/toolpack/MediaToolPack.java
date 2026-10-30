package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.mcp.BotToolContext;
import io.mosire.iibot.app.media.BotAudioGetTool;
import io.mosire.iibot.app.media.BotForwardMediaGetTool;
import io.mosire.iibot.app.media.BotGroupFileGetTool;
import io.mosire.iibot.app.media.BotGroupFileHandles;
import io.mosire.iibot.app.media.BotGroupFilesTool;
import io.mosire.iibot.app.media.BotMediaGetTool;
import io.mosire.iibot.app.media.BotMediaUploadTool;
import io.mosire.iibot.app.reply.BotGroupFileUploadTool;
import io.mosire.iibot.app.reply.BotMessageRecallTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 媒体上传、读取、群文件与撤回工具包。 */
public final class MediaToolPack {

    private final BotToolContext context;
    private final BotGroupFileHandles groupFileHandles = new BotGroupFileHandles();

    public MediaToolPack(BotToolContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** 无条件可用的媒体上传工具。 */
    public List<AgentTool> uploadTools() {
        return List.of(new BotMediaUploadTool(context.mediaStore()));
    }

    /** 按账号能力启用的媒体/群文件/撤回工具。 */
    public List<AgentTool> capabilityTools() {
        List<AgentTool> tools = new ArrayList<>();
        if (hasCapability(context.mediaByAccount())) {
            tools.add(new BotMediaGetTool(context.board(), context.mediaByAccount(),
                    context.mediaStore()));
        }
        if (hasCapability(context.audioByAccount())) {
            tools.add(new BotAudioGetTool(context.board(), context.audioByAccount(),
                    context.mediaStore()));
        }
        if (hasCapability(context.mediaByAccount())
                && hasCapability(context.forwardByAccount())) {
            tools.add(new BotForwardMediaGetTool(context.board(),
                    context.forwardByAccount(), context.mediaByAccount(),
                    context.mediaStore()));
        }
        if (hasCapability(context.groupFilesByAccount())) {
            tools.add(new BotGroupFilesTool(context.groupFilesByAccount(),
                    groupFileHandles));
            tools.add(new BotGroupFileGetTool(context.groupFilesByAccount(),
                    groupFileHandles, context.mediaStore()));
        }
        if (hasCapability(context.groupFileUploadByAccount())) {
            tools.add(new BotGroupFileUploadTool(context.board(),
                    context.groupFileUploadByAccount(), context.mediaStore()));
        }
        if (hasCapability(context.recallByAccount())) {
            tools.add(new BotMessageRecallTool(context.recallByAccount()));
        }
        return List.copyOf(tools);
    }

    private static boolean hasCapability(java.util.Map<String, ?> providers) {
        return providers != null && providers.values().stream().anyMatch(Objects::nonNull);
    }
}
