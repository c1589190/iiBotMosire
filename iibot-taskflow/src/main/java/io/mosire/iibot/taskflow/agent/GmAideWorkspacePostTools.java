package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GM 推演秘书的只读工作区与推文工具。
 *
 * <p>GM aide 被允许读取所有玩家秘书工作区与全部推文（含待审草稿和他人私密推文），因此这些
 * 工具与玩家秘书的同名能力不同：{@code playerId} 是显式只读参数，没有任何工作区写入、
 * 推文创建/审批/发布或 QQ 发送入口。工具固定声明 {@code gm-aide} 角色、
 * {@link ToolSpec#DEFAULT} 与 {@code secretary-workspace}/{@code post} 只读资源面，实际调用
 * 仍必须经过 {@link io.mosire.agentlib.tool.ToolCallAuthorizer}。</p>
 *
 * <p><b>输出纪律</b>：工具结果只包含逻辑路径、玩家 ID、推文元数据与正文；不包含
 * {@link SecretaryWorkspaceStore#rootFor(String)} 的物理路径、QQ 号或 token。文本读取
 * （工作区与推文正文）超过 {@value #MAX_READ_RESULT_CHARS} 字符时截断并带固定标记。</p>
 */
public final class GmAideWorkspacePostTools {

    /** 单次读取回给模型的最大字符数；与玩家秘书工具保持一致。 */
    public static final int MAX_READ_RESULT_CHARS = 16 * 1024;
    /** 截断后的固定标记。 */
    public static final String TRUNCATION_MARKER = "\n\n[内容已截断]";

    public static final String WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND";
    public static final String POST_NOT_FOUND = "POST_NOT_FOUND";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    private static final Set<String> ROLES = Set.of(GmAideAgent.ROLE);

    private static final ResourceManifest WORKSPACE_RESOURCES = ResourceManifest.of(
            SecretaryWorkspaceStore.RESOURCE_NAMESPACE, ResourcePolicy.READ_ONLY);
    private static final ResourceId WORKSPACE_RESOURCE = ResourceId.of(
            SecretaryWorkspaceStore.RESOURCE_NAMESPACE, "workspace");
    private static final ResourceManifest POST_RESOURCES = ResourceManifest.of(
            PostStore.RESOURCE_NAMESPACE, ResourcePolicy.READ_ONLY);
    private static final ResourceId POST_RESOURCE = ResourceId.of(
            PostStore.RESOURCE_NAMESPACE, PostStore.RESOURCE_NAMESPACE);

    private GmAideWorkspacePostTools() {
    }

    /** 两个工作区只读工具；顺序稳定。 */
    public static List<AgentTool> workspaceTools(SecretaryWorkspaceStore store,
                                                 TaskflowEngine engine) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(engine, "engine");
        return List.of(workspaceList(store, engine), workspaceRead(store));
    }

    /** 两个推文只读工具；顺序稳定。 */
    public static List<AgentTool> postTools(PostService postService) {
        Objects.requireNonNull(postService, "postService");
        return List.of(posts(postService), postRead(postService));
    }

    public static AgentTool workspaceList(SecretaryWorkspaceStore store, TaskflowEngine engine) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "playerId", Map.of(
                                "type", "string",
                                "description", "可选玩家 ID；省略时列出所有玩家及其工作区根目录"),
                        "path", Map.of(
                                "type", "string",
                                "description", "可选相对路径（public/player/gm/notes 及其子目录）；"
                                        + "仅与 playerId 一起使用，缺省列出该玩家工作区根")),
                "additionalProperties", false);
        return new ReadTool(
                GmAideTools.GM_WORKSPACE_LIST,
                "列出全部玩家工作区或指定玩家的直接子项；只返回逻辑相对路径，不含物理路径。",
                schema,
                WORKSPACE_RESOURCES,
                WORKSPACE_RESOURCE,
                context -> {
                    String playerId = optionalText(context.arguments(), "playerId");
                    if (playerId == null) {
                        if (optionalText(context.arguments(), "path") != null) {
                            throw invalid("不带 playerId 时不能指定 path");
                        }
                        return listAllPlayers(store, engine);
                    }
                    String path = optionalText(context.arguments(), "path");
                    String prefix = path == null ? "" : path;
                    List<String> entries;
                    try {
                        entries = store.list(playerId, prefix);
                    } catch (IllegalArgumentException failure) {
                        throw workspaceFailure(failure);
                    }
                    StringBuilder result = new StringBuilder();
                    result.append("playerId=").append(playerId).append('\n');
                    result.append("path=").append(prefix.isEmpty() ? "<root>" : prefix).append('\n');
                    result.append("共 ").append(entries.size()).append(" 项：");
                    for (String entry : entries) {
                        result.append("\n- ").append(entry);
                    }
                    return result.toString();
                });
    }

    public static AgentTool workspaceRead(SecretaryWorkspaceStore store) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "playerId", Map.of(
                                "type", "string",
                                "description", "目标玩家 ID"),
                        "path", Map.of(
                                "type", "string",
                                "description", "工作区内相对 Docs 路径，如 gm/order.md")),
                "required", List.of("playerId", "path"),
                "additionalProperties", false);
        return new ReadTool(
                GmAideTools.GM_WORKSPACE_READ,
                "以 GM 权限只读读取任意玩家秘书工作区中的一篇文本；不返回物理路径。",
                schema,
                WORKSPACE_RESOURCES,
                WORKSPACE_RESOURCE,
                context -> {
                    String playerId = requiredText(context.arguments(), "playerId");
                    String path = requiredText(context.arguments(), "path");
                    requireWorkspacePath(path);
                    String content;
                    try {
                        content = store.read(playerId, path).orElse(null);
                    } catch (IllegalArgumentException failure) {
                        throw workspaceFailure(failure);
                    }
                    if (content == null) {
                        throw new ToolFailure(WORKSPACE_NOT_FOUND, "工作区中不存在该文件");
                    }
                    boolean truncated = content.length() > MAX_READ_RESULT_CHARS;
                    String rendered = truncated
                            ? content.substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                            : content;
                    return "playerId=" + playerId
                            + "\npath=" + path
                            + "\ntruncated=" + truncated
                            + "\n内容：\n" + rendered;
                });
    }

    public static AgentTool posts(PostService postService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false);
        return new ReadTool(
                GmAideTools.GM_POSTS,
                "列出全部推文摘要（含待审草稿与所有玩家推文）；不返回正文。",
                schema,
                POST_RESOURCES,
                POST_RESOURCE,
                context -> {
                    List<Post> posts = postService.postsForGm();
                    StringBuilder result = new StringBuilder("共 ").append(posts.size()).append(" 篇推文：");
                    for (Post post : posts) {
                        result.append("\n- postId=").append(post.postId())
                                .append(" author=").append(post.authorPlayerId())
                                .append(" visibility=").append(post.visibility())
                                .append(" status=").append(post.status())
                                .append(" revision=").append(post.revision())
                                .append(" deliveryState=")
                                .append(post.deliveryState() == null
                                        ? "null" : post.deliveryState());
                    }
                    return result.toString();
                });
    }

    public static AgentTool postRead(PostService postService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "postId", Map.of("type", "string", "description", "要读取的推文 ID")),
                "required", List.of("postId"),
                "additionalProperties", false);
        return new ReadTool(
                GmAideTools.GM_POST_READ,
                "按 postId 以 GM 权限只读读取任意推文正文；正文超长时截断并标记。",
                schema,
                POST_RESOURCES,
                POST_RESOURCE,
                context -> {
                    String postId = requiredText(context.arguments(), "postId");
                    Post post = postService.post(postId).orElse(null);
                    if (post == null) {
                        throw new ToolFailure(POST_NOT_FOUND, "推文不存在");
                    }
                    boolean truncated = post.body().length() > MAX_READ_RESULT_CHARS;
                    String body = truncated
                            ? post.body().substring(0, MAX_READ_RESULT_CHARS) + TRUNCATION_MARKER
                            : post.body();
                    return "postId=" + post.postId()
                            + "\nauthor=" + post.authorPlayerId()
                            + "\nvisibility=" + post.visibility()
                            + "\nstatus=" + post.status()
                            + "\nrevision=" + post.revision()
                            + "\ndeliveryState=" + (post.deliveryState() == null
                                    ? "null" : post.deliveryState())
                            + "\ntruncated=" + truncated
                            + "\nbody:\n" + body;
                });
    }

    private static String listAllPlayers(SecretaryWorkspaceStore store, TaskflowEngine engine) {
        StringBuilder result = new StringBuilder("roots: ")
                .append(String.join(", ", SecretaryWorkspaceStore.ROOT_DIRECTORIES))
                .append("\nplayers:");
        for (PlayerProfile profile : engine.playerProfiles()) {
            List<String> entries;
            try {
                entries = store.list(profile.playerId(), "");
            } catch (IllegalArgumentException unavailable) {
                entries = List.of();
            }
            result.append("\n- playerId=").append(profile.playerId())
                    .append(" directories=").append(entries);
        }
        return result.toString();
    }

    /**
     * 与 {@link SecretaryWorkspaceStore} 的公开路径纪律同口径的模型侧前置校验：失败时给出
     * 稳定 {@code INVALID_ARGUMENTS}，而不是把 store 的物理路径/IO 细节带到模型面。
     */
    private static void requireWorkspacePath(String path) {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || path.startsWith("/") || path.startsWith("~")
                || path.matches("^[A-Za-z]:.*")) {
            throw new ToolFailure(INVALID_ARGUMENTS, "路径必须是工作区内的相对 Docs 路径");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ToolFailure(INVALID_ARGUMENTS, "路径不得包含空段、. 或 ..");
            }
        }
        if (!SecretaryWorkspaceStore.ROOT_DIRECTORIES.contains(segments[0])) {
            throw new ToolFailure(INVALID_ARGUMENTS,
                    "路径必须位于 public/、player/、gm/ 或 notes/");
        }
    }

    private static ToolFailure workspaceFailure(IllegalArgumentException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("未知玩家工作区")) {
            return new ToolFailure(WORKSPACE_NOT_FOUND, "玩家工作区不存在");
        }
        return new ToolFailure(INVALID_ARGUMENTS, "工作区路径非法或不可读");
    }

    private static ToolFailure invalid(String message) {
        return new ToolFailure(INVALID_ARGUMENTS, message);
    }

    private static String requiredText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolFailure(INVALID_ARGUMENTS, name + " 必须是非空字符串");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolFailure(INVALID_ARGUMENTS, name + " 必须是字符串");
        }
        return text;
    }

    @FunctionalInterface
    private interface Body {
        String read(ToolContext context);
    }

    private static final class ToolFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private ToolFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 只读工具壳：metadata + 只读资源断言 + 稳定错误映射。 */
    private static final class ReadTool implements AgentTool {

        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final ResourceManifest resources;
        private final ResourceId resourceId;
        private final Body body;

        private ReadTool(String name, String description, Map<String, Object> schema,
                         ResourceManifest resources, ResourceId resourceId, Body body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Map.copyOf(schema);
            this.resources = Objects.requireNonNull(resources, "resources");
            this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
            this.body = Objects.requireNonNull(body, "body");
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return schema;
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.DEFAULT;
        }

        @Override
        public Set<String> roles() {
            return ROLES;
        }

        @Override
        public ResourceManifest resources() {
            return resources;
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                context.resources().require(Operation.READ, resourceId);
                return ToolResult.ok(body.read(context));
            } catch (ResourceDeniedException denied) {
                throw denied;
            } catch (ToolFailure failure) {
                return ToolResult.error(failure.code, failure.getMessage());
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error(INVALID_ARGUMENTS,
                        invalid.getMessage() == null ? "工具参数非法" : invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error("GM_AIDE_TOOL_ERROR", "GM 推演工具执行失败（信息已脱敏）");
            }
        }
    }
}
