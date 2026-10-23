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

/** GM 只读列出共享 Skill 区；只返回逻辑相对路径，不返回物理路径。 */
public final class GmSharedSkillListTool implements AgentTool {

    public static final String NAME = "tf_shared_skill_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "列出共享 Skill 区 public/、skills/ 或子目录的直接子项；只读。",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description", "可选相对路径：public、skills 或其子目录")),
            "required", List.of(),
            "additionalProperties", false);

    private final SharedSkillStore sharedSkillStore;

    public GmSharedSkillListTool(SharedSkillStore sharedSkillStore) {
        this.sharedSkillStore = Objects.requireNonNull(sharedSkillStore, "sharedSkillStore");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读列出共享 Skill 区的直接子项；只返回逻辑相对路径。";
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
            String path = GmSharedSkillToolSupport.optionalText(
                    context.arguments(), "path", GmSharedSkillToolSupport.MAX_PATH_CHARS);
            List<String> entries = sharedSkillStore.list(path);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path.isEmpty() ? "<root>" : path);
            data.put("count", entries.size());
            data.put("entries", entries);
            return GmSharedSkillToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (GmSharedSkillToolSupport.InvalidArgumentsException invalid) {
            return GmSharedSkillToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return GmSharedSkillToolSupport.failure(NAME, "INVALID_ARGUMENTS",
                    "共享 Skill 路径非法或不可列出");
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
