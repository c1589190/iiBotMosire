package io.mosire.iibot.app.mcp;

import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.control.ControlOperationResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 控制初始化幂等摘要工具。 */
public final class ControlInitializeTool extends ControlBootstrapToolSupport.BaseTool {

    public static final String NAME = "tf_control_initialize";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "幂等初始化控制面并返回公开群/身份/秘书摘要。",
            "properties", Map.of(),
            "required", List.of(),
            "additionalProperties", false);

    private final ControlBootstrapService service;

    public ControlInitializeTool(ControlBootstrapService service) {
        super(NAME, "幂等初始化控制面；不返回正文、QQ 凭据或物理路径。", SCHEMA);
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected ControlOperationResult runChecked(Map<String, Object> arguments) {
        return service.initialize();
    }
}
