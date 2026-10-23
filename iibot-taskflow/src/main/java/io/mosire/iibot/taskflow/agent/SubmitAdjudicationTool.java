package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.WorldDelta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GM 唯一提交工具：把结构化参数变成 {@link AdjudicationProposal} 并交给引擎原子提交。
 *
 * <p>turnId、expectedWorldVersion 和 proposalHash 都由宿主绑定/计算，模型参数里没有这些可伪造
 * 字段。工具声明 sensitive + destructive，必须经授权器并持有 GM/DIRECTOR/SUPERVISOR 角色。</p>
 */
public final class SubmitAdjudicationTool implements AgentTool {

    public static final String NAME = "submit_adjudication";

    private static final Set<String> ROLES = Set.of("gm", "director", "supervisor");
    private static final ToolSpec SPEC = ToolSpec.level(AccessToken.DEFAULT, true, true);
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "publicNarrative", Map.of("type", "string",
                            "description", "面向公开通道的推演叙述"),
                    "playerOutcomes", Map.of("type", "object",
                            "description", "playerId 到该玩家私密结果的映射",
                            "additionalProperties", Map.of("type", "string")),
                    "worldDeltas", Map.of("type", "array",
                            "description", "结构化世界变化列表",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "type", Map.of("type", "string"),
                                            "targetId", Map.of("type", "string"),
                                            "value", Map.of("type", "string")),
                                    "required", List.of("type", "targetId"),
                                    "additionalProperties", false)),
                    "nextHooks", Map.of("type", "array",
                            "description", "下一回合可用的钩子",
                            "items", Map.of("type", "string"))),
            "required", List.of("publicNarrative", "playerOutcomes", "worldDeltas", "nextHooks"),
            "additionalProperties", false);

    private final TaskflowEngine engine;
    private final io.mosire.iibot.taskflow.Actor gm;
    private final String turnId;
    private final long expectedWorldVersion;

    public SubmitAdjudicationTool(TaskflowEngine engine, io.mosire.iibot.taskflow.Actor gm,
                                  String turnId, long expectedWorldVersion) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gm = Objects.requireNonNull(gm, "gm");
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
        if (expectedWorldVersion < 0) {
            throw new IllegalArgumentException("expectedWorldVersion 不能为负");
        }
        this.turnId = turnId;
        this.expectedWorldVersion = expectedWorldVersion;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "提交唯一一份中央推演提案。工具会用冻结上下文生成 proposalHash 并调用引擎原子提交；"
                + "失败零世界变更，成功后回合进入 COMMITTED。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Set<String> roles() {
        return ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String narrative = SecretaryToolSupport.requireString(args, "publicNarrative");
            Map<String, String> outcomes = SecretaryToolSupport.stringMap(args, "playerOutcomes");
            List<WorldDelta> deltas = parseDeltas(args.get("worldDeltas"));
            List<String> hooks = SecretaryToolSupport.stringList(args, "nextHooks");

            AdjudicationProposal proposal = AdjudicationProposal.create(
                    AdjudicationProposal.CURRENT_SCHEMA_VERSION,
                    turnId, expectedWorldVersion, narrative, outcomes, deltas, hooks);
            TurnView turn = engine.turnView().orElseThrow(
                    () -> new IllegalArgumentException("当前没有进行中的回合"));
            if (!turn.turnId().equals(turnId)) {
                return ToolResult.error("PROPOSAL_INVALID", "提案 turnId 与当前回合不一致");
            }
            CommandResult result = engine.commitAdjudication(gm, turn.revision(),
                    SecretaryToolSupport.idempotencyKey("adjudication-commit"), proposal);
            if (!result.ok()) {
                return ToolResult.error(result.code().name(), result.message());
            }
            return ToolResult.ok("推演提案已提交。\nturnId: " + turnId
                    + "\nproposalHash: " + proposal.proposalHash()
                    + "\nworldVersion: " + result.details().getOrDefault("worldVersion", "?")
                    + "\nturnState: " + result.turnState());
        } catch (IllegalArgumentException invalid) {
            return SecretaryToolSupport.invalid(invalid.getMessage());
        } catch (RuntimeException unexpected) {
            return SecretaryToolSupport.internal();
        }
    }

    private static List<WorldDelta> parseDeltas(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("worldDeltas 必须是数组");
        }
        List<WorldDelta> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("worldDeltas 每项必须是对象");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, itemValue) -> {
                if (key instanceof String text) {
                    map.put(text, itemValue);
                }
            });
            String type = SecretaryToolSupport.requireString(map, "type");
            String targetId = SecretaryToolSupport.requireString(map, "targetId");
            String deltaValue = map.containsKey("value")
                    ? SecretaryToolSupport.optionalString(map, "value", "") : "";
            result.add(new WorldDelta(type, targetId, deltaValue));
        }
        return List.copyOf(result);
    }
}
