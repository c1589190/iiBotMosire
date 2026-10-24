package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocs;
import io.mosire.iibot.taskflow.workspace.GmPrivateDocsException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** GM 私有文档写入工具；默认拒绝覆盖，成功回执不含正文/物理路径。 */
public final class GmPrivateDocWriteTool implements AgentTool {

    public static final String NAME = "tf_gm_doc_write";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "写入 GM 私有文档；默认 replace=false 拒绝覆盖。",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description", "相对路径，如 world/summary.md"),
                    "content", Map.of("type", "string", "description", "UTF-8 文档正文"),
                    "replace", Map.of("type", "boolean",
                            "description", "为 true 才允许覆盖；缺省 false"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键返回首次成功回执"),
                    "reason", Map.of("type", "string", "description", "审计原因（可选）")),
            "required", List.of("path", "content", "idempotencyKey"),
            "additionalProperties", false);

    private final GmPrivateDocs docs;
    private final Map<String, String> idempotentResults = new ConcurrentHashMap<>();

    public GmPrivateDocWriteTool(GmPrivateDocs docs) {
        this.docs = Objects.requireNonNull(docs, "docs");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "写入 GM 私有文档；默认拒绝覆盖，返回逻辑路径/hash/大小，不回显正文或物理路径。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return GmPrivateDocsToolSupport.writeSpec();
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
            GmPrivateDocsToolSupport.requireWrite(context);
            Map<String, Object> args = context.arguments() == null
                    ? Map.of() : context.arguments();
            String path = GmPrivateDocsToolSupport.requiredText(
                    args, "path", GmPrivateDocsToolSupport.MAX_PATH_CHARS);
            String content = GmPrivateDocsToolSupport.requiredContent(
                    args, "content", GmPrivateDocs.MAX_CONTENT_CHARS);
            String idempotencyKey = GmPrivateDocsToolSupport.requiredText(
                    args, "idempotencyKey",
                    GmPrivateDocsToolSupport.MAX_IDEMPOTENCY_KEY_CHARS);
            boolean replace = GmPrivateDocsToolSupport.optionalBoolean(args, "replace", false);
            GmPrivateDocsToolSupport.optionalText(
                    args, "reason", GmPrivateDocsToolSupport.MAX_REASON_CHARS);

            String cached = idempotentResults.get(idempotencyKey);
            if (cached != null) {
                return ToolResult.ok(cached);
            }
            GmPrivateDocs.WriteResult result = docs.write(path, content, replace);
            String wire = GmPrivateDocsToolSupport.json(
                    successEnvelope(GmPrivateDocsToolSupport.writeData(result)));
            idempotentResults.putIfAbsent(idempotencyKey, wire);
            return ToolResult.ok(wire);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmPrivateDocsException failure) {
            return GmPrivateDocsToolSupport.docsFailure(NAME, failure);
        } catch (GmPrivateDocsToolSupport.InvalidArgumentsException invalid) {
            return GmPrivateDocsToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return GmPrivateDocsToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    "GM 私有文档写入参数非法");
        } catch (RuntimeException failure) {
            return GmPrivateDocsToolSupport.failure(NAME, "GM_DOCS_UNAVAILABLE",
                    "GM 私有文档区暂不可用（内部错误已脱敏）");
        }
    }

    private static Map<String, Object> successEnvelope(Map<String, Object> data) {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("ok", true);
        output.put("code", "OK");
        output.put("tool", NAME);
        output.put("data", data);
        return output;
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return GmPrivateDocsToolSupport.safeWriteLedgerArgs(context);
    }
}
