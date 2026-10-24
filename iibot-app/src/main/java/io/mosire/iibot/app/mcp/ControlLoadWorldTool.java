package io.mosire.iibot.app.mcp;

import io.mosire.iibot.app.control.ControlBootstrapService;
import io.mosire.iibot.app.control.ControlOperationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 装载一批新世界事实；worldVersion 由服务固定为当前值 + 1。 */
public final class ControlLoadWorldTool extends ControlBootstrapToolSupport.BaseTool {

    public static final String NAME = "tf_control_load_world";

    private static final Map<String, Object> FACT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "factId", Map.of("type", "string", "description", "稳定事实 ID"),
                    "content", Map.of("type", "string", "description", "事实正文"),
                    "visibility", Map.of("type", "string",
                            "description", "PUBLIC、DIRECTOR_ONLY 或 PLAYERS:p1,p2")),
            "required", List.of("factId", "content", "visibility"),
            "additionalProperties", false);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "装载世界事实；worldVersion 固定为当前值 + 1，成功返回摘要。",
            "properties", Map.of(
                    "facts", Map.of("type", "array", "items", FACT_SCHEMA,
                            "description", "待装载事实数组"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键返回首次回执"),
                    "reason", Map.of("type", "string",
                            "description", "审计原因（可选）")),
            "required", List.of("facts", "idempotencyKey"),
            "additionalProperties", false);

    private final ControlBootstrapService service;

    public ControlLoadWorldTool(ControlBootstrapService service) {
        super(NAME, "加载新版本世界事实；不返回事实正文或物理路径。", SCHEMA);
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected ControlOperationResult runChecked(Map<String, Object> arguments) {
        List<Map<String, Object>> rawFacts = ControlBootstrapToolSupport.requiredObjectArray(
                arguments, "facts");
        String key = ControlBootstrapToolSupport.requiredText(
                arguments, "idempotencyKey", ControlBootstrapToolSupport.MAX_KEY_CHARS);
        ControlBootstrapToolSupport.optionalText(
                arguments, "reason", ControlBootstrapToolSupport.MAX_REASON_CHARS);
        List<ControlBootstrapService.WorldFactInput> facts = rawFacts.stream()
                .map(item -> new ControlBootstrapService.WorldFactInput(
                        ControlBootstrapToolSupport.requiredText(item, "factId",
                                ControlBootstrapToolSupport.MAX_FACT_ID_CHARS),
                        ControlBootstrapToolSupport.requiredContent(item, "content",
                                ControlBootstrapToolSupport.MAX_CONTENT_CHARS),
                        ControlBootstrapToolSupport.requiredText(item, "visibility",
                                ControlBootstrapToolSupport.MAX_VISIBILITY_CHARS)))
                .toList();
        return service.loadWorld(facts, key);
    }
}
