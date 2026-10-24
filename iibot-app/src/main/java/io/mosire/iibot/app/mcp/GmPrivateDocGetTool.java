package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocs;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocsException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只读读取 GM 私有文档正文；GM 工具面允许返回正文。 */
public final class GmPrivateDocGetTool implements AgentTool {

    public static final String NAME = "tf_gm_doc_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按逻辑路径读取 GM 私有文档正文、哈希与字节大小。",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description", "必填相对路径，如 world/summary.md")),
            "required", List.of("path"),
            "additionalProperties", false);

    private final GmPrivateDocs docs;

    public GmPrivateDocGetTool(GmPrivateDocs docs) {
        this.docs = Objects.requireNonNull(docs, "docs");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读读取 GM 私有文档正文、内容 hash 与大小；不返回物理路径。";
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
            String path = GmPrivateDocsToolSupport.requiredText(context.arguments(),
                    "path", GmPrivateDocsToolSupport.MAX_PATH_CHARS);
            GmPrivateDocs.ReadResult result = docs.read(path).orElse(null);
            if (result == null) {
                return GmPrivateDocsToolSupport.failure(NAME, "GM_DOC_NOT_FOUND",
                        "GM 私有文档不存在");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", result.path());
            data.put("content", result.content());
            data.put("contentHash", result.contentHash());
            data.put("size", result.size());
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
