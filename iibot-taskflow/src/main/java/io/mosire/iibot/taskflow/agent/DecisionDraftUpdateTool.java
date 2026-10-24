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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 秘书工具：安全更新当前玩家自己的 DRAFT。
 *
 * <p>只允许更新宿主绑定玩家在宿主绑定回合中的草稿；draftId 来自模型参数，但必须与当前玩家
 * 匹配，最终仍由引擎做 owner/turn/status 校验。未给出的字段沿用旧值，过期时间不延长。</p>
 */
public final class DecisionDraftUpdateTool implements AgentTool {

    public static final String NAME = "decision_draft_update";

    private static final Set<String> ROLES = Set.of("secretary");
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "draftId", Map.of("type", "string", "description", "当前玩家要更新的草稿 ID"),
                    "intent", Map.of("type", "string", "description", "新的核心意图"),
                    "actions", Map.of("type", "array", "description", "新的行动列表",
                            "items", Map.of("type", "string")),
                    "resources", Map.of("type", "object", "description", "新的资源映射",
                            "additionalProperties", Map.of("type", "string")),
                    "target", Map.of("type", "string", "description", "新的目标"),
                    "contingency", Map.of("type", "string", "description", "新的应急方案")),
            "required", List.of("draftId"),
            "additionalProperties", false);

    private final TaskflowEngine engine;

    public DecisionDraftUpdateTool(TaskflowEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "更新当前私聊玩家自己的未确认决策草稿；不能更新他人的草稿，也不能确认。"
                + "成功后会返回新的 previewHash 与预览，玩家需重新发送 /确认 <短确认码>。";
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
            String draftId = SecretaryToolSupport.requireString(args, "draftId");
            DecisionDraft existing = engine.draft(draftId).orElseThrow(
                    () -> new IllegalArgumentException("草稿不存在"));
            if (!existing.playerId().equals(bound.playerId())) {
                return ToolResult.error("DRAFT_NOT_OWNED", "不能修改他人草稿");
            }
            if (!existing.turnId().equals(turn.turnId())) {
                return ToolResult.error("DRAFT_NOT_FOUND", "草稿不属于当前回合");
            }
            DecisionDraftInput input = new DecisionDraftInput(
                    SecretaryToolSupport.optionalString(args, "intent", existing.intent()),
                    SecretaryToolSupport.optionalStringList(args, "actions", existing.actions()),
                    SecretaryToolSupport.optionalStringMap(args, "resources", existing.resources()),
                    SecretaryToolSupport.optionalString(args, "target", existing.target()),
                    SecretaryToolSupport.optionalString(args, "contingency", existing.contingency()),
                    existing.expiresAt());
            CommandResult result = engine.updateDecisionDraft(bound.actor(), draftId, input,
                    turn.revision(), SecretaryToolSupport.idempotencyKey("draft-update"));
            if (!result.ok()) {
                return ToolResult.error(result.code().name(), result.message());
            }
            DecisionDraft updated = engine.draft(draftId).orElseThrow(
                    () -> new IllegalStateException("更新后的草稿不存在"));
            return ToolResult.ok(SecretaryToolSupport.preview(updated));
        } catch (IllegalArgumentException invalid) {
            return SecretaryToolSupport.invalid(invalid.getMessage());
        } catch (RuntimeException unexpected) {
            return SecretaryToolSupport.internal();
        }
    }
}
