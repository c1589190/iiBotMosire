package io.mosire.iibot.bot.weight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 来源策略与消息权重判定的持久化端口。 */
public interface SourceWeightStore {

    Optional<SourceWeightPolicy> findPolicy(String threadId);

    /** expectedRevision=null/0 表示新建；返回带实际 revision 的策略。 */
    SourceWeightPolicy savePolicy(SourceWeightPolicy policy, Long expectedRevision);

    List<SourceWeightPolicy> listPolicies(int limit);

    Optional<MessageWeightDecision> findDecision(String messageKey);

    void saveDecision(MessageWeightDecision decision);

    default Map<String, MessageWeightDecision> decisions(List<String> messageKeys) {
        Map<String, MessageWeightDecision> result = new LinkedHashMap<>();
        for (String key : messageKeys) {
            findDecision(key).ifPresent(decision -> result.put(key, decision));
        }
        return Map.copyOf(result);
    }
}
