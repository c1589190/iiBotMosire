package io.mosire.iibot.app.mcp;

import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.control.ControlOperationResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 动态设置公共群；状态事件成功后才替换内存值。 */
public final class ControlSetPublicGroupTool extends ControlBootstrapToolSupport.BaseTool {

    public static final String NAME = "tf_control_set_public_group";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "设置公开群；事件写入成功后才生效，同群重复调用幂等。",
            "properties", Map.of(
                    "publicGroupId", Map.of("type", "string",
                            "description", "公开群标识"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键返回首次回执"),
                    "reason", Map.of("type", "string",
                            "description", "审计原因（可选，不进入领域正文）")),
            "required", List.of("publicGroupId", "idempotencyKey"),
            "additionalProperties", false);

    private final ControlBootstrapService service;

    public ControlSetPublicGroupTool(ControlBootstrapService service) {
        super(NAME, "设置公开群；append 成功后才替换内存状态。", SCHEMA);
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected ControlOperationResult runChecked(Map<String, Object> arguments) {
        String groupId = ControlBootstrapToolSupport.requiredText(
                arguments, "publicGroupId", ControlBootstrapToolSupport.MAX_GROUP_CHARS);
        String key = ControlBootstrapToolSupport.requiredText(
                arguments, "idempotencyKey", ControlBootstrapToolSupport.MAX_KEY_CHARS);
        ControlBootstrapToolSupport.optionalText(
                arguments, "reason", ControlBootstrapToolSupport.MAX_REASON_CHARS);
        return service.setPublicGroup(groupId, key);
    }
}
