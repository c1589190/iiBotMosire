package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;

import java.util.List;
import java.util.Objects;

/** GM 侧三个共享 Skill MCP 工具；只注入 SharedSkillStore 后启用。 */
public final class GmSharedSkillMcpTools {

    public static final List<String> NAMES = List.of(
            GmSharedSkillListTool.NAME,
            GmSharedSkillGetTool.NAME,
            GmSharedSkillWriteTool.NAME);

    private GmSharedSkillMcpTools() {
    }

    /** 三个工具，顺序稳定。 */
    public static List<AgentTool> all(SharedSkillStore sharedSkillStore) {
        Objects.requireNonNull(sharedSkillStore, "sharedSkillStore");
        return List.of(
                new GmSharedSkillListTool(sharedSkillStore),
                new GmSharedSkillGetTool(sharedSkillStore),
                new GmSharedSkillWriteTool(sharedSkillStore));
    }
}
