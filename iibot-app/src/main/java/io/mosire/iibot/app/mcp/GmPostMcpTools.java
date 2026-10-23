package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.post.PostService;

import java.util.List;
import java.util.Objects;

/** GM 侧四个推文审核/发布 MCP 工具；只注入 PostService 后启用。 */
public final class GmPostMcpTools {

    public static final List<String> NAMES = List.of(
            GmPostListTool.NAME,
            GmPostGetTool.NAME,
            GmPostDecideTool.NAME,
            GmPostPublishTool.NAME);

    private GmPostMcpTools() {
    }

    /** 四个工具，顺序稳定。 */
    public static List<AgentTool> all(PostService postService, Actor supervisor) {
        Objects.requireNonNull(postService, "postService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                new GmPostListTool(postService),
                new GmPostGetTool(postService),
                new GmPostDecideTool(postService, supervisor),
                new GmPostPublishTool(postService, supervisor));
    }
}
