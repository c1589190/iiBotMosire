package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.mcp.BotToolContext;
import io.mosire.iibot.bot.mcp.BotMcpTools;

import java.util.List;

/** QQ/统一 conversation/notes/source-weight 工具包。 */
public final class QqToolPack {

    private QqToolPack() {
    }

    public static List<AgentTool> tools(BotToolContext context) {
        return BotMcpTools.allForAccounts(
                context.board(),
                context.notes(),
                context.inboxDigests(),
                context.replies(),
                context.defaultAccountId(),
                context.forwardByAccount(),
                context.recentByAccount(),
                context.friendRequestByAccount(),
                context.inboxAudit(),
                context.historyByAccount(),
                context.sourceWeights(),
                context.consumerStates(),
                context.accountRegistry());
    }
}
