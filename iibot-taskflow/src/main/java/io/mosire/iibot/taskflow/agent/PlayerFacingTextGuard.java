package io.mosire.iibot.taskflow.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玩家可见文本的最后一道边界。
 *
 * <p>这不是文风生成器，也不替代秘书 prompt 或编辑审稿。它只保证无论模型/工具如何输出，
 * QQ 玩家侧都不会看到明显的后台字段、文件路径、Markdown 结构或系统状态枚举。</p>
 *
 * <p>唯一允许保留的机器交互是行动确认命令，例如 {@code /确认 K7Q2M9}</p>
 */
public final class PlayerFacingTextGuard {

    public static final int MAX_CHAT_CHARS = 4_000;
    public static final int MAX_POST_CHARS = 10_000;

    private static final String FALLBACK =
            "我这边刚才没有整理出一句可读的回复。你再说一次，我直接回答。";

    private static final Pattern CODE_FENCE_LINE = Pattern.compile("^\\s*```.*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s*");
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?:[A-Za-z]:\\\\[^\\s，。；;：:]+|~?/(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]+)");
    private static final Pattern CONFIRM_COMMAND =
            Pattern.compile("(?i)/确认\\s+[A-Z0-9][A-Z0-9._:-]{3,}");
    private static final Pattern BACKSTAGE_WORD = Pattern.compile(
            "(?i)(draftId|decisionId|ticketId|postId|contentHash|turnId|expectedRevision|"
                    + "idempotencyKey|PENDING_APPROVAL|APPROVED|PUBLISHED|COMMITTED|CLAIMED|"
                    + "RESOLVED|NO_OP|NEEDS_CLARIFICATION|MCP|schema|payload|workspace|"
                    + "playerId|LLM|tool call|stacktrace|Exception|event-spy|"
                    + "\\bplayer/|\\bgm/|\\bnotes/)");
    private static final Pattern SYSTEM_TAG = Pattern.compile("(?i)^\\s*【系统[^】]*】.*$");

    private PlayerFacingTextGuard() {
    }

    /** 私聊秘书/系统回复的净化。 */
    public static String normalizeForChat(String text) {
        return normalize(text, MAX_CHAT_CHARS);
    }

    /** 公共/私密推文正文的净化。 */
    public static String normalizeForPost(String text) {
        return normalize(text, MAX_POST_CHARS);
    }

    /**
     * 返回净化后仍检测到的后台泄漏类型。正常情况下应为空；调用方可在发布前 fail-closed。
     */
    public static List<String> remainingBackstageLeaks(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = normalize(text, Integer.MAX_VALUE);
        List<String> leaks = new ArrayList<>();
        if (BACKSTAGE_WORD.matcher(normalized).find()) {
            leaks.add("BACKSTAGE_FIELD");
        }
        if (UUID.matcher(normalized).find()) {
            leaks.add("INTERNAL_ID");
        }
        if (ABSOLUTE_PATH.matcher(normalized).find()) {
            leaks.add("FILE_PATH");
        }
        if (hasMarkdownStructure(normalized)) {
            leaks.add("MARKDOWN");
        }
        return List.copyOf(leaks);
    }

    /** 是否包含明显的 Markdown 结构标记。 */
    public static boolean hasMarkdownStructure(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            if (CODE_FENCE_LINE.matcher(line).matches()
                    || HEADING.matcher(line).find()
                    || TABLE_SEPARATOR.matcher(line).matches()) {
                return true;
            }
        }
        return text.contains("**") || text.contains("__") || text.contains("`");
    }

    private static String normalize(String text, int maxChars) {
        Objects.requireNonNull(text, "text");
        String source = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (source.isEmpty()) {
            return FALLBACK;
        }
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length());
        for (String rawLine : lines) {
            String line = rawLine;
            if (CODE_FENCE_LINE.matcher(line).matches()) {
                continue;
            }
            if (TABLE_SEPARATOR.matcher(line).matches()) {
                continue;
            }
            line = HEADING.matcher(line).replaceFirst("");
            if (line.stripLeading().startsWith("|") && line.stripTrailing().endsWith("|")) {
                String inner = line.strip();
                inner = inner.substring(1, inner.length() - 1).trim();
                line = "· " + inner.replace("|", " / ");
            }
            line = line.replace("**", "").replace("__", "").replace("`", "");
            line = stripListMarker(line);
            if (isBackstageLine(line) && !containsConfirmationCommand(line)) {
                continue;
            }
            line = UUID.matcher(line).replaceAll("内部编号");
            line = ABSOLUTE_PATH.matcher(line).replaceAll("相关工作区");
            line = line.replaceAll("[ \\t]{2,}", " ").stripTrailing();
            if (!line.isBlank()) {
                out.append(line).append('\n');
            }
        }
        String cleaned = out.toString().replaceAll("\\n{3,}", "\n\n").trim();
        if (cleaned.isEmpty()) {
            return FALLBACK;
        }
        return truncate(cleaned, maxChars);
    }

    private static String stripListMarker(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("- ") || stripped.startsWith("* ")
                || stripped.startsWith("+ ")) {
            return "· " + stripped.substring(2).trim();
        }
        return line;
    }

    private static boolean isBackstageLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (SYSTEM_TAG.matcher(line).matches()) {
            return true;
        }
        return BACKSTAGE_WORD.matcher(line).find();
    }

    private static boolean containsConfirmationCommand(String line) {
        Matcher matcher = CONFIRM_COMMAND.matcher(line);
        if (!matcher.find()) {
            return false;
        }
        String remaining = matcher.replaceAll("").trim();
        return remaining.isBlank();
    }

    private static String truncate(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int cut = maxChars;
        int newline = text.lastIndexOf('\n', cut);
        if (newline > cut / 2) {
            cut = newline;
        }
        return text.substring(0, cut).stripTrailing() + "\n……";
    }
}
