package io.mosire.iibot.app.mcp;

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

/** GM 只读读取共享 Skill 文档；返回逻辑路径与正文，不返回物理路径。 */
public final class GmSharedSkillGetTool implements AgentTool {

    public static final String NAME = "tf_shared_skill_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "按相对路径读取共享 Skill 文档正文；只读。",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description", "必填相对 Docs 路径，如 public/notice.md")),
            "required", List.of("path"),
            "additionalProperties", false);

    private final SharedSkillStore sharedSkillStore;

    public GmSharedSkillGetTool(SharedSkillStore sharedSkillStore) {
        this.sharedSkillStore = Objects.requireNonNull(sharedSkillStore, "sharedSkillStore");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读读取共享 Skill 文档正文；不存在返回稳定错误，不返回物理路径。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.DEFAULT;
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
            GmSharedSkillToolSupport.requireRead(context);
            String path = GmSharedSkillToolSupport.requiredText(
                    context.arguments(), "path", GmSharedSkillToolSupport.MAX_PATH_CHARS);
            String content = sharedSkillStore.read(path).orElse(null);
            if (content == null) {
                return GmSharedSkillToolSupport.failure(NAME, "SHARED_SKILL_NOT_FOUND",
                        "共享 Skill 区中不存在该文件");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path);
            data.put("truncated", false);
            data.put("content", content);
            return GmSharedSkillToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSharedSkillToolSupport.InvalidArgumentsException invalid) {
            return GmSharedSkillToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return GmSharedSkillToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    "共享 Skill 路径非法或不可读");
        } catch (RuntimeException failure) {
            return GmSharedSkillToolSupport.failure(NAME, "SHARED_SKILLS_UNAVAILABLE",
                    "共享 Skill 区暂不可用（内部错误已脱敏）");
        }
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
        return Map.of();
    }
}
