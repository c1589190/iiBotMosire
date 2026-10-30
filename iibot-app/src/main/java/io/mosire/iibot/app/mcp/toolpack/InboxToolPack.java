package io.mosire.iibot.app.mcp.toolpack;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.inbox.InboxBindingDescriptorTool;
import io.mosire.iibot.app.mcp.BotToolContext;

import java.util.List;

/** Inbox 唯一通用工具与绑定描述工具包。 */
public final class InboxToolPack {

    private InboxToolPack() {
    }

    public static List<AgentTool> tools(BotToolContext context) {
        return context.inboxRuntime().mcpTools().stream()
                .filter(tool -> "inbox_rest".equals(tool.name())
                        || InboxBindingDescriptorTool.NAME.equals(tool.name()))
                .toList();
    }
}
