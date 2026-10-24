package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocs;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只读列出 GM 私有文档；返回逻辑路径/大小/更新时间。 */
public final class GmPrivateDocListTool implements AgentTool {

    public static final String NAME = "tf_gm_doc_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "列出 GM 私有文档；只返回逻辑路径、大小与更新时间。",
            "properties", Map.of(
                    "prefix", Map.of("type", "string",
                            "description", "可选：world/plot/settings/notes 或空")),
            "required", List.of(),
            "additionalProperties", false);

    private final GmPrivateDocs docs;

    public GmPrivateDocListTool(GmPrivateDocs docs) {
        this.docs = Objects.requireNonNull(docs, "docs");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读列出 GM 私有文档的逻辑路径、大小与更新时间；不返回物理路径。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return GmPrivateDocsToolSupport.readSpec();
    }

    @Override
    public java.util.Set<String> roles() {
        return GmPrivateDocsToolSupport.ROLES;
    }

    @Override
    public io.mosire.agentlib.permission.ResourceManifest resources() {
        return GmPrivateDocsToolSupport.RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmPrivateDocsToolSupport.requireRead(context);
            String prefix = GmPrivateDocsToolSupport.optionalText(context.arguments(),
                    "prefix", GmPrivateDocsToolSupport.MAX_PATH_CHARS);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (GmPrivateDocs.Entry entry : docs.list(prefix)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", entry.path());
                item.put("size", entry.size());
                item.put("updatedAt", entry.updatedAt().toString());
                entries.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("prefix", prefix.isEmpty() ? "<root>" : prefix);
            data.put("count", entries.size());
            data.put("entries", entries);
            return GmPrivateDocsToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPrivateDocsException failure) {
            return GmPrivateDocsToolSupport.docsFailure(NAME, failure);
        } catch (GmPrivateDocsToolSupport.InvalidArgumentsException invalid) {
            return GmPrivateDocsToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return GmPrivateDocsToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    "GM 私有文档参数非法");
        } catch (RuntimeException failure) {
            return GmPrivateDocsToolSupport.failure(NAME, "GM_DOCS_UNAVAILABLE",
                    "GM 私有文档区暂不可用（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return Map.of();
    }
}
