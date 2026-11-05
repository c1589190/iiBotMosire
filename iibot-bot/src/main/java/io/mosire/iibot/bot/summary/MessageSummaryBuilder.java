package io.mosire.iibot.bot.summary;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.note.UserNote;
import io.mosire.iibot.connect.core.MessageSegment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 确定性消息渲染器：不调用 LLM、不联网，把一条消息渲染成可读文本块。
 *
 * <p>输出是给人和模型直接阅读的文本，不是 JSON。格式（字段顺序固定）：</p>
 *
 * <pre>
 * [22:21] #9646 张三（备注：群主｜#管理）：我已经跟运营确认过了，周五能出。
 * [22:22] #9647 张三：@你 补充一句，预算单我放群里了。
 *   引用 ↩ 李四 [22:19]「这周五之前必须给答复」：
 *   预算单我放群里了。
 *   [文件 messageKey=qk_9f2a]
 * </pre>
 *
 * <p>时间只写 {@code HH:mm}（跨天时补 {@code MM-dd}）；行首的 {@code #seq} 是本地序号，
 * Agent 可以用它精确 sort / 引用；备注在同一次注入里每个发送者只出现一次，省掉重复开销。</p>
 *
 * <p>短消息单行；带引用或媒体时正文另起一行并缩进两格。完整正文始终保存在消息板，
 * 本类只负责渲染。</p>
 */
public final class MessageSummaryBuilder {

    public static final int DEFAULT_MAX_CHARS = 1000;
    public static final int REPLY_SNIPPET_CHARS = 40;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter REPLY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final int maxChars;
    private final ZoneId zone;
    private final Clock clock;

    public MessageSummaryBuilder() {
        this(DEFAULT_MAX_CHARS);
    }

    public MessageSummaryBuilder(int maxChars) {
        this(maxChars, ZoneId.systemDefault());
    }

    public MessageSummaryBuilder(int maxChars, ZoneId zone) {
        this(maxChars, zone, Clock.systemUTC());
    }

    public MessageSummaryBuilder(int maxChars, ZoneId zone, Clock clock) {
        if (maxChars < 16) {
            throw new IllegalArgumentException("maxChars 至少为 16");
        }
        this.maxChars = maxChars;
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 为一条消息生成渲染块；引用目标未知。
     *
     * @param message 已入库消息（localSeq > 0）
     * @param note    发送者备注；无备注传 null
     */
    public MessageSummary build(ChatMessage message, UserNote note) {
        return build(message, note, null, List.of());
    }

    /**
     * 为一条消息生成渲染块。
     *
     * @param message 已入库消息（localSeq > 0）
     * @param note    发送者备注；无备注传 null
     * @param replyTo 被引用的消息；无法解析时传 null（此时整行省略）
     */
    public MessageSummary build(ChatMessage message, UserNote note, ChatMessage replyTo) {
        return build(message, note, replyTo, List.of());
    }

    /**
     * 为一条消息生成渲染块（带段信息）。
     *
     * @param segments 该消息的全部段；用来把 {@code @} 渲染成具体目标，无段时传空列表
     */
    public MessageSummary build(ChatMessage message, UserNote note, ChatMessage replyTo,
                                List<MessageSegment> segments) {
        return build(message, note, replyTo, segments, true);
    }

    /**
     * 为一条消息生成渲染块。
     *
     * @param includeNote 是否渲染备注；同一次注入里同一个发送者只需要出现一次备注
     */
    public MessageSummary build(ChatMessage message, UserNote note, ChatMessage replyTo,
                                List<MessageSegment> segments, boolean includeNote) {
        Objects.requireNonNull(message, "message");
        if (message.localSeq() <= 0L) {
            throw new IllegalArgumentException("消息必须先入库并分配 localSeq");
        }
        Set<String> tags = note == null ? Set.of() : note.tags();
        String noteText = note == null ? "" : flatten(note.note());
        if (!includeNote) {
            noteText = "";
        }
        String sender = message.direction() == ChatDirection.OUT
                ? "我"
                : firstNonBlank(message.senderName(), message.senderId());
        String body = firstNonBlank(message.text(), message.nonTextSummary(), "[空消息]");
        String mention = renderMentions(segments, message.source().accountId());
        String replyLine = renderReply(message, replyTo);
        String mediaLine = renderMedia(message);
        String block = renderBlock(message, sender, noteText, tags, body, mention,
                replyLine, mediaLine);
        return new MessageSummary(
                message.source().threadId(),
                message.localSeq(),
                message.messageId(),
                message.messageKey(),
                message.direction(),
                message.senderId(),
                message.senderName(),
                tags,
                noteText,
                note == null ? 0 : note.priority(),
                truncate(block, maxChars),
                message.sentAt(),
                message.replyToMessageId(),
                replyTo == null ? "" : snippet(replyTo));
    }

    private String renderBlock(ChatMessage message, String sender, String noteText,
                               Set<String> tags, String body, String mention,
                               String replyLine, String mediaLine) {
        String header = "[" + stampOf(message) + "] #" + message.localSeq() + " " + sender
                + renderQualifier(noteText, tags) + "：";
        String rendered = flatten(body);
        if (!mention.isEmpty()) {
            rendered = rendered.isEmpty() ? mention : mention + " " + rendered;
        }
        body = rendered;
        boolean multiline = !replyLine.isEmpty() || !mediaLine.isEmpty();
        StringBuilder raw = new StringBuilder(header);
        if (multiline) {
            raw.append('\n');
            if (!replyLine.isEmpty()) {
                raw.append(replyLine).append('\n');
            }
            raw.append("  ").append(flatten(body));
            if (!mediaLine.isEmpty()) {
                raw.append('\n').append(mediaLine);
            }
        } else {
            raw.append(flatten(body));
        }
        return raw.toString();
    }

    /** {@code 22:21}；跨天时写成 {@code 11-05 22:21}，避免"昨天/今天"混淆。 */
    private String stampOf(ChatMessage message) {
        var when = whenOf(message).atZone(zone);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        return when.toLocalDate().equals(today)
                ? TIME_FORMAT.format(when) : DATE_TIME_FORMAT.format(when);
    }

    /**
     * 把 {@code @} 段渲染成具体目标：{@code [@你]} 或被 @ 的 QQ 号。
     *
     * <p>原始载荷里只有段类型没有名字，之前渲染成 {@code [@×1]}，Agent 完全不知道 @ 的是谁。</p>
     */
    private static String renderMentions(List<MessageSegment> segments, String accountId) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        return segments.stream()
                .filter(segment -> segment != null && segment.type() == MessageSegment.Type.MENTION)
                .map(segment -> {
                    String target = segment.qqId() == null ? "" : segment.qqId().trim();
                    if (target.isEmpty() || target.equals(accountId)) {
                        return "[@你]";
                    }
                    return "[@" + target + "]";
                })
                .distinct()
                .collect(Collectors.joining(" "));
    }

    /** 备注与标签合并在同一对括号里；都没有时返回空串。 */
    private static String renderQualifier(String noteText, Set<String> tags) {
        String tagText = tags.stream()
                .sorted()
                .map(tag -> "#" + tag)
                .collect(Collectors.joining(" "));
        if (noteText.isEmpty() && tagText.isEmpty()) {
            return "";
        }
        if (noteText.isEmpty()) {
            return "（" + tagText + "）";
        }
        if (tagText.isEmpty()) {
            return "（备注：" + noteText + "）";
        }
        return "（备注：" + noteText + "｜" + tagText + "）";
    }

    /** {@code 引用 ↩ 李四 [22:19]「前 40 字」：}；无法解析引用时返回空串。 */
    private String renderReply(ChatMessage message, ChatMessage replyTo) {
        if (replyTo == null) {
            return "";
        }
        String who = replyTo.direction() == ChatDirection.OUT
                ? "我"
                : firstNonBlank(replyTo.senderName(), replyTo.senderId());
        String when = REPLY_TIME_FORMAT.format(whenOf(replyTo).atZone(zone));
        return "  引用 ↩ " + who + " [" + when + "]「" + snippet(replyTo) + "」：";
    }

    /**
     * {@code [图片 messageKey=…]} 一行；纯文本消息返回空串。
     *
     * <p>{@code messageKey} 是 Agent 取媒体或引用的唯一句柄。</p>
     */
    private static String renderMedia(ChatMessage message) {
        String hint = message.nonTextSummary();
        if (hint == null || hint.isBlank()) {
            return "";
        }
        String normalized = hint.trim();
        String key = message.messageKey();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            String inner = normalized.substring(1, normalized.length() - 1).trim();
            return inner.isEmpty()
                    ? "  [messageKey=" + key + "]"
                    : "  [" + inner + " messageKey=" + key + "]";
        }
        return "  " + normalized + " (messageKey=" + key + ")";
    }

    /** {@code sentAt} 可能缺失（部分平台只给接收时间），渲染时回退到 {@code receivedAt}。 */
    private static java.time.Instant whenOf(ChatMessage message) {
        return message.sentAt() != null ? message.sentAt() : message.receivedAt();
    }

    private static String snippet(ChatMessage message) {
        String body = firstNonBlank(message.text(), message.nonTextSummary(), "[空消息]");
        String flat = flatten(body);
        if (flat.length() <= REPLY_SNIPPET_CHARS) {
            return flat;
        }
        return flat.substring(0, REPLY_SNIPPET_CHARS) + "…";
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
