package io.mosire.iibot.app.mcp;

import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.control.ControlOperationResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 动态注册玩家并建立其秘书 Session/workspace。 */
public final class ControlRegisterPlayerTool extends ControlBootstrapToolSupport.BaseTool {

    public static final String NAME = "tf_control_register_player";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "注册玩家；engine 是身份事实源，成功后建立隔离 workspace 与秘书 Session。",
            "properties", Map.of(
                    "playerId", Map.of("type", "string", "description", "稳定玩家 ID"),
                    "qqId", Map.of("type", "string", "description", "玩家 QQ"),
                    "displayName", Map.of("type", "string", "description", "显示名"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键返回首次回执"),
                    "reason", Map.of("type", "string",
                            "description", "审计原因（可选）")),
            "required", List.of("playerId", "qqId", "displayName", "idempotencyKey"),
            "additionalProperties", false);

    private final ControlBootstrapService service;

    public ControlRegisterPlayerTool(ControlBootstrapService service) {
        super(NAME, "动态注册玩家；冲突 fail-closed，重复完整身份幂等。", SCHEMA);
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected ControlOperationResult runChecked(Map<String, Object> arguments) {
        String playerId = ControlBootstrapToolSupport.requiredText(
                arguments, "playerId", ControlBootstrapToolSupport.MAX_ID_CHARS);
        String qqId = ControlBootstrapToolSupport.requiredText(
                arguments, "qqId", ControlBootstrapToolSupport.MAX_QQ_CHARS);
        String displayName = ControlBootstrapToolSupport.requiredText(
                arguments, "displayName", ControlBootstrapToolSupport.MAX_DISPLAY_NAME_CHARS);
        String key = ControlBootstrapToolSupport.requiredText(
                arguments, "idempotencyKey", ControlBootstrapToolSupport.MAX_KEY_CHARS);
        String reason = ControlBootstrapToolSupport.optionalText(
                arguments, "reason", ControlBootstrapToolSupport.MAX_REASON_CHARS);
        return service.registerPlayer(playerId, qqId, displayName, key, reason);
    }
}
