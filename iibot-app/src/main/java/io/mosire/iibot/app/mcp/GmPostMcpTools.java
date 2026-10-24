package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.post.PostService;

import java.util.List;
import java.util.Objects;

/** GM 侧四个推文审核/发布 MCP 工具；只注入 PostService 后启用。 */
public final class GmPostMcpTools {

    public static final List<String> BASE_NAMES = List.of(
            GmPostListTool.NAME,
            GmPostGetTool.NAME,
            GmPostDecideTool.NAME,
            GmPostPublishTool.NAME);

    public static final List<String> NAMES = List.of(
            GmPostListTool.NAME,
            GmPostGetTool.NAME,
            GmPostDecideTool.NAME,
            GmPostPublishTool.NAME,
            GmPostRetryDeliveryTool.NAME);

    private GmPostMcpTools() {
    }

    /** 四个基础工具，顺序稳定；无 PostDistributor 的兼容装配。 */
    public static List<AgentTool> all(PostService postService, Actor supervisor) {
        Objects.requireNonNull(postService, "postService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                new GmPostListTool(postService),
                new GmPostGetTool(postService),
                new GmPostDecideTool(postService, supervisor),
                new GmPostPublishTool(postService, supervisor));
    }

    /** 完整五工具装配；仅完整组合根注入 PostDistributor 后启用送达重试。 */
    public static List<AgentTool> all(PostService postService,
                                      io.mosire.iibot.app.delivery.PostDistributor distributor,
                                      Actor supervisor) {
        Objects.requireNonNull(distributor, "distributor");
        return List.of(
                new GmPostListTool(postService),
                new GmPostGetTool(postService),
                new GmPostDecideTool(postService, supervisor),
                new GmPostPublishTool(postService, supervisor),
                new GmPostRetryDeliveryTool(postService, distributor));
    }
}
