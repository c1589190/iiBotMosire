package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.DecisionDraftInput;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnView;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 秘书工具：创建当前玩家自己的决策草稿。
 *
 * <p>身份只从 {@link ToolContext#config()} 读取，模型参数 schema 中没有 playerId。创建成功后
 * 回执包含 draftId、previewHash、turnId 与完整预览，并明确提示玩家在私聊发送
 * {@code /确认 <draftId>}。</p>
 */
public final class DecisionDraftCreateTool implements AgentTool {

    public static final String NAME = "decision_draft_create";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "intent", Map.of("type", "string",
                            "description", "玩家本轮想达成的核心意图，不能为空"),
                    "actions", Map.of("type", "array",
                            "description", "可执行行动列表",
                            "items", Map.of("type", "string")),
                    "resources", Map.of("type", "object",
                            "description", "拟投入的资源，键和值均为字符串",
                            "additionalProperties", Map.of("type", "string")),
                    "target", Map.of("type", "string", "description", "行动目标"),
                    "contingency", Map.of("type", "string", "description", "失败或意外时的应急方案")),
            "required", List.of("intent"),
            "additionalProperties", false);

    private final TaskflowEngine engine;
    private final Clock clock;

    public DecisionDraftCreateTool(TaskflowEngine engine, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "为当前私聊玩家创建一份结构化决策草稿；不会确认决策。成功结果包含 draftId、"
                + "previewHash 和预览，玩家必须之后在私聊发送 /确认 <draftId>。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
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
            SecretaryToolContext.BoundPlayer bound = SecretaryToolContext.requirePlayer(context);
            TurnView turn = SecretaryToolContext.requireBoundTurn(engine, bound);
            Map<String, Object> args = context.arguments();
            DecisionDraftInput input = new DecisionDraftInput(
                    SecretaryToolSupport.requireString(args, "intent"),
                    args.containsKey("actions")
                            ? SecretaryToolSupport.stringList(args, "actions") : List.of(),
                    args.containsKey("resources")
                            ? SecretaryToolSupport.stringMap(args, "resources") : Map.of(),
                    SecretaryToolSupport.optionalString(args, "target", ""),
                    SecretaryToolSupport.optionalString(args, "contingency", ""),
                    clock.instant().plus(SecretaryToolContext.draftTtl(context)));
            CommandResult result = engine.createDecisionDraft(bound.actor(), input,
                    turn.revision(), SecretaryToolSupport.idempotencyKey("draft-create"));
            if (!result.ok()) {
                return ToolResult.error(result.code().name(), result.message());
            }
            String draftId = result.referenceId().orElseThrow(
                    () -> new IllegalStateException("引擎成功回执缺少 draftId"));
            DecisionDraft draft = engine.draft(draftId).orElseThrow(
                    () -> new IllegalStateException("引擎成功回执对应的草稿不存在"));
            return ToolResult.ok(SecretaryToolSupport.preview(draft));
        } catch (IllegalArgumentException invalid) {
            return SecretaryToolSupport.invalid(invalid.getMessage());
        } catch (RuntimeException unexpected) {
            return SecretaryToolSupport.internal();
        }
    }
}
