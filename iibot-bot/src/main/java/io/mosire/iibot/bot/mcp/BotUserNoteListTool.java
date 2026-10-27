package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.note.UserNote;
import io.mosire.iibot.bot.note.UserNoteStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按 priority 列出用户备注，可选 tag 过滤。 */
public final class BotUserNoteListTool implements AgentTool {

    public static final String NAME = "bot_user_note_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；省略时使用宿主默认账号"),
                    "tag", Map.of("type", "string", "description", "可选标签过滤"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 1000,
                            "description", "最多返回条数，默认 50")),
            "additionalProperties", false);

    private final UserNoteStore notes;
    private final String defaultAccountId;

    public BotUserNoteListTool(UserNoteStore notes, String defaultAccountId) {
        this.notes = Objects.requireNonNull(notes, "notes");
        this.defaultAccountId = requireText(defaultAccountId, "defaultAccountId");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "按 priority 降序列出用户备注；可按标签过滤。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = BotToolSupport.optionalText(
                    context.arguments(), "accountId", 128);
            if (accountId.isEmpty()) {
                accountId = defaultAccountId;
            }
            String tag = BotToolSupport.optionalText(context.arguments(), "tag", 64);
            int limit = BotToolSupport.optionalInt(context.arguments(), "limit", 50);
            if (limit < 1 || limit > 1000) {
                throw new BotToolSupport.InvalidArgumentsException("limit 必须在 1..1000");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (UserNote note : notes.list(1000)) {
                if (!accountId.equals(note.accountId())) {
                    continue;
                }
                if (!tag.isEmpty() && !note.tags().contains(tag)) {
                    continue;
                }
                result.add(BotToolSupport.noteMap(note));
                if (result.size() == limit) {
                    break;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("tag", tag);
            data.put("count", result.size());
            data.put("notes", result);
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("NOTE_LIST_UNAVAILABLE",
                    "用户备注列表暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "tag", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
