package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.MessagingAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code qq_adapter_status}：查询单个适配器的连接状态与脱敏错误摘要。
 *
 * <p>该工具为 GUEST 级只读工具；调用前要求 {@code qq:<adapterId>} 的读权限。错误码与错误说明均经过
 * {@link Redaction} 清洗，完整 URL、token 和协议对象不会进入结果。</p>
 */
final class QqAdapterStatusTool extends AbstractQqTool {

    static final String NAME = "qq_adapter_status";

    private final AdapterRegistry adapters;

    QqAdapterStatusTool(AdapterRegistry adapters, Set<String> roles) {
        super(ToolSpec.level(AccessToken.GUEST, false, false), roles);
        this.adapters = adapters;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询指定 QQ 适配器的连接状态与脱敏后的最近错误摘要。只读操作。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "description", "按 adapterId 查询连接状态。",
                "properties", Map.of(
                        "adapterId", Map.of(
                                "type", "string",
                                "description", "qq_list_adapters 返回的适配器 id")),
                "required", List.of("adapterId"),
                "additionalProperties", false);
    }

    @Override
    protected ToolResult executeChecked(ToolContext context) {
        String adapterId = ToolArguments.requiredText(context.arguments(), "adapterId");
        ToolArguments.requireSafePathIdentifier(adapterId, "adapterId");
        Optional<MessagingAdapter> found = adapters.find(adapterId);
        if (found.isEmpty()) {
            return ToolResult.error("ADAPTER_NOT_FOUND", "未找到适配器: " + Redaction.sanitize(adapterId));
        }
        MessagingAdapter adapter = found.get();
        context.resources().require(Operation.READ, QqResourcePaths.adapter(adapterId));

        AdapterStatus status = adapter.status();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("adapterId", Redaction.sanitize(adapter.adapterId()));
        root.put("state", status == null ? "UNKNOWN" : status.state().name());
        root.put("changedAt", status == null ? null : status.changedAt().toString());
        root.put("lastErrorCode", status == null ? null : Redaction.sanitizeCode(status.lastErrorCode()));
        root.put("lastErrorMessage", status == null ? null : Redaction.sanitize(status.lastErrorMessage()));
        return ToolResult.ok(Json.write(root));
    }
}
