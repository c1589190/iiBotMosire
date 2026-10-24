package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocs;

import java.util.List;
import java.util.Objects;

/** GM 私有文档 MCP 工具集：list/get/write + 控制初始化 load 便捷写入口。 */
public final class GmPrivateDocsMcpTools {

    public static final List<String> NAMES = List.of(
            GmPrivateDocListTool.NAME,
            GmPrivateDocGetTool.NAME,
            GmPrivateDocWriteTool.NAME,
            ControlLoadGmDocTool.NAME);

    private GmPrivateDocsMcpTools() {
    }

    /** 四个工具，顺序稳定。 */
    public static List<AgentTool> all(GmPrivateDocs docs) {
        Objects.requireNonNull(docs, "docs");
        return List.of(
                new GmPrivateDocListTool(docs),
                new GmPrivateDocGetTool(docs),
                new GmPrivateDocWriteTool(docs),
                new ControlLoadGmDocTool(docs));
    }
}
