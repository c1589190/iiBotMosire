package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.note.UserNote;
import io.mosire.iibot.bot.note.UserNoteDraft;
import io.mosire.iibot.bot.note.UserNoteRevisionConflictException;
import io.mosire.iibot.bot.note.UserNoteStore;
import io.mosire.iibot.connect.ops.OpsLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 新建或更新 QQ 用户备注/标签/priority。 */
public final class BotUserNoteUpsertTool implements AgentTool {

    public static final String NAME = "bot_user_note_upsert";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；省略时使用宿主默认账号"),
                    "qqId", Map.of("type", "string", "minLength", 1),
                    "displayName", Map.of("type", "string"),
                    "tags", Map.of("type", "array",
                            "items", Map.of("type", "string")),
                    "note", Map.of("type", "string"),
                    "priority", Map.of("type", "integer", "minimum", -1000, "maximum", 1000),
                    "expectedRevision", Map.of("type", "integer", "minimum", 0)),
            "required", List.of("qqId"),
            "additionalProperties", false);

    private final UserNoteStore notes;
    private final String defaultAccountId;

    public BotUserNoteUpsertTool(UserNoteStore notes, String defaultAccountId) {
        this.notes = Objects.requireNonNull(notes, "notes");
        this.defaultAccountId = requireText(defaultAccountId, "defaultAccountId");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "新建或更新 QQ 用户备注；省略字段保留当前值，tags 传入时整体替换。";
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
            UserNote current = notes.find(accountId, qqId).orElse(null);
            String displayName = context.arguments().containsKey("displayName")
                    ? BotToolSupport.optionalText(context.arguments(), "displayName", 200)
                    : current == null ? "" : current.displayName();
            Set<String> tags = context.arguments().containsKey("tags")
                    ? Set.copyOf(BotToolSupport.optionalStringList(context.arguments(), "tags"))
                    : current == null ? Set.of() : current.tags();
            String note = context.arguments().containsKey("note")
                    ? BotToolSupport.optionalText(context.arguments(), "note", 4000)
                    : current == null ? "" : current.note();
            int priority = context.arguments().containsKey("priority")
                    ? BotToolSupport.optionalInt(context.arguments(), "priority", 0)
                    : current == null ? 0 : current.priority();
            Long expectedRevision = BotToolSupport.optionalLongObject(
                    context.arguments(), "expectedRevision");
            if (expectedRevision == null && current != null) {
                expectedRevision = current.revision();
            }
            UserNote saved = notes.upsert(new UserNoteDraft(accountId, qqId, displayName,
                    tags, note, priority), expectedRevision);
            OpsLog.info("event=user_note_upserted accountId={} qqId={} revision={} "
                            + "priority={} tagCount={}", accountId, qqId, saved.revision(),
                    saved.priority(), saved.tags().size());
            return BotToolSupport.ok(NAME, BotToolSupport.noteMap(saved));
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (UserNoteRevisionConflictException conflict) {
            return BotToolSupport.failure("REVISION_CONFLICT", conflict.getMessage());
        } catch (IllegalArgumentException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("NOTE_WRITE_UNAVAILABLE",
                    "用户备注写入暂不可用（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("accountId", "qqId", "tags", "priority", "expectedRevision")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        // note/displayName 是可自由撰写的正文，不进 ledger。
        return Map.copyOf(safe);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
