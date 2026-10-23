package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AdapterStatus;
import io.mosire.iibot.connect.core.MessagingAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code qq_list_adapters}：列出已装配适配器及其连接状态摘要。
 *
 * <p>该工具为 GUEST 级只读工具；调用前通过宿主注入的资源判定者要求读权限。宿主可授予
 * {@code qq:adapters} 列出全部，也可只授予 {@code qq:<adapterId>} 让工具返回可见适配器子集。
 * 返回内容只包含 adapterId、状态与状态变更时间，不返回错误详情、协议对象或连接参数。</p>
 */
final class QqListAdaptersTool extends AbstractQqTool {

    static final String NAME = "qq_list_adapters";

    private final AdapterRegistry adapters;

    QqListAdaptersTool(AdapterRegistry adapters, Set<String> roles) {
        super(ToolSpec.level(AccessToken.GUEST, false, false), roles);
        this.adapters = adapters;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出当前已装配的 QQ 适配器 id 与连接状态摘要。只读操作，不返回凭据或协议细节。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "description", "该工具不需要参数。",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false);
    }

    @Override
    protected ToolResult executeChecked(ToolContext context) {
        // 宿主可显式授予 qq:adapters 以列出全部；也可只授予某些 qq:<adapterId>。后者只返回调用者够得着的
        // 适配器，避免“列全部”成为绕过单适配器可达面的侧信道。判定仍只发生在注入的 ResourceAuthorizer 上。
        ResourceId adaptersResource = QqResourcePaths.adapters();
        boolean listAll = context.resources().allows(Operation.READ, adaptersResource);
        List<MessagingAdapter> visible = new ArrayList<>();
        for (MessagingAdapter adapter : adapters.list()) {
            ResourceId adapterResource = QqResourcePaths.adapter(adapter.adapterId());
            if (listAll || context.resources().allows(Operation.READ, adapterResource)) {
                visible.add(adapter);
            }
        }
        if (visible.isEmpty()) {
            // 没有单个适配器可达时仍调用 require，保持 fail-closed：直接执行/空 scope 不得静默返回空列表。
            context.resources().require(Operation.READ, adaptersResource);
        } else if (listAll) {
            context.resources().require(Operation.READ, adaptersResource);
        } else {
            for (MessagingAdapter adapter : visible) {
                context.resources().require(
                        Operation.READ, QqResourcePaths.adapter(adapter.adapterId()));
            }
        }

        List<Object> summaries = new ArrayList<>();
        for (MessagingAdapter adapter : visible) {
            AdapterStatus status = adapter.status();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("adapterId", Redaction.sanitize(adapter.adapterId()));
            item.put("state", status == null ? "UNKNOWN" : status.state().name());
            item.put("changedAt", status == null ? null : status.changedAt().toString());
            summaries.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("count", summaries.size());
        root.put("adapters", summaries);
        return ToolResult.ok(Json.write(root));
    }
}
