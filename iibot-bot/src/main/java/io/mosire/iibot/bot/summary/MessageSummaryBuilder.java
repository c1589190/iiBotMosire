package io.mosire.iibot.bot.summary;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.note.UserNote;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 确定性消息摘要器：不调用 LLM、不联网，只从消息与用户备注生成 bounded 文本。
 *
 * <p>完整正文不截断地保存在消息板；本类的输出只用于 Inbox 列表和大框内容。</p>
 */
public final class MessageSummaryBuilder {

    public static final int DEFAULT_MAX_CHARS = 240;

    private final int maxChars;

    public MessageSummaryBuilder() {
        this(DEFAULT_MAX_CHARS);
    }

    public MessageSummaryBuilder(int maxChars) {
        if (maxChars < 16) {
            throw new IllegalArgumentException("maxChars 至少为 16");
        }
        this.maxChars = maxChars;
    }

    /**
     * 为一条消息生成摘要。
     *
     * @param message 已入库消息（localSeq > 0）
     * @param note    发送者备注；无备注传 null
     */
    public MessageSummary build(ChatMessage message, UserNote note) {
        Objects.requireNonNull(message, "message");
        if (message.localSeq() <= 0L) {
            throw new IllegalArgumentException("消息必须先入库并分配 localSeq");
        }
        Set<String> tags = note == null ? Set.of() : note.tags();
        String noteText = note == null ? "" : flatten(note.note());
        String sender = message.direction() == ChatDirection.OUT
                ? "我"
                : firstNonBlank(message.senderName(), message.senderId());
        String body = firstNonBlank(message.text(), message.nonTextSummary(), "[空消息]");
        String tagPrefix = tags.stream()
                .sorted()
                .map(tag -> "#" + tag)
                .collect(Collectors.joining(" "));
        StringBuilder raw = new StringBuilder();
        if (!tagPrefix.isEmpty()) {
            raw.append('[').append(tagPrefix).append("] ");
        }
        raw.append(sender);
        if (!noteText.isEmpty()) {
            raw.append("（备注：").append(noteText).append('）');
        }
        raw.append("：").append(flatten(body));
        String summary = truncate(raw.toString(), maxChars);
        return new MessageSummary(
                message.source().threadId(),
                message.localSeq(),
                message.messageId(),
                message.direction(),
                message.senderId(),
                message.senderName(),
                tags,
                noteText,
                note == null ? 0 : note.priority(),
                summary,
                message.sentAt());
    }

    private static String firstNonBlank(String first, String second) {
        return firstNonBlank(first, second, second);
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private static String flatten(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
