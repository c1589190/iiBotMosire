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
 * @param textSegments   非空文本片段列表
 * @param idempotencyKey 调用方提供的稳定幂等键；相同键至多触发一次逻辑发送
 */
public record OutboundMessage(
        TargetType targetType,
        String targetId,
        List<String> textSegments,
        String idempotencyKey) {

    /** 发送目标类型。 */
    public enum TargetType {
        PRIVATE,
        GROUP
    }

    public OutboundMessage {
        targetType = Objects.requireNonNull(targetType, "targetType");
        targetId = requireText(targetId, "targetId");
        Objects.requireNonNull(textSegments, "textSegments");
        if (textSegments.isEmpty()) {
            throw new IllegalArgumentException("textSegments 不能为空");
        }
        textSegments = List.copyOf(textSegments);
        if (textSegments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("textSegments 不能包含 null");
        }
        if (textSegments.stream().allMatch(String::isBlank)) {
            throw new IllegalArgumentException("空文本被拒绝");
        }
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    }

    /** 构造私聊消息。 */
    public static OutboundMessage privateMessage(
            String userId, List<String> textSegments, String idempotencyKey) {
        return new OutboundMessage(TargetType.PRIVATE, userId, textSegments, idempotencyKey);
    }

    /** 构造群消息。 */
    public static OutboundMessage groupMessage(
            String groupId, List<String> textSegments, String idempotencyKey) {
        return new OutboundMessage(TargetType.GROUP, groupId, textSegments, idempotencyKey);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
        return value;
    }
}
