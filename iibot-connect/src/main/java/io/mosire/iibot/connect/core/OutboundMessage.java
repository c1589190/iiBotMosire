package io.mosire.iibot.connect.core;

import java.util.List;
import java.util.Objects;

/**
 * 中立的消息发送请求。
 *
 * <p>{@code textSegments} 表示调用方希望保持顺序的文本片段。适配器仍会按自身
 * 最大文本长度再次安全切分；所有片段都通过 text 段发送，不会解释 CQ Code。</p>
 *
 * @param targetType     私聊或群聊
 * @param targetId       私聊用户标识或群标识
 * @param textSegments   兼容用文本片段列表；富消息时由 Text 段派生
 * @param idempotencyKey 调用方提供的稳定幂等键；相同键至多触发一次逻辑发送
 * @param temporaryGroupId 私聊临时会话的来源群；null 表示普通私聊
 * @param richSegments   可选的富消息段；为空时走 textSegments 兼容路径
 */
public record OutboundMessage(
        TargetType targetType,
        String targetId,
        List<String> textSegments,
        String idempotencyKey,
        String temporaryGroupId,
        List<OutboundSegment> richSegments) {

    /** 发送目标类型。 */
    public enum TargetType {
        PRIVATE,
        GROUP
    }

    public OutboundMessage {
        targetType = Objects.requireNonNull(targetType, "targetType");
        targetId = requireText(targetId, "targetId");
        richSegments = richSegments == null ? List.of() : List.copyOf(richSegments);
        if (richSegments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("richSegments 不能包含 null");
        }
        textSegments = textSegments == null ? List.of() : List.copyOf(textSegments);
        if (textSegments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("textSegments 不能包含 null");
        }
        if (richSegments.isEmpty()) {
            if (textSegments.isEmpty()) {
                throw new IllegalArgumentException("textSegments 不能为空");
            }
            if (textSegments.stream().allMatch(String::isBlank)) {
                throw new IllegalArgumentException("空文本被拒绝");
            }
        } else {
            if (textSegments.isEmpty()) {
                textSegments = richSegments.stream()
                        .filter(OutboundSegment.Text.class::isInstance)
                        .map(OutboundSegment.Text.class::cast)
                        .map(OutboundSegment.Text::text)
                        .toList();
            }
            boolean hasContent = richSegments.stream().anyMatch(segment ->
                    (segment instanceof OutboundSegment.Text text && !text.text().isBlank())
                            || segment instanceof OutboundSegment.Image
                            || segment instanceof OutboundSegment.Sticker);
            if (!hasContent) {
                throw new IllegalArgumentException(
                        "richSegments 至少需要一个非空 Text/Image/Sticker 段");
            }
            if (targetType != TargetType.GROUP && richSegments.stream()
                    .anyMatch(segment -> segment instanceof OutboundSegment.Mention
                            || segment instanceof OutboundSegment.MentionAll)) {
                throw new IllegalArgumentException("Mention/MentionAll 段只适用于群聊");
            }
        }
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (temporaryGroupId != null && temporaryGroupId.isBlank()) {
            throw new IllegalArgumentException("temporaryGroupId 不能为空白");
        }
        if (targetType != TargetType.PRIVATE && temporaryGroupId != null) {
            throw new IllegalArgumentException("temporaryGroupId 只适用于私聊");
        }
    }

    /** 兼容文本消息 5 参构造。 */
    public OutboundMessage(TargetType targetType, String targetId,
                           List<String> textSegments, String idempotencyKey,
                           String temporaryGroupId) {
        this(targetType, targetId, textSegments, idempotencyKey, temporaryGroupId, List.of());
    }

    /** 兼容旧 4 参构造：普通私聊/群聊。 */
    public OutboundMessage(TargetType targetType, String targetId,
                           List<String> textSegments, String idempotencyKey) {
        this(targetType, targetId, textSegments, idempotencyKey, null, List.of());
    }

    /** 构造普通私聊消息。 */
    public static OutboundMessage privateMessage(
            String userId, List<String> textSegments, String idempotencyKey) {
        return new OutboundMessage(TargetType.PRIVATE, userId, textSegments, idempotencyKey,
                null);
    }

    /** 构造群临时会话私聊消息。 */
    public static OutboundMessage privateTemporaryMessage(
            String userId, String groupId, List<String> textSegments, String idempotencyKey) {
        return new OutboundMessage(TargetType.PRIVATE, userId, textSegments, idempotencyKey,
                requireText(groupId, "temporaryGroupId"));
    }

    /** 构造群消息。 */
    public static OutboundMessage groupMessage(
            String groupId, List<String> textSegments, String idempotencyKey) {
        return new OutboundMessage(TargetType.GROUP, groupId, textSegments, idempotencyKey);
    }

    /** 构造富文本群消息。 */
    public static OutboundMessage richGroupMessage(
            String groupId, List<OutboundSegment> segments, String idempotencyKey) {
        return new OutboundMessage(TargetType.GROUP, groupId, List.of(), idempotencyKey,
                null, segments);
    }

    /** 构造富文本普通私聊消息。 */
    public static OutboundMessage richPrivateMessage(
            String userId, List<OutboundSegment> segments, String idempotencyKey) {
        return new OutboundMessage(TargetType.PRIVATE, userId, List.of(), idempotencyKey,
                null, segments);
    }

    /** 构造富文本群临时私聊消息。 */
    public static OutboundMessage richPrivateTemporaryMessage(
            String userId, String groupId, List<OutboundSegment> segments,
            String idempotencyKey) {
        return new OutboundMessage(TargetType.PRIVATE, userId, List.of(), idempotencyKey,
                requireText(groupId, "temporaryGroupId"), segments);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
