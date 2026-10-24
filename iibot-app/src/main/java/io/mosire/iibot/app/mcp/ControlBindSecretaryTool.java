package io.mosire.iibot.app.mcp;

import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.control.ControlOperationResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 已注册玩家的秘书 Session 幂等绑定工具。 */
public final class ControlBindSecretaryTool extends ControlBootstrapToolSupport.BaseTool {

    public static final String NAME = "tf_control_bind_secretary";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "为已注册玩家幂等创建/返回秘书 Session；玩家必须已注册。",
            "properties", Map.of(
                    "playerId", Map.of("type", "string", "description", "已注册玩家 ID"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键返回首次回执"),
                    "reason", Map.of("type", "string",
                            "description", "审计原因（可选）")),
            "required", List.of("playerId", "idempotencyKey"),
            "additionalProperties", false);

    private final ControlBootstrapService service;

    public ControlBindSecretaryTool(ControlBootstrapService service) {
        super(NAME, "幂等绑定已注册玩家的秘书 Session。", SCHEMA);
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected ControlOperationResult runChecked(Map<String, Object> arguments) {
        String playerId = ControlBootstrapToolSupport.requiredText(
                arguments, "playerId", ControlBootstrapToolSupport.MAX_ID_CHARS);
        String key = ControlBootstrapToolSupport.requiredText(
                arguments, "idempotencyKey", ControlBootstrapToolSupport.MAX_KEY_CHARS);
        ControlBootstrapToolSupport.optionalText(
                arguments, "reason", ControlBootstrapToolSupport.MAX_REASON_CHARS);
        return service.bindSecretary(playerId, key);
    }
}
