package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.app.publication.ExternalPostService;

import java.util.List;
import java.util.Objects;

/** GM 侧四个推文审核/发布 MCP 工具；只注入 PostService 后启用。 */
public final class GmPostMcpTools {

    public static final String COMPOSE_NAME = GmPostComposeTool.NAME;

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
        return all(postService, null, supervisor, null);
    }

    /** 完整五工具装配；仅完整组合根注入 PostDistributor 后启用送达重试。 */
    public static List<AgentTool> all(PostService postService,
                                      io.mosire.iibot.app.delivery.PostDistributor distributor,
                                      Actor supervisor) {
        return all(postService, distributor, supervisor, null);
    }

    /** 完整装配 + 外部自由发帖；external 为 null 时不注册 tf_post_compose。 */
    public static List<AgentTool> all(PostService postService,
                                      io.mosire.iibot.app.delivery.PostDistributor distributor,
                                      Actor supervisor,
                                      ExternalPostService externalPostService) {
        Objects.requireNonNull(postService, "postService");
        Objects.requireNonNull(supervisor, "supervisor");
        List<AgentTool> tools = new java.util.ArrayList<>();
        tools.add(new GmPostListTool(postService));
        tools.add(new GmPostGetTool(postService));
        tools.add(new GmPostDecideTool(postService, supervisor));
        tools.add(new GmPostPublishTool(postService, supervisor));
        if (distributor != null) {
            tools.add(new GmPostRetryDeliveryTool(postService, distributor));
        }
        if (externalPostService != null) {
            tools.add(new GmPostComposeTool(externalPostService, supervisor));
        }
        return List.copyOf(tools);
    }
}
