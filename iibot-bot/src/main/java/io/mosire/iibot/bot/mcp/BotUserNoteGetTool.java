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

/** 读取一个 QQ 用户在某账号下的备注/标签。 */
public final class BotUserNoteGetTool implements AgentTool {

    public static final String NAME = "qq_user_note_get";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "qqId", Map.of("type", "string", "minLength", 1)),
            "required", List.of("qqId"),
            "additionalProperties", false);

    private final UserNoteStore notes;
    private final BotAccountScope scope;

    public BotUserNoteGetTool(UserNoteStore notes, BotAccountScope scope) {
        this.notes = Objects.requireNonNull(notes, "notes");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /** 单账号便捷构造：只有该账号可用。 */
    public BotUserNoteGetTool(UserNoteStore notes, String defaultAccountId) {
        this(notes, BotAccountScope.single(defaultAccountId));
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取一个 QQ 用户在某 Bot 账号下的备注、标签与 priority；不存在时返回 "
                + "NOTE_NOT_FOUND。多账号时必须指定 accountId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = scope.resolve(context.arguments());
            String qqId = BotToolSupport.requiredText(context.arguments(), "qqId", 64);
            UserNote note = notes.find(accountId, qqId)
                    .orElseThrow(() -> new BotToolSupport.InvalidArgumentsException(
                            "用户备注不存在"));
            return BotToolSupport.ok(NAME, BotToolSupport.noteMap(note));
        } catch (BotAccountToolSupport.AccountToolException unavailable) {
            return BotToolSupport.failure(unavailable.code(), unavailable.getMessage());
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
}
