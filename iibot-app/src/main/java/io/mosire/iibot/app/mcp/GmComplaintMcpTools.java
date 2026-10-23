package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.complaint.ComplaintService;

import java.util.List;
import java.util.Objects;

/** GM 侧三个投诉 MCP 工具；只注入 ComplaintService 后启用。 */
public final class GmComplaintMcpTools {

    public static final List<String> NAMES = List.of(
            GmComplaintListTool.NAME,
            GmComplaintGetTool.NAME,
            GmComplaintRespondTool.NAME);

    private GmComplaintMcpTools() {
    }

    /** 三个工具，顺序稳定。 */
    public static List<AgentTool> all(ComplaintService complaintService, Actor supervisor) {
        Objects.requireNonNull(complaintService, "complaintService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                new GmComplaintListTool(complaintService),
                new GmComplaintGetTool(complaintService),
                new GmComplaintRespondTool(complaintService, supervisor));
    }
}
