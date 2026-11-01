package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 为同一图片 assetId 读取/补充备注，避免重复图片反复解释。 */
public final class BotMediaNoteTool implements AgentTool {

    public static final String NAME = "qq_media_note";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "assetId", Map.of("type", "string", "minLength", 1),
                    "note", Map.of("type", "string", "maxLength", BotMediaNoteStore.MAX_NOTE_CHARS,
                            "description", "省略则读取；空字符串表示清除")),
            "required", List.of("assetId"),
            "additionalProperties", false);

    private final BotMediaStore mediaStore;
    private final BotMediaNoteStore notes;

    public BotMediaNoteTool(BotMediaStore mediaStore, BotMediaNoteStore notes) {
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.notes = Objects.requireNonNull(notes, "notes");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取或补充一张已缓存图片的备注；同一 assetId 复用，避免重复解释。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            String assetId = required(args, "assetId");
            if (mediaStore.resolve(assetId).isEmpty()) {
                return ToolResult.error("MEDIA_NOT_FOUND", "assetId 不存在或已过期");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("assetId", assetId);
            if (args != null && args.containsKey("note")) {
                Object raw = args.get("note");
                String note = raw == null ? "" : raw.toString();
                notes.put(assetId, note);
                data.put("note", note.trim());
                data.put("cleared", note.trim().isEmpty());
            } else {
                Optional<String> note = notes.get(assetId);
                data.put("note", note.orElse(""));
                data.put("cleared", false);
            }
            return ToolResult.ok(JSON.writeValueAsString(data));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("MEDIA_NOTE_FAILED", "图片备注操作失败（内部原因已脱敏）");
        }
    }

    private static String required(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }
}
