package io.mosire.iibot.taskflow.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.Decision;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.InfoBrief;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnSnapshot;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.control.EventReadPort;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GM 推演秘书的只读工具面。
 *
 * <p>工具只暴露全量世界事实、当前回合快照、身份摘要、玩家 brief 与事件流摘要。
 * 没有 QQ 发送、世界写、决策确认、文件写、进程或密钥工具。所有工具固定声明
 * {@code gm-aide} role 与 {@link ToolSpec#DEFAULT}，实际调用仍必须经过
 * {@code ToolCallAuthorizer}。</p>
 */
public final class GmAideTools {

    /** 全量世界事实（只读）。 */
    public static final String GM_WORLD_FACTS = "gm_world_facts";
    /** 当前回合快照、决策、草稿与已提交提案（只读）。 */
    public static final String GM_TURN_VIEW = "gm_turn_view";
    /** 玩家与推演身份摘要（不含 QQ 账号）。 */
    public static final String GM_PROFILES = "gm_profiles";
    /** 遍历全部玩家的 brief（只读）。 */
    public static final String GM_BRIEFS = "gm_briefs";
    /** 可选事件流轮询（只读）。 */
    public static final String GM_EVENT_POLL = "gm_event_poll";
    /** GM 读取全部玩家秘书工作区目录（只读）。 */
    public static final String GM_WORKSPACE_LIST = "gm_workspace_list";
    /** GM 读取任意玩家秘书工作区文件（只读）。 */
    public static final String GM_WORKSPACE_READ = "gm_workspace_read";
    /** GM 列出全部推文摘要（只读，不含正文）。 */
    public static final String GM_POSTS = "gm_posts";
    /** GM 读取任意推文正文（只读）。 */
    public static final String GM_POST_READ = "gm_post_read";

    /** 基础只读工具（不依赖工作区/推文服务）。 */
    public static final Set<String> BASE_TOOL_NAMES = Set.of(
            GM_WORLD_FACTS, GM_TURN_VIEW, GM_PROFILES, GM_BRIEFS, GM_EVENT_POLL);
    /** 注入 {@link SecretaryWorkspaceStore} 后增加的只读工具。 */
    public static final Set<String> WORKSPACE_TOOL_NAMES = Set.of(
            GM_WORKSPACE_LIST, GM_WORKSPACE_READ);
    /** 注入 {@link PostService} 后增加的只读工具。 */
    public static final Set<String> POST_TOOL_NAMES = Set.of(GM_POSTS, GM_POST_READ);
    /** 稳定工具名全集；宿主和测试据此核对完整工具面。 */
    public static final Set<String> TOOL_NAMES = Set.of(
            GM_WORLD_FACTS, GM_TURN_VIEW, GM_PROFILES, GM_BRIEFS, GM_EVENT_POLL,
            GM_WORKSPACE_LIST, GM_WORKSPACE_READ, GM_POSTS, GM_POST_READ);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROLES = Set.of(GmAideAgent.ROLE);

    private GmAideTools() {
    }

    /** 兼容旧装配：仅基础只读工具。 */
    public static List<AgentTool> create(TaskflowEngine engine, EventReadPort eventReadPort) {
        return create(engine, eventReadPort, null, null);
    }

    /**
     * 构造 GM 推演秘书只读工具快照。
     *
     * <p>事件端口为 null 时仅 {@code gm_event_poll} 返回稳定不可用错误；工作区/推文服务
     * 为 null 时对应工具不出现。全量读取能力仍受 {@code ToolCallAuthorizer} 的资源判定。</p>
     */
    public static List<AgentTool> create(TaskflowEngine engine, EventReadPort eventReadPort,
                                         SecretaryWorkspaceStore workspaceStore,
                                         PostService postService) {
        Objects.requireNonNull(engine, "engine");
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new ReadTool(GM_WORLD_FACTS,
                "读取全量 WorldFact（含全部 visibility、版本与内容）；只读。",
                objectSchema("读取全量世界事实。", Map.of(), List.of()),
                context -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    List<WorldFact> facts = engine.allWorldFacts();
                    data.put("count", facts.size());
                    data.put("currentWorldVersion", engine.status().worldVersion());
                    data.put("facts", facts.stream().map(GmAideTools::worldFactData).toList());
                    return data;
                }));
        tools.add(new ReadTool(GM_TURN_VIEW,
                "读取当前回合状态、快照、决策、草稿与已提交推演提案；只读。",
                objectSchema("读取当前回合完整只读视图。", Map.of(), List.of()),
                context -> engine.turnView()
                        .map(turn -> {
                            Map<String, Object> data = GmAideTools.turnData(turn);
                            data.put("currentWorldVersion", engine.status().worldVersion());
                            data.put("worldVersionNote",
                                    "currentWorldVersion 是当前引擎版本；snapshot.worldVersion 是回合冻结版本；"
                                            + "WorldFact.worldVersion 只表示事实最近写入版本，不要求三者相等");
                            return data;
                        })
                        .orElseGet(() -> Map.of("active", false))));
        tools.add(new ReadTool(GM_PROFILES,
                "读取玩家与推演身份摘要（playerId/displayName，不含 QQ 账号）；只读。",
                objectSchema("读取玩家与推演身份摘要。", Map.of(), List.of()),
                context -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("players", engine.playerProfiles().stream()
                            .map(GmAideTools::profileData).toList());
                    data.put("directors", engine.directorProfiles().stream()
                            .map(GmAideTools::profileData).toList());
                    return data;
                }));
        tools.add(new ReadTool(GM_BRIEFS,
                "遍历全部已注册玩家的 brief；只读。",
                objectSchema("遍历全部玩家 brief。", Map.of(), List.of()),
                context -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    List<Map<String, Object>> players = new ArrayList<>();
                    for (PlayerProfile player : engine.playerProfiles()) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("playerId", player.playerId());
                        entry.put("briefs", engine.briefsForPlayer(player.playerId()).stream()
                                .sorted(Comparator.comparing(InfoBrief::turnId)
                                        .thenComparing(InfoBrief::contentHash))
                                .map(GmAideTools::briefData).toList());
                        players.add(entry);
                    }
                    data.put("players", players);
                    return data;
                }));
        tools.add(new ReadTool(GM_EVENT_POLL,
                "按 afterSeq 排他游标与 limit 读取只读事件摘要；不消费游标。",
                objectSchema("读取事件流摘要。",
                        Map.of(
                                "afterSeq", Map.of("type", "integer", "minimum", 0,
                                        "description", "排他游标，返回 seq > afterSeq 的事件"),
                                "limit", Map.of("type", "integer", "minimum", 1,
                                        "maximum", 200, "description", "最多返回条数")),
                        List.of()),
                context -> {
                    if (eventReadPort == null) {
                        throw new ToolFailure("EVENT_STREAM_UNAVAILABLE",
                                "事件流未注入，gm_event_poll 不可用");
                    }
                    long afterSeq = optionalNonNegativeLong(context.arguments(), "afterSeq", 0L);
                    int limit = optionalInt(context.arguments(), "limit", 50, 1, 200);
                    List<TaskflowEvent> events = eventReadPort.readAfter(afterSeq, limit + 1);
                    List<TaskflowEvent> ordered = events == null ? List.of() : events.stream()
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparingLong(TaskflowEvent::seq))
                            .limit(limit)
                            .toList();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("afterSeq", afterSeq);
                    data.put("limit", limit);
                    data.put("events", ordered.stream().map(GmAideTools::eventData).toList());
                    data.put("nextCursor", ordered.isEmpty()
                            ? afterSeq : ordered.getLast().seq());
                    data.put("hasMore", events != null && events.size() > limit);
                    return data;
                }));
        if (workspaceStore != null) {
            tools.addAll(GmAideWorkspacePostTools.workspaceTools(workspaceStore, engine));
        }
        if (postService != null) {
            tools.addAll(GmAideWorkspacePostTools.postTools(postService));
        }
        return List.copyOf(tools);
    }

    private static Map<String, Object> worldFactData(WorldFact fact) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factId", fact.factId());
        data.put("worldVersion", fact.worldVersion());
        data.put("visibility", visibilityLabel(fact));
        data.put("content", fact.content());
        return data;
    }

    private static String visibilityLabel(WorldFact fact) {
        if (fact.visibility() == io.mosire.iibot.taskflow.Visibility.PUBLIC) {
            return "PUBLIC";
        }
        if (fact.visibility() == io.mosire.iibot.taskflow.Visibility.DIRECTOR_ONLY) {
            return "DIRECTOR_ONLY";
        }
        if (fact.visibility()
                instanceof io.mosire.iibot.taskflow.Visibility.PlayerSetVisibility players) {
            return "PLAYERS:" + players.playerIds().stream().sorted().toList();
        }
        return fact.visibility().getClass().getSimpleName();
    }

    private static Map<String, Object> turnData(TurnView turn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", true);
        data.put("turnId", turn.turnId());
        data.put("turnNo", turn.turnNo());
        data.put("state", turn.state().name());
        data.put("revision", turn.revision());
        data.put("worldVersion", turn.worldVersion());
        data.put("promptVersion", turn.promptVersion());
        data.put("opensAt", turn.opensAt().toString());
        data.put("closesAt", turn.closesAt().toString());
        data.put("lockedAt", turn.lockedAt() == null ? null : turn.lockedAt().toString());
        data.put("drafts", turn.drafts().stream().map(GmAideTools::draftData).toList());
        data.put("decisions", turn.decisions().stream().map(GmAideTools::decisionData).toList());
        data.put("snapshot", turn.snapshot() == null ? null : snapshotData(turn.snapshot()));
        data.put("committedProposal", turn.committedProposal() == null
                ? null : proposalData(turn.committedProposal()));
        return data;
    }

    private static Map<String, Object> draftData(DecisionDraft draft) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", draft.draftId());
        data.put("turnId", draft.turnId());
        data.put("playerId", draft.playerId());
        data.put("intent", draft.intent());
        data.put("actions", draft.actions());
        data.put("resources", draft.resources());
        data.put("target", draft.target());
        data.put("contingency", draft.contingency());
        data.put("previewHash", draft.previewHash());
        data.put("status", draft.status().name());
        data.put("revision", draft.revision());
        data.put("expiresAt", draft.expiresAt().toString());
        return data;
    }

    private static Map<String, Object> decisionData(Decision decision) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decisionId", decision.decisionId());
        data.put("draftId", decision.draftId());
        data.put("turnId", decision.turnId());
        data.put("playerId", decision.playerId());
        data.put("contentHash", decision.contentHash());
        data.put("confirmedByPlayerId", decision.confirmedByPlayerId());
        data.put("confirmedAt", decision.confirmedAt().toString());
        data.put("fields", decision.fields());
        return data;
    }

    private static Map<String, Object> snapshotData(TurnSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turnId", snapshot.turnId());
        data.put("turnNo", snapshot.turnNo());
        data.put("worldVersion", snapshot.worldVersion());
        data.put("promptVersion", snapshot.promptVersion());
        data.put("decisions", snapshot.decisions().stream()
                .map(GmAideTools::decisionData).toList());
        data.put("confirmedAt", snapshot.confirmedAt().toString());
        data.put("contentHash", snapshot.contentHash());
        return data;
    }

    private static Map<String, Object> proposalData(AdjudicationProposal proposal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", proposal.schemaVersion());
        data.put("turnId", proposal.turnId());
        data.put("expectedWorldVersion", proposal.expectedWorldVersion());
        data.put("publicNarrative", proposal.publicNarrative());
        data.put("playerOutcomes", proposal.playerOutcomes());
        data.put("worldDeltas", proposal.worldDeltas().stream()
                .map(WorldDelta::canonicalText).toList());
        data.put("nextHooks", proposal.nextHooks());
        data.put("proposalHash", proposal.proposalHash());
        return data;
    }

    private static Map<String, Object> profileData(PlayerProfile profile) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerId", profile.playerId());
        data.put("displayName", profile.displayName());
        return data;
    }

    private static Map<String, Object> briefData(InfoBrief brief) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerId", brief.playerId());
        data.put("turnId", brief.turnId());
        data.put("sourceFactIds", brief.sourceFactIds().stream().sorted().toList());
        data.put("content", brief.content());
        data.put("expiresAt", brief.expiresAt().toString());
        data.put("contentHash", brief.contentHash());
        return data;
    }

    private static Map<String, Object> eventData(TaskflowEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", event.seq());
        data.put("occurredAt", event.occurredAt().toString());
        data.put("type", event.type().name());
        data.put("actorRole", event.actorRole().name());
        data.put("actorId", event.actorId());
        data.put("turnId", event.turnId());
        data.put("idempotencyKey", event.idempotencyKey());
        data.put("payload", event.payload());
        return data;
    }

    private static long optionalNonNegativeLong(Map<String, Object> arguments,
                                                 String name, long defaultValue) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
                || !(number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long)) {
            throw new IllegalArgumentException("参数 " + name + " 必须是非负整数");
        }
        long result = number.longValue();
        if (result < 0) {
            throw new IllegalArgumentException("参数 " + name + " 必须是非负整数");
        }
        return result;
    }

    private static int optionalInt(Map<String, Object> arguments, String name,
                                   int defaultValue, int minimum, int maximum) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
                || !(number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long)) {
            throw new IllegalArgumentException("参数 " + name + " 必须是整数");
        }
        long raw = number.longValue();
        if (raw < minimum || raw > maximum) {
            throw new IllegalArgumentException(
                    "参数 " + name + " 必须在 " + minimum + ".." + maximum + " 之间");
        }
        return (int) raw;
    }

    private static Map<String, Object> objectSchema(String description,
                                                     Map<String, Object> properties,
                                                     List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 只读工具的统一实现；工具分类固定 DEFAULT，不敏感、不破坏。 */
    private static final class ReadTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;
        private final Body body;

        private ReadTool(String name, String description, Map<String, Object> schema, Body body) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.schema = Objects.requireNonNull(schema, "schema");
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
        public ToolResult execute(ToolContext context) {
            try {
                return ToolResult.ok(json(body.read(context)));
            } catch (ToolFailure failure) {
                return ToolResult.error(failure.code(), failure.getMessage());
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error("INVALID_ARGUMENTS",
                        invalid.getMessage() == null ? "工具参数非法" : invalid.getMessage());
            } catch (RuntimeException unexpected) {
                return ToolResult.error("GM_AIDE_TOOL_ERROR", "GM 推演工具执行失败（信息已脱敏）");
            }
        }
    }

    @FunctionalInterface
    private interface Body {
        Map<String, Object> read(ToolContext context);
    }

    private static final class ToolFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private ToolFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return "{\"ok\":false,\"code\":\"GM_AIDE_TOOL_ERROR\"}";
        }
    }
}
