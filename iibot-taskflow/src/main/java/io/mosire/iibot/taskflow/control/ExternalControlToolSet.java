package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.Decision;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.SettingProposal;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnSnapshot;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldDelta;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.event.TaskflowEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 外部 Agent 全游戏事件流控制面的工具集合。
 *
 * <p>本类只实现标准 AgentLib {@link AgentTool}，不包含 HTTP server、认证代理或应用
 * 组合根。宿主应把 {@link #tools()} 注册到独立 {@link ToolRegistry}，并让每次调用经过
 * {@code ToolCallAuthorizer}；工具自身不做工具名级权限判断，只通过
 * {@code context.resources().require(...)} 做资源级断言。</p>
 *
 * <p>所有工具固定声明角色 {@code supervisor}、资源命名空间 {@code taskflow} 且缺省
 * {@code DENY}。写工具要求 {@code expectedRevision + idempotencyKey + reason}，actor
 * 来自构造时由宿主绑定的已认证 {@link Actor}，工具参数不能选择或提升 actor。玩家硬确认
 * （提交/确认决策）没有对应工具，因此外部控制面不能代替玩家确认。</p>
 */
public final class ExternalControlToolSet implements Iterable<AgentTool> {

    /** 引擎全局状态摘要。 */
    public static final String TF_STATUS = "tf_status";
    /** 当前或指定回合详情。 */
    public static final String TF_TURN_GET = "tf_turn_get";
    /** 只读事件游标。 */
    public static final String TF_EVENT_POLL = "tf_event_poll";
    /** 不变量检查。 */
    public static final String TF_INVARIANT_CHECK = "tf_invariant_check";
    /** 注册玩家。 */
    public static final String TF_REGISTER_PLAYER = "tf_register_player";
    /** 注册推演身份。 */
    public static final String TF_REGISTER_DIRECTOR = "tf_register_director";
    /** 装载世界事实。 */
    public static final String TF_LOAD_WORLD_FACTS = "tf_load_world_facts";
    /** 开启回合。 */
    public static final String TF_OPEN_TURN = "tf_open_turn";
    /** 冻结回合。 */
    public static final String TF_LOCK_TURN = "tf_lock_turn";
    /** 启动推演。 */
    public static final String TF_START_ADJUDICATION = "tf_start_adjudication";
    /** 提交推演提案。 */
    public static final String TF_COMMIT_ADJUDICATION = "tf_commit_adjudication";
    /** 标记推演失败。 */
    public static final String TF_FAIL_ADJUDICATION = "tf_fail_adjudication";
    /** 重试推演。 */
    public static final String TF_RETRY_ADJUDICATION = "tf_retry_adjudication";
    /** 发布回合。 */
    public static final String TF_PUBLISH_TURN = "tf_publish_turn";
    /** 签发 brief。 */
    public static final String TF_ISSUE_BRIEF = "tf_issue_brief";
    /** 列出待审设定或读取指定设定提案摘要。 */
    public static final String TF_SETTING_LIST = "tf_setting_list";
    /** 提交一条运行期设定提案。 */
    public static final String TF_SETTING_PROPOSE = "tf_setting_propose";
    /** 审核一条设定提案。 */
    public static final String TF_SETTING_DECIDE = "tf_setting_decide";

    /** 只读工具名。 */
    public static final Set<String> READ_TOOL_NAMES = Set.of(
            TF_STATUS, TF_TURN_GET, TF_EVENT_POLL, TF_INVARIANT_CHECK, TF_SETTING_LIST);

    /** 写工具名。 */
    public static final Set<String> WRITE_TOOL_NAMES = Set.of(
            TF_REGISTER_PLAYER, TF_REGISTER_DIRECTOR, TF_LOAD_WORLD_FACTS,
            TF_OPEN_TURN, TF_LOCK_TURN, TF_START_ADJUDICATION, TF_COMMIT_ADJUDICATION,
            TF_FAIL_ADJUDICATION, TF_RETRY_ADJUDICATION, TF_PUBLISH_TURN,
            TF_ISSUE_BRIEF, TF_SETTING_PROPOSE, TF_SETTING_DECIDE);

    private final TaskflowEngine engine;
    private final Actor supervisor;
    private final Clock clock;
    private final EventReadPort eventReadPort;
    private final List<AgentTool> tools;
    private final Map<String, AgentTool> byName;

    private ExternalControlToolSet(Builder builder) {
        this.engine = builder.engine;
        this.supervisor = builder.supervisor;
        this.clock = builder.clock;
        this.eventReadPort = builder.eventReadPort;

        ControlWriteCache cache = new ControlWriteCache();
        List<AgentTool> built = new ArrayList<>();
        built.add(statusTool());
        built.add(turnGetTool());
        built.add(eventPollTool());
        built.add(invariantCheckTool());
        built.add(settingListTool());
        built.add(registerPlayerTool(cache));
        built.add(registerDirectorTool(cache));
        built.add(loadWorldFactsTool(cache));
        built.add(openTurnTool(cache));
        built.add(lockTurnTool(cache));
        built.add(startAdjudicationTool(cache));
        built.add(commitAdjudicationTool(cache));
        built.add(failAdjudicationTool(cache));
        built.add(retryAdjudicationTool(cache));
        built.add(publishTurnTool(cache));
        built.add(issueBriefTool(cache));
        built.add(settingProposeTool(cache));
        built.add(settingDecideTool(cache));
        this.tools = List.copyOf(built);

        Map<String, AgentTool> index = new LinkedHashMap<>();
        for (AgentTool tool : this.tools) {
            if (index.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具名重复: " + tool.name());
            }
        }
        this.byName = Map.copyOf(index);
    }

    /** 从一个必需的已认证 supervisor actor 开始构造。 */
    public static Builder builder(TaskflowEngine engine, Actor supervisor) {
        return new Builder(engine).supervisor(supervisor);
    }

    /** 创建 builder；调用 {@link Builder#supervisor(Actor)} 后才能 build。 */
    public static Builder builder(TaskflowEngine engine) {
        return new Builder(engine);
    }

    /** 便捷构造：绑定已认证 actor 与引擎，不注入事件端口。 */
    public static ExternalControlToolSet create(TaskflowEngine engine, Actor supervisor) {
        return builder(engine, supervisor).build();
    }

    /** 便捷构造：绑定已认证 actor、引擎与事件读端口。 */
    public static ExternalControlToolSet create(TaskflowEngine engine, Actor supervisor,
                                                EventReadPort eventReadPort) {
        return builder(engine, supervisor).eventReadPort(eventReadPort).build();
    }

    /** 不可变工具快照，顺序稳定。 */
    public List<AgentTool> tools() {
        return tools;
    }

    /** 按名称查找工具。 */
    public Optional<AgentTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    /** 工具名列表。 */
    public List<String> names() {
        return tools.stream().map(AgentTool::name).toList();
    }

    /** 工具数量。 */
    public int size() {
        return tools.size();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /** 原子注册整个工具集；注册只是暴露目录，实际执行仍必须经唯一授权入口。 */
    public ToolRegistry registerInto(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.registerAll(tools);
        return registry;
    }

    /** {@link #registerInto(ToolRegistry)} 的别名。 */
    public ToolRegistry registerAll(ToolRegistry registry) {
        return registerInto(registry);
    }

    /** {@link #registerInto(ToolRegistry)} 的短别名。 */
    public ToolRegistry register(ToolRegistry registry) {
        return registerInto(registry);
    }

    @Override
    public Iterator<AgentTool> iterator() {
        return tools.iterator();
    }

    // ------------------------------------------------------------------
    // 只读工具
    // ------------------------------------------------------------------

    private AgentTool statusTool() {
        return new ControlReadTool(TF_STATUS,
                "读取引擎状态、世界版本、事件计数与当前回合摘要；只读且不消费事件游标。",
                objectSchema("读取 taskflow 引擎全局状态摘要。", Map.of(), List.of()),
                context -> statusData(engine.status()));
    }

    private AgentTool turnGetTool() {
        return new ControlReadTool(TF_TURN_GET,
                "读取当前或指定回合的不可变视图、快照、决策与草稿；只读。",
                objectSchema("读取回合详情；turnId 省略时读取当前回合。",
                        Map.of("turnId", textProperty("可选回合 ID；省略则读取当前回合")),
                        List.of()),
                context -> {
                    String turnId = ControlArguments.optionalText(context.arguments(), "turnId");
                    Optional<TurnView> view = turnId.isBlank()
                            ? engine.turnView() : engine.turnView(turnId);
                    TurnView turn = view.orElseThrow(() -> new ControlFailureException(
                            "NOT_FOUND", turnId.isBlank() ? "当前没有回合" : "回合不存在: " + turnId));
                    return turnData(turn);
                });
    }

    private AgentTool eventPollTool() {
        return new ControlReadTool(TF_EVENT_POLL,
                "按 seq 排他游标读取 TaskflowEvent 摘要；不消费游标，不读取文件或日志。",
                objectSchema("读取事件流摘要。",
                        Map.of(
                                "afterSeq", integerProperty("排他游标，返回 seq > afterSeq 的事件", 0, Long.MAX_VALUE),
                                "limit", integerProperty("最多返回条数", 1, 500)),
                        List.of()),
                context -> {
                    if (eventReadPort == null) {
                        throw new ControlFailureException("EVENT_STREAM_UNAVAILABLE",
                                "控制面未注入 EventReadPort，事件流不可用");
                    }
                    long afterSeq = ControlArguments.optionalNonNegativeLong(
                            context.arguments(), "afterSeq", 0L);
                    int limit = ControlArguments.optionalInt(
                            context.arguments(), "limit", 100, 1, 500);
                    // 多取一条只用于判断 hasMore；对调用者仍严格最多返回 limit 条。
                    List<TaskflowEvent> events = eventReadPort.readAfter(afterSeq, limit + 1);
                    List<TaskflowEvent> ordered = events == null ? List.of() : events.stream()
                            .filter(Objects::nonNull)
                            .sorted(java.util.Comparator.comparingLong(TaskflowEvent::seq))
                            .limit(limit)
                            .toList();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("afterSeq", afterSeq);
                    data.put("limit", limit);
                    data.put("events", ordered.stream().map(ExternalControlToolSet::eventData).toList());
                    data.put("nextCursor", ordered.isEmpty()
                            ? afterSeq : ordered.get(ordered.size() - 1).seq());
                    data.put("hasMore", events != null && events.size() > limit);
                    return data;
                });
    }

    private AgentTool settingListTool() {
        return new ControlReadTool(TF_SETTING_LIST,
                "读取待审设定提案摘要，或按 proposalId 读取单条提案摘要；只读，"
                        + "正文仅返回最多 160 字符预览和 contentLength。",
                objectSchema("列出 PENDING 设定摘要，或按 ID 读取单条设定摘要。",
                        Map.of("proposalId", textProperty(
                                "可选提案 ID；省略时仅列出 pendingSettings()")),
                        List.of()),
                context -> {
                    String proposalId = ControlArguments.optionalText(
                            context.arguments(), "proposalId");
                    if (!proposalId.isBlank()) {
                        SettingProposal proposal = engine.settingProposal(proposalId)
                                .orElseThrow(() -> new ControlFailureException(
                                        "NOT_FOUND", "设定提案不存在: " + proposalId));
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("proposal", settingProposalSummary(proposal));
                        return data;
                    }
                    List<SettingProposal> pending = engine.pendingSettings();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", pending.size());
                    data.put("proposals", pending.stream()
                            .map(ExternalControlToolSet::settingProposalSummary).toList());
                    return data;
                });
    }

    private AgentTool invariantCheckTool() {
        return new ControlReadTool(TF_INVARIANT_CHECK,
                "检查当前引擎快照可安全判定的状态机与版本不变量；只读。",
                objectSchema("只读检查 taskflow 引擎不变量。", Map.of(), List.of()),
                context -> {
                    InvariantChecker.Result result = InvariantChecker.inspect(engine.status());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("overall", result.overall());
                    data.put("checks", result.checks().stream()
                            .map(check -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("name", check.name());
                                item.put("status", check.status());
                                item.put("detail", check.detail());
                                return item;
                            }).toList());
                    return data;
                });
    }

    // ------------------------------------------------------------------
    // 写工具
    // ------------------------------------------------------------------

    private AgentTool registerPlayerTool(ControlWriteCache cache) {
        Map<String, Object> properties = writeProperties(Map.of(
                "qqId", textProperty("QQ 账号 ID；只作为外部映射，不参与领域授权"),
                "playerId", textProperty("稳定玩家 ID"),
                "displayName", textProperty("显示名")));
        return new ControlWriteTool(TF_REGISTER_PLAYER,
                "注册玩家身份映射；expectedRevision 使用引擎 engineRevision。",
                objectSchema("注册玩家。", properties,
                        writeRequired("qqId", "playerId", "displayName")),
                cache,
                (context, args, envelope) -> {
                    PlayerProfile profile = new PlayerProfile(
                            ControlArguments.requiredText(args, "playerId"),
                            ControlArguments.requiredText(args, "qqId"),
                            ControlArguments.requiredText(args, "displayName"));
                    return ControlResults.fromCommand(TF_REGISTER_PLAYER,
                            engine.registerPlayer(supervisor, profile,
                                    envelope.expectedRevision(), envelope.idempotencyKey()));
                });
    }

    private AgentTool registerDirectorTool(ControlWriteCache cache) {
        Map<String, Object> properties = writeProperties(Map.of(
                "qqId", textProperty("QQ 账号 ID；只作为外部映射，不参与领域授权"),
                "playerId", textProperty("稳定推演身份 ID"),
                "displayName", textProperty("显示名")));
        return new ControlWriteTool(TF_REGISTER_DIRECTOR,
                "注册推演身份映射；expectedRevision 使用引擎 engineRevision。",
                objectSchema("注册推演身份。", properties,
                        writeRequired("qqId", "playerId", "displayName")),
                cache,
                (context, args, envelope) -> {
                    PlayerProfile profile = new PlayerProfile(
                            ControlArguments.requiredText(args, "playerId"),
                            ControlArguments.requiredText(args, "qqId"),
                            ControlArguments.requiredText(args, "displayName"));
                    return ControlResults.fromCommand(TF_REGISTER_DIRECTOR,
                            engine.registerDirector(supervisor, profile,
                                    envelope.expectedRevision(), envelope.idempotencyKey()));
                });
    }

    private AgentTool loadWorldFactsTool(ControlWriteCache cache) {
        Map<String, Object> factProperties = new LinkedHashMap<>();
        factProperties.put("factId", textProperty("事实 ID"));
        factProperties.put("content", textProperty("事实正文"));
        factProperties.put("visibility", visibilitySchema());
        Map<String, Object> factsItem = objectSchema("单条世界事实；worldVersion 由服务端按 expectedWorldVersion+1 计算。",
                factProperties, List.of("factId", "content", "visibility"));
        Map<String, Object> properties = writeProperties(Map.of(
                "expectedWorldVersion", integerProperty("当前世界版本，必须与引擎一致", 0, Long.MAX_VALUE),
                "facts", Map.of("type", "array", "minItems", 1,
                        "items", factsItem, "description", "待装载的新版本事实数组")));
        return new ControlWriteTool(TF_LOAD_WORLD_FACTS,
                "装载新版本世界事实；visibility 支持 PUBLIC、DIRECTOR_ONLY、PLAYERS(playerIds)。",
                objectSchema("装载世界事实。", properties,
                        writeRequired("expectedWorldVersion", "facts")),
                cache,
                (context, args, envelope) -> {
                    long expectedWorldVersion = ControlArguments.requiredNonNegativeLong(
                            args, "expectedWorldVersion");
                    if (expectedWorldVersion == Long.MAX_VALUE) {
                        throw new InvalidControlArgumentException("expectedWorldVersion 过大");
                    }
                    long targetVersion = expectedWorldVersion + 1;
                    List<Map<String, Object>> rawFacts = ControlArguments.requiredObjectList(args, "facts");
                    if (rawFacts.isEmpty()) {
                        throw new InvalidControlArgumentException("参数 facts 不能为空数组");
                    }
                    List<WorldFact> facts = new ArrayList<>(rawFacts.size());
                    for (Map<String, Object> rawFact : rawFacts) {
                        String factId = ControlArguments.requiredText(rawFact, "factId");
                        String content = ControlArguments.requiredText(rawFact, "content");
                        Object rawVersion = rawFact.get("worldVersion");
                        if (rawVersion != null) {
                            if (!(rawVersion instanceof Number number)
                                    || !(number instanceof Byte || number instanceof Short
                                    || number instanceof Integer || number instanceof Long
                                    || number instanceof java.math.BigInteger)) {
                                throw new InvalidControlArgumentException(
                                        "facts[].worldVersion 提供时必须是整数");
                            }
                            if (number.longValue() != targetVersion) {
                                throw new InvalidControlArgumentException(
                                        "facts[].worldVersion 必须等于 expectedWorldVersion+1");
                            }
                        }
                        Visibility visibility = parseVisibility(rawFact.get("visibility"));
                        try {
                            facts.add(new WorldFact(factId, targetVersion, content, visibility));
                        } catch (IllegalArgumentException invalid) {
                            throw new InvalidControlArgumentException("世界事实非法: " + invalid.getMessage());
                        }
                    }
                    return ControlResults.fromCommand(TF_LOAD_WORLD_FACTS,
                            engine.loadWorldFacts(supervisor, expectedWorldVersion, facts,
                                    envelope.expectedRevision(), envelope.idempotencyKey()));
                });
    }

    private AgentTool openTurnTool(ControlWriteCache cache) {
        Map<String, Object> properties = writeProperties(Map.of(
                "minutes", integerProperty("回合持续分钟数，1..240", 1, 240)));
        return new ControlWriteTool(TF_OPEN_TURN,
                "开启新回合；expectedRevision 使用引擎 engineRevision，minutes 必须在 1..240。",
                objectSchema("开启回合。", properties, writeRequired("minutes")),
                cache,
                (context, args, envelope) -> {
                    int minutes = ControlArguments.requiredInt(args, "minutes", 1, 240);
                    Instant closesAt = clock.instant().plus(Duration.ofMinutes(minutes));
                    return ControlResults.fromCommand(TF_OPEN_TURN,
                            engine.openTurn(supervisor, envelope.expectedRevision(),
                                    envelope.idempotencyKey(), closesAt));
                });
    }

    private AgentTool lockTurnTool(ControlWriteCache cache) {
        return new ControlWriteTool(TF_LOCK_TURN,
                "冻结当前 OPEN 回合并生成不可变快照；expectedRevision 使用当前回合 revision。",
                writeSchema("冻结当前回合。"),
                cache,
                (context, args, envelope) -> ControlResults.fromCommand(TF_LOCK_TURN,
                        engine.lockTurn(supervisor, envelope.expectedRevision(),
                                envelope.idempotencyKey())));
    }

    private AgentTool startAdjudicationTool(ControlWriteCache cache) {
        return new ControlWriteTool(TF_START_ADJUDICATION,
                "把 LOCKED 回合推进到 ADJUDICATING；expectedRevision 使用当前回合 revision。",
                writeSchema("启动推演。"),
                cache,
                (context, args, envelope) -> ControlResults.fromCommand(TF_START_ADJUDICATION,
                        engine.startAdjudication(supervisor, envelope.expectedRevision(),
                                envelope.idempotencyKey())));
    }

    private AgentTool commitAdjudicationTool(ControlWriteCache cache) {
        Map<String, Object> deltaProperties = new LinkedHashMap<>();
        deltaProperties.put("type", textProperty("白名单内的 worldDelta 类型"));
        deltaProperties.put("targetId", textProperty("变化目标 ID"));
        deltaProperties.put("value", textProperty("变化值，可省略"));
        Map<String, Object> deltaSchema = objectSchema("一条结构化世界变化。",
                deltaProperties, List.of("type", "targetId"));
        Map<String, Object> properties = writeProperties(Map.of(
                "publicNarrative", textProperty("公开叙述"),
                "playerOutcomes", Map.of("type", "object",
                        "description", "playerId -> 该玩家可见结果",
                        "additionalProperties", Map.of("type", "string")),
                "worldDeltas", Map.of("type", "array",
                        "items", deltaSchema, "description", "结构化世界变化数组"),
                "nextHooks", Map.of("type", "array",
                        "items", Map.of("type", "string"), "description", "下一回合钩子")));
        return new ControlWriteTool(TF_COMMIT_ADJUDICATION,
                "构造并原子提交 AdjudicationProposal；expectedRevision 使用当前回合 revision。",
                objectSchema("提交推演提案。", properties,
                        writeRequired("publicNarrative", "playerOutcomes", "worldDeltas", "nextHooks")),
                cache,
                (context, args, envelope) -> {
                    Optional<TurnView> current = engine.turnView();
                    if (current.isEmpty()) {
                        return ControlResults.failure(TF_COMMIT_ADJUDICATION,
                                "WRONG_STATE", "当前没有进行中的回合");
                    }
                    TurnView turn = current.get();
                    String publicNarrative = ControlArguments.requiredText(args, "publicNarrative");
                    Map<String, String> outcomes = ControlArguments.requiredStringMap(args, "playerOutcomes");
                    List<WorldDelta> deltas = parseWorldDeltas(args);
                    List<String> hooks = ControlArguments.optionalTextList(args, "nextHooks");
                    long expectedWorldVersion = engine.status().worldVersion();
                    AdjudicationProposal proposal;
                    try {
                        proposal = AdjudicationProposal.create(
                                AdjudicationProposal.CURRENT_SCHEMA_VERSION,
                                turn.turnId(), expectedWorldVersion, publicNarrative,
                                outcomes, deltas, hooks);
                    } catch (IllegalArgumentException invalid) {
                        throw new InvalidControlArgumentException("推演提案非法: " + invalid.getMessage());
                    }
                    return ControlResults.fromCommand(TF_COMMIT_ADJUDICATION,
                            engine.commitAdjudication(supervisor, envelope.expectedRevision(),
                                    envelope.idempotencyKey(), proposal));
                });
    }

    private AgentTool failAdjudicationTool(ControlWriteCache cache) {
        return new ControlWriteTool(TF_FAIL_ADJUDICATION,
                "把 ADJUDICATING 回合标记为 FAILED_RETRYABLE；reason 同时作为审计与失败原因。",
                writeSchema("标记推演失败。"),
                cache,
                (context, args, envelope) -> ControlResults.fromCommand(TF_FAIL_ADJUDICATION,
                        engine.failAdjudication(supervisor, envelope.reason(),
                                envelope.expectedRevision(), envelope.idempotencyKey())));
    }

    private AgentTool retryAdjudicationTool(ControlWriteCache cache) {
        return new ControlWriteTool(TF_RETRY_ADJUDICATION,
                "从 FAILED_RETRYABLE 重新进入 ADJUDICATING；expectedRevision 使用当前回合 revision。",
                writeSchema("重试推演。"),
                cache,
                (context, args, envelope) -> ControlResults.fromCommand(TF_RETRY_ADJUDICATION,
                        engine.retryAdjudication(supervisor, envelope.expectedRevision(),
                                envelope.idempotencyKey())));
    }

    private AgentTool publishTurnTool(ControlWriteCache cache) {
        return new ControlWriteTool(TF_PUBLISH_TURN,
                "把 COMMITTED 回合推进到 PUBLISHED；expectedRevision 使用当前回合 revision。",
                writeSchema("发布回合。"),
                cache,
                (context, args, envelope) -> ControlResults.fromCommand(TF_PUBLISH_TURN,
                        engine.publishTurn(supervisor, envelope.expectedRevision(),
                                envelope.idempotencyKey())));
    }

    private AgentTool settingProposeTool(ControlWriteCache cache) {
        Map<String, Object> properties = writeProperties(Map.of(
                "content", textProperty("设定正文，1..8192 字符"),
                "visibility", visibilitySchema()));
        return new ControlWriteTool(TF_SETTING_PROPOSE,
                "提交运行期设定提案；actor 固定为绑定 supervisor，expectedRevision 使用 engineRevision。",
                objectSchema("提交设定提案。", properties,
                        writeRequired("content", "visibility")),
                cache,
                (context, args, envelope) -> {
                    String content = ControlArguments.requiredText(args, "content");
                    if (content.length() > SettingProposal.MAX_CONTENT_CHARS) {
                        throw new InvalidControlArgumentException(
                                "参数 content 不能超过 " + SettingProposal.MAX_CONTENT_CHARS + " 字符");
                    }
                    Visibility visibility = parseVisibility(args.get("visibility"));
                    return ControlResults.fromCommand(TF_SETTING_PROPOSE,
                            engine.proposeSetting(supervisor, content, visibility,
                                    envelope.expectedRevision(), envelope.idempotencyKey()));
                });
    }

    private AgentTool settingDecideTool(ControlWriteCache cache) {
        Map<String, Object> properties = writeProperties(Map.of(
                "proposalId", textProperty("待审设定提案 ID"),
                "approve", Map.of("type", "boolean",
                        "description", "true 批准并生成世界事实；false 拒绝"),
                "note", textProperty("可选审核说明")));
        return new DestructiveControlWriteTool(TF_SETTING_DECIDE,
                "审核设定提案；批准生成稳定世界事实并提升 worldVersion，拒绝只改变提案状态。"
                        + "expectedRevision 使用 engineRevision。",
                objectSchema("批准或拒绝设定提案。", properties,
                        writeRequired("proposalId", "approve")),
                cache,
                (context, args, envelope) -> {
                    String proposalId = ControlArguments.requiredText(args, "proposalId");
                    Object rawApprove = args.get("approve");
                    if (!(rawApprove instanceof Boolean approve)) {
                        throw new InvalidControlArgumentException("参数 approve 必须是布尔值");
                    }
                    String note = optionalNote(args.get("note"));
                    return ControlResults.fromCommand(TF_SETTING_DECIDE,
                            engine.decideSetting(supervisor, proposalId, approve, note,
                                    envelope.expectedRevision(), envelope.idempotencyKey()));
                });
    }

    private AgentTool issueBriefTool(ControlWriteCache cache) {
        Map<String, Object> properties = writeProperties(Map.of(
                "requestId", textProperty("已由玩家/秘书发起的信息请求 ID"),
                "content", textProperty("经过 GM 审查和过滤后的 brief 内容"),
                "sourceFactIds", Map.of("type", "array", "minItems", 1,
                        "items", Map.of("type", "string"),
                        "description", "来源事实 ID；每个事实必须对请求玩家可见"),
                "expiresAt", textProperty("可选 ISO-8601 过期时间；省略时默认当前时间 + 24h")));
        return new ControlWriteTool(TF_ISSUE_BRIEF,
                "签发 brief；引擎会逐项校验来源事实对请求玩家可见。绑定的 GM/DIRECTOR/SUPERVISOR 可签发，但仍不能伪造玩家硬确认。",
                objectSchema("签发 brief。", properties,
                        writeRequired("requestId", "content", "sourceFactIds")),
                cache,
                (context, args, envelope) -> {
                    String requestId = ControlArguments.requiredText(args, "requestId");
                    String content = ControlArguments.requiredText(args, "content");
                    List<String> sourceFactIds = ControlArguments.requiredTextList(args, "sourceFactIds");
                    Instant expiresAt = optionalInstant(args, "expiresAt",
                            clock.instant().plus(Duration.ofHours(24)));
                    if (!expiresAt.isAfter(clock.instant())) {
                        throw new InvalidControlArgumentException("expiresAt 必须晚于当前时间");
                    }
                    return ControlResults.fromCommand(TF_ISSUE_BRIEF,
                            engine.issueBrief(supervisor, requestId, content, sourceFactIds,
                                    expiresAt, envelope.expectedRevision(), envelope.idempotencyKey()));
                });
    }

    // ------------------------------------------------------------------
    // 视图与参数工具
    // ------------------------------------------------------------------

    private static Map<String, Object> statusData(EngineStatus status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("engineRevision", status.engineRevision());
        data.put("worldVersion", status.worldVersion());
        data.put("playerCount", status.playerCount());
        data.put("directorCount", status.directorCount());
        data.put("eventCount", status.eventCount());
        data.put("hasActiveTurn", status.hasActiveTurn());
        data.put("turn", status.turn() == null ? null : turnSummary(status.turn()));
        return data;
    }

    private static Map<String, Object> turnSummary(TurnView turn) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("turnId", turn.turnId());
        summary.put("turnNo", turn.turnNo());
        summary.put("state", turn.state().name());
        summary.put("revision", turn.revision());
        summary.put("worldVersion", turn.worldVersion());
        summary.put("promptVersion", turn.promptVersion());
        summary.put("opensAt", turn.opensAt());
        summary.put("closesAt", turn.closesAt());
        summary.put("lockedAt", turn.lockedAt());
        summary.put("draftCount", turn.drafts().size());
        summary.put("decisionCount", turn.decisions().size());
        summary.put("hasSnapshot", turn.hasSnapshot());
        summary.put("hasCommittedProposal", turn.committedProposal() != null);
        return summary;
    }

    private static Map<String, Object> turnData(TurnView turn) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turn", turnSummary(turn));
        data.put("drafts", turn.drafts().stream()
                .map(ExternalControlToolSet::draftData).toList());
        data.put("decisions", turn.decisions().stream()
                .map(ExternalControlToolSet::decisionData).toList());
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
        data.put("resources", safeMap(draft.resources()));
        data.put("target", draft.target());
        data.put("contingency", draft.contingency());
        data.put("previewHash", draft.previewHash());
        data.put("status", draft.status().name());
        data.put("revision", draft.revision());
        data.put("expiresAt", draft.expiresAt());
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
        data.put("confirmedAt", decision.confirmedAt());
        // 用户要求外部总控可见决策正文；这里仍只展开受限 JSON 值并过滤敏感键。
        data.put("fields", safeMap(decision.fields()));
        return data;
    }

    private static Map<String, Object> snapshotData(TurnSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turnId", snapshot.turnId());
        data.put("turnNo", snapshot.turnNo());
        data.put("worldVersion", snapshot.worldVersion());
        data.put("promptVersion", snapshot.promptVersion());
        data.put("decisions", snapshot.decisions().stream()
                .map(ExternalControlToolSet::decisionData).toList());
        data.put("confirmedAt", snapshot.confirmedAt());
        data.put("contentHash", snapshot.contentHash());
        return data;
    }

    private static Map<String, Object> proposalData(AdjudicationProposal proposal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", proposal.schemaVersion());
        data.put("turnId", proposal.turnId());
        data.put("expectedWorldVersion", proposal.expectedWorldVersion());
        data.put("publicNarrative", proposal.publicNarrative());
        data.put("playerOutcomes", safeMap(proposal.playerOutcomes()));
        data.put("worldDeltas", proposal.worldDeltas().stream().map(delta -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", delta.type());
            item.put("targetId", delta.targetId());
            item.put("value", delta.value());
            return item;
        }).toList());
        data.put("nextHooks", proposal.nextHooks());
        data.put("proposalHash", proposal.proposalHash());
        return data;
    }

    private static Map<String, Object> eventData(TaskflowEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", event.seq());
        data.put("occurredAt", event.occurredAt());
        data.put("type", event.type().name());
        data.put("actorRole", event.actorRole().name());
        data.put("actorId", event.actorId());
        data.put("turnId", event.turnId());
        data.put("payload", safeMap(event.payload()));
        return data;
    }

    private static long lastEventSeq(List<TaskflowEvent> events) {
        if (events.isEmpty()) {
            return -1;
        }
        return events.stream().mapToLong(TaskflowEvent::seq).max().orElse(-1);
    }

    private static Object safeJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                safe.put(key, sensitiveKey(key) ? "[redacted]" : safeJsonValue(entry.getValue()));
            }
            return safe;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> safe = new ArrayList<>();
            for (Object item : iterable) {
                safe.add(safeJsonValue(item));
            }
            return safe;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> safe = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                safe.add(safeJsonValue(java.lang.reflect.Array.get(value, index)));
            }
            return safe;
        }
        return "[redacted]";
    }

    private static Map<String, Object> safeMap(Map<?, ?> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (source == null) {
            return safe;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            safe.put(key, sensitiveKey(key) ? "[redacted]" : safeJsonValue(entry.getValue()));
        }
        return safe;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("authorization") || normalized.contains("credential")
                || normalized.contains("api_key") || normalized.contains("apikey");
    }

    private static Map<String, Object> visibilitySchema() {
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("description", "PUBLIC、DIRECTOR_ONLY，或 {\"type\":\"PLAYERS\",\"playerIds\":[...]}");
        visible.put("oneOf", List.of(
                Map.of("type", "string", "enum", List.of("PUBLIC", "DIRECTOR_ONLY")),
                Map.of("type", "object",
                        "properties", Map.of(
                                "type", Map.of("type", "string", "enum", List.of("PLAYERS")),
                                "playerIds", Map.of("type", "array", "minItems", 1,
                                        "items", Map.of("type", "string"))),
                        "required", List.of("type", "playerIds"),
                        "additionalProperties", false)));
        return visible;
    }

    private static Map<String, Object> settingProposalSummary(SettingProposal proposal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proposalId", proposal.proposalId());
        data.put("authorActorId", proposal.authorActorId());
        data.put("authorRole", proposal.authorRole().name());
        data.put("visibility", visibilityData(proposal.visibility()));
        data.put("status", proposal.status().name());
        data.put("createdAt", proposal.createdAt());
        data.put("reviewerActorId", proposal.reviewerActorId());
        data.put("hasReviewNote", proposal.reviewNote() != null
                && !proposal.reviewNote().isBlank());
        data.put("decidedAt", proposal.decidedAt());
        data.put("worldFactId", proposal.worldFactId());
        data.put("contentLength", proposal.content().length());
        String preview = proposal.content().replace('\n', ' ').replace('\r', ' ');
        boolean truncated = preview.length() > 160;
        if (truncated) {
            preview = preview.substring(0, 160);
        }
        data.put("contentPreview", preview);
        data.put("contentPreviewTruncated", truncated);
        return data;
    }

    private static Object visibilityData(Visibility visibility) {
        if (visibility instanceof Visibility.PublicVisibility) {
            return "PUBLIC";
        }
        if (visibility instanceof Visibility.DirectorOnlyVisibility) {
            return "DIRECTOR_ONLY";
        }
        if (visibility instanceof Visibility.PlayerSetVisibility playerSet) {
            return Map.of("type", "PLAYERS",
                    "playerIds", playerSet.playerIds().stream().sorted().toList());
        }
        return visibility.getClass().getSimpleName();
    }

    private static String optionalNote(Object raw) {
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text)) {
            throw new InvalidControlArgumentException("参数 note 提供时必须是字符串");
        }
        return text.trim();
    }

    private static Visibility parseVisibility(Object raw) {
        if (raw instanceof String text) {
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "PUBLIC" -> Visibility.PUBLIC;
                case "DIRECTOR_ONLY" -> Visibility.DIRECTOR_ONLY;
                case "PLAYERS", "PLAYER_SET", "PLAYERS_ONLY" ->
                        throw new InvalidControlArgumentException(
                                "PLAYERS 可见性必须提供 playerIds");
                default -> throw new InvalidControlArgumentException("非法 visibility: " + text);
            };
        }
        if (raw instanceof Map<?, ?> map) {
            Object rawType = firstPresent(map, "type", "kind", "visibility", "mode");
            if (!(rawType instanceof String typeText)) {
                throw new InvalidControlArgumentException("visibility 对象缺少 type");
            }
            String type = typeText.trim().toUpperCase(Locale.ROOT);
            return switch (type) {
                case "PUBLIC" -> Visibility.PUBLIC;
                case "DIRECTOR_ONLY" -> Visibility.DIRECTOR_ONLY;
                case "PLAYERS", "PLAYER_SET", "PLAYERS_ONLY" -> {
                    Object rawIds = firstPresent(map, "playerIds", "players", "ids");
                    yield Visibility.forPlayers(readPlayerIds(rawIds));
                }
                default -> throw new InvalidControlArgumentException("非法 visibility type: " + typeText);
            };
        }
        throw new InvalidControlArgumentException("visibility 必须是字符串或对象");
    }

    private static Set<String> readPlayerIds(Object raw) {
        if (!(raw instanceof Iterable<?> list)) {
            throw new InvalidControlArgumentException("PLAYERS 可见性需要非空 playerIds 数组");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new InvalidControlArgumentException("playerIds 不能包含空字符串");
            }
            values.add(text);
        }
        if (values.isEmpty()) {
            throw new InvalidControlArgumentException("PLAYERS 可见性需要非空 playerIds 数组");
        }
        return Set.copyOf(values);
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static List<WorldDelta> parseWorldDeltas(Map<String, Object> args) {
        List<Map<String, Object>> raw = ControlArguments.requiredObjectList(args, "worldDeltas");
        List<WorldDelta> deltas = new ArrayList<>(raw.size());
        for (Map<String, Object> item : raw) {
            String type = ControlArguments.requiredText(item, "type");
            String targetId = ControlArguments.requiredText(item, "targetId");
            String value = ControlArguments.optionalText(item, "value");
            try {
                deltas.add(new WorldDelta(type, targetId, value));
            } catch (IllegalArgumentException invalid) {
                throw new InvalidControlArgumentException("worldDelta 非法: " + invalid.getMessage());
            }
        }
        return List.copyOf(deltas);
    }

    private static Instant optionalInstant(Map<String, Object> args, String name, Instant defaultValue) {
        String raw = ControlArguments.optionalText(args, name);
        if (raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException invalid) {
            throw new InvalidControlArgumentException("参数 " + name + " 必须是 ISO-8601 时间");
        }
    }

    /** 与普通写工具相同，但显式声明 destructive=true 的危险审核工具。 */
    private static final class DestructiveControlWriteTool extends AbstractControlTool {
        @FunctionalInterface
        interface Body {
            ToolResult execute(ToolContext context, Map<String, Object> arguments,
                               WriteEnvelope envelope);
        }

        private final Body body;
        private final ControlWriteCache cache;

        private DestructiveControlWriteTool(String name, String description,
                                            Map<String, Object> schema,
                                            ControlWriteCache cache, Body body) {
            super(name, description, schema, ToolSpec.level(AccessToken.DEFAULT, true, true));
            this.body = body;
            this.cache = cache;
        }

        @Override
        protected Operation operation() {
            return Operation.WRITE;
        }

        @Override
        protected ToolResult executeChecked(ToolContext context) {
            Map<String, Object> arguments = context.arguments();
            WriteEnvelope envelope = WriteEnvelope.from(arguments);
            return cache.execute(envelope.idempotencyKey(),
                    () -> body.execute(context, arguments, envelope));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectSchema(String description,
                                                     Map<String, Object> properties,
                                                     List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return (Map<String, Object>) deepFreeze(schema);
    }

    /** 递归冻结 schema，保证工具目录拿到的是真正的不可变快照。 */
    private static Object deepFreeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                frozen.put(entry.getKey(), deepFreeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) {
                frozen.add(deepFreeze(item));
            }
            return List.copyOf(frozen);
        }
        return value;
    }

    private static Map<String, Object> writeSchema(String description) {
        return objectSchema(description, writeProperties(Map.of()), writeRequired());
    }

    private static Map<String, Object> writeProperties(Map<String, Object> toolProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(toolProperties);
        properties.put("expectedRevision", integerProperty(
                "乐观锁 revision；全局工具用 engineRevision，回合工具用当前回合 revision", 0, Long.MAX_VALUE));
        properties.put("idempotencyKey", textProperty("客户端生成的幂等键，1..128 字符"));
        properties.put("reason", textProperty("审计原因，1..512 字符"));
        return properties;
    }

    private static List<String> writeRequired(String... toolFields) {
        List<String> required = new ArrayList<>();
        required.add("expectedRevision");
        required.add("idempotencyKey");
        required.add("reason");
        required.addAll(List.of(toolFields));
        return List.copyOf(required);
    }

    private static Map<String, Object> textProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description, long minimum, long maximum) {
        return Map.of("type", "integer", "description", description,
                "minimum", minimum, "maximum", maximum);
    }

    /** 控制面 builder：绑定引擎与已认证 actor（默认角色 SUPERVISOR）。 */
    public static final class Builder {

        private final TaskflowEngine engine;
        private Actor supervisor;
        private Clock clock = Clock.systemUTC();
        private EventReadPort eventReadPort;

        private Builder(TaskflowEngine engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        /**
         * 绑定宿主已认证的 Actor。角色由宿主决定；{@code tf_issue_brief} 接受
         * GM/DIRECTOR/SUPERVISOR，但仍不能代替玩家提交或确认决策。
         */
        public Builder supervisor(Actor supervisor) {
            this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
            return this;
        }

        /** {@link #supervisor(Actor)} 的语义别名。 */
        public Builder actor(Actor actor) {
            return supervisor(actor);
        }

        /** 以 actorId 构造默认角色 SUPERVISOR 的 actor；仅用于宿主已有认证上下文的装配。 */
        public Builder supervisor(String actorId) {
            return supervisor(new Actor(actorId, ActorRole.SUPERVISOR));
        }

        /** 注入事件读端口；不注入时 event_poll 稳定返回 EVENT_STREAM_UNAVAILABLE。 */
        public Builder eventReadPort(EventReadPort eventReadPort) {
            this.eventReadPort = eventReadPort;
            return this;
        }

        /** 直接注入内存事件存储的只读适配器（便捷装配）。 */
        public Builder inMemoryEventStore(InMemoryEventStore store) {
            this.eventReadPort = EventReadPort.from(Objects.requireNonNull(store, "store"));
            return this;
        }

        /** 注入与引擎一致的时钟；open_turn 截止时间与 brief 默认过期时间使用它。 */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** 构造不可变工具集。 */
        public ExternalControlToolSet build() {
            if (supervisor == null) {
                throw new IllegalStateException("必须先绑定已认证 supervisor Actor");
            }
            return new ExternalControlToolSet(this);
        }
    }

}
