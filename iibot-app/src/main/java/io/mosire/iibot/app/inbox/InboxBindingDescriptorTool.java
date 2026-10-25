package io.mosire.iibot.app.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.inbox.client.InboxModels;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 只读返回 DSH wake binding descriptor 与其本地 JSON 路径。
 *
 * <p>descriptor 由 {@link InboxModels.BindingDescriptor} 固定五字段组成，绝不包含
 * program MCP token、wake token 原文、claim token 或业务正文。</p>
 */
public final class InboxBindingDescriptorTool implements AgentTool {

    public static final String NAME = "tf_inbox_binding_descriptor";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false);

    private final InboxModels.BindingDescriptor descriptor;
    private final Path descriptorPath;

    public InboxBindingDescriptorTool(InboxModels.BindingDescriptor descriptor,
                                      Path descriptorPath) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.descriptorPath = Objects.requireNonNull(descriptorPath, "descriptorPath")
                .toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Return the InboxMosire BindingDescriptor and its local JSON file path. "
                + "The descriptor contains no raw token; pass it to inbox_adapter_bind. "
                + "Rebind after app restart because embedded ports may change.";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("descriptor", descriptor);
            output.put("descriptorFile", descriptorPath.toString());
            return ToolResult.ok(JSON.writeValueAsString(output));
        } catch (Exception failure) {
            return ToolResult.error("INTERNAL", "无法序列化 inbox binding descriptor");
        }
    }
}
