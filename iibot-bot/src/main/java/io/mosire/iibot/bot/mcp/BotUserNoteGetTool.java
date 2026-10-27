package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.note.UserNote;
import io.mosire.iibot.bot.note.UserNoteStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 读取一个 QQ 用户的备注/标签。 */
public final class BotUserNoteGetTool implements AgentTool {

    public static final String NAME = "bot_user_note_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；省略时使用宿主默认账号"),
                    "qqId", Map.of("type", "string", "minLength", 1)),
            "required", List.of("qqId"),
            "additionalProperties", false);

    private final UserNoteStore notes;
    private final String defaultAccountId;

    public BotUserNoteGetTool(UserNoteStore notes, String defaultAccountId) {
        this.notes = Objects.requireNonNull(notes, "notes");
        this.defaultAccountId = requireText(defaultAccountId, "defaultAccountId");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取一个 QQ 用户的备注、标签与 priority；不存在时返回 NOT_FOUND。";
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
            String qqId = BotToolSupport.requiredText(context.arguments(), "qqId", 64);
            UserNote note = notes.find(accountId, qqId)
                    .orElseThrow(() -> new BotToolSupport.InvalidArgumentsException(
                            "用户备注不存在"));
            return BotToolSupport.ok(NAME, BotToolSupport.noteMap(note));
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("NOTE_NOT_FOUND", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("NOTE_READ_UNAVAILABLE",
                    "用户备注读取暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "qqId")) {
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
