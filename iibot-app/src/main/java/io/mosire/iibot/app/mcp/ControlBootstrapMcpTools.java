package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.app.control.ControlBootstrapService;

import java.util.List;
import java.util.Objects;

/** 运行期控制初始化的六个 MCP 工具；只在 ControlBootstrapService 注入时注册。 */
public final class ControlBootstrapMcpTools {

    public static final List<String> NAMES = List.of(
            ControlInitializeTool.NAME,
            ControlSetPublicGroupTool.NAME,
            ControlRegisterPlayerTool.NAME,
            ControlRegisterDirectorTool.NAME,
            ControlBindSecretaryTool.NAME,
            ControlLoadWorldTool.NAME);

    private ControlBootstrapMcpTools() {
    }

    /** 六个工具，顺序稳定。 */
    public static List<AgentTool> all(ControlBootstrapService service) {
        Objects.requireNonNull(service, "service");
        return List.of(
                new ControlInitializeTool(service),
                new ControlSetPublicGroupTool(service),
                new ControlRegisterPlayerTool(service),
                new ControlRegisterDirectorTool(service),
                new ControlBindSecretaryTool(service),
                new ControlLoadWorldTool(service));
    }
}
