package io.mosire.iibot.app.dispatch;

import io.mosire.iibot.connect.core.InboundMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 入站私信 ACK 策略：只根据配置身份与消息类型决定是否回复“已收到”状态提示。
 *
 * <p>本接口不产生事件、不记录日志，也不接触 LLM；返回文本只交给应用层 outbox 发送。
 * 调用方必须自行保证 ACK 正文不进入审计/模型上下文。</p>
 */
@FunctionalInterface
public interface AckPolicy {

    /** 已登记玩家的私信 ACK 文本。 */
    String PLAYER_ACK_TEXT = "消息已收到，秘书正在处理，请稍候。";

    /** 推演/GM 的私信 ACK 文本。 */
    String DIRECTOR_ACK_TEXT = "消息已收到，GM 正在处理，请稍候。";

    /**
     * 为一条入站消息解析 ACK 文本。
     *
     * @return 需要 ACK 时返回文本；群聊、自身消息、未登记身份或无需 ACK 时返回 empty
     */
    Optional<String> ackFor(InboundMessage message);

    /** 语义别名，便于调用方按“文本”语义读取。 */
    default Optional<String> ackText(InboundMessage message) {
        return ackFor(message);
    }

    /** 语义别名，便于调用方按“解析”语义读取。 */
    default Optional<String> resolve(InboundMessage message) {
        return ackFor(message);
    }

    /**
     * 按 QQ 号到 ACK 文本的映射构建策略。
     *
     * <p>只有 {@link InboundMessage.SessionType#PRIVATE}、非自身消息且 QQ 号在映射中时才
     * 返回 ACK。映射在构建时复制并冻结，之后配置变化不会影响已装配的策略。</p>
     */
    static AckPolicy configured(Map<String, String> ackTextByQq) {
        Objects.requireNonNull(ackTextByQq, "ackTextByQq");
        Map<String, String> copy = new LinkedHashMap<>();
        ackTextByQq.forEach((qqId, text) -> {
            if (qqId == null || qqId.isBlank()) {
                throw new IllegalArgumentException("ACK QQ 映射不能包含空键");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("ACK 文本不能为空白");
            }
            copy.put(qqId, text);
        });
        Map<String, String> mappings = Collections.unmodifiableMap(copy);
        return message -> {
            if (message == null
                    || message.sessionType() != InboundMessage.SessionType.PRIVATE) {
                return Optional.empty();
            }
            if (Objects.equals(message.selfId(), message.userId())) {
                return Optional.empty();
            }
            return Optional.ofNullable(mappings.get(message.userId()));
        };
    }

    /**
     * 动态 ACK 策略：每次接收消息时重新调用 {@code ackTextByQq} 读取当前 QQ 映射。
     *
     * <p>函数返回 {@code null}/空白表示该 QQ 当前未登记。该端口用于运行期动态注册玩家：
     * 不重建 dispatcher，也不把启动配置的身份 map 冻结成第二事实源。</p>
     */
    static AckPolicy dynamic(Function<String, String> ackTextByQq) {
        Objects.requireNonNull(ackTextByQq, "ackTextByQq");
        return message -> {
            if (message == null
                    || message.sessionType() != InboundMessage.SessionType.PRIVATE) {
                return Optional.empty();
            }
            if (Objects.equals(message.selfId(), message.userId())) {
                return Optional.empty();
            }
            String userId = message.userId();
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            String text = ackTextByQq.apply(userId);
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
        };
    }

    /**
     * 动态 ACK 策略的快照供应商形式：每次 ACK 时读取 supplier 返回的最新 map。
     * supplier 返回 null 视为暂无登记身份。
     */
    static AckPolicy dynamic(Supplier<Map<String, String>> ackTextByQq) {
        Objects.requireNonNull(ackTextByQq, "ackTextByQq");
        return dynamic(qqId -> {
            Map<String, String> current = ackTextByQq.get();
            return current == null ? null : current.get(qqId);
        });
    }

    /** {@link #configured(Map)} 的简短别名。 */
    static AckPolicy of(Map<String, String> ackTextByQq) {
        return configured(ackTextByQq);
    }
}
