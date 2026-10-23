package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.skill.SharedSkillStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM 写入共享 Skill 文档；只允许 public/ 与 skills/，默认拒绝覆盖，replace=true 才覆盖。
 *
 * <p>幂等键缓存首次成功回执：重复键直接返回同一结果，不再次写入。返回数据只含逻辑路径、
 * 内容 hash 与 created/replaced 状态，不包含正文或物理路径。</p>
 */
public final class GmSharedSkillWriteTool implements AgentTool {

    public static final String NAME = "tf_shared_skill_write";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "写入共享 Skill 文档（仅 public/ 或 skills/）；默认拒绝覆盖。",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description", "相对 Docs 路径，如 skills/response-guide.md"),
                    "content", Map.of("type", "string", "description", "文档正文"),
                    "replace", Map.of("type", "boolean",
                            "description", "为 true 才允许覆盖已有文档；缺省 false"),
                    "idempotencyKey", Map.of("type", "string",
                            "description", "幂等键；同一键重复调用返回首次成功回执"),
                    "reason", Map.of("type", "string", "description", "写入原因（审计摘要）")),
            "required", List.of("path", "content", "idempotencyKey", "reason"),
            "additionalProperties", false);

    private final SharedSkillStore sharedSkillStore;
    private final Map<String, String> idempotentResults = new ConcurrentHashMap<>();

    public GmSharedSkillWriteTool(SharedSkillStore sharedSkillStore) {
        this.sharedSkillStore = Objects.requireNonNull(sharedSkillStore, "sharedSkillStore");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "GM 写入共享 Skill 文档；默认拒绝覆盖，replace=true 才替换；不返回正文。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public java.util.Set<String> roles() {
        return GmSharedSkillToolSupport.ROLES;
    }

    @Override
    public io.mosire.agentlib.permission.ResourceManifest resources() {
        return GmSharedSkillToolSupport.RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmSharedSkillToolSupport.requireWrite(context);
            Map<String, Object> args = context.arguments();
            String path = GmSharedSkillToolSupport.requiredText(
                    args, "path", GmSharedSkillToolSupport.MAX_PATH_CHARS);
            String content = GmSharedSkillToolSupport.requiredText(
                    args, "content", SharedSkillStore.MAX_CONTENT_CHARS);
            String idempotencyKey = GmSharedSkillToolSupport.requiredText(
                    args, "idempotencyKey",
                    GmSharedSkillToolSupport.MAX_IDEMPOTENCY_KEY_CHARS);
            String reason = GmSharedSkillToolSupport.requiredText(
                    args, "reason", GmSharedSkillToolSupport.MAX_REASON_CHARS);
            boolean replace = GmSharedSkillToolSupport.optionalBoolean(args, "replace", false);

            String cached = idempotentResults.get(idempotencyKey);
            if (cached != null) {
                return ToolResult.ok(cached);
            }

            SharedSkillStore.WriteResult result = sharedSkillStore.writeGm(path, content, replace);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", result.path());
            data.put("status", result.status());
            data.put("contentHash", result.contentHash());
            data.put("reason", reason);
            String wire = GmSharedSkillToolSupport.json(successEnvelope(data));
            idempotentResults.putIfAbsent(idempotencyKey, wire);
            return ToolResult.ok(wire);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSharedSkillToolSupport.InvalidArgumentsException invalid) {
            return GmSharedSkillToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return GmSharedSkillToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage() == null ? "共享 Skill 写入参数非法" : invalid.getMessage());
        } catch (RuntimeException failure) {
            return GmSharedSkillToolSupport.failure(NAME, "SHARED_SKILLS_UNAVAILABLE",
                    "共享 Skill 区暂不可用（内部错误已脱敏）");
        }
    }

    private static Map<String, Object> successEnvelope(Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("code", "OK");
        output.put("tool", NAME);
        output.put("data", data);
        return output;
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Map<String, Object> safe = new LinkedHashMap<>();
        if (args != null) {
            for (String key : List.of("path", "replace", "idempotencyKey", "reason")) {
                if (args.containsKey(key)) {
                    safe.put(key, args.get(key));
                }
            }
        }
        return Map.copyOf(safe);
    }
}
