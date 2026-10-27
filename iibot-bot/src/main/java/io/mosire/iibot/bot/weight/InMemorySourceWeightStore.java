package io.mosire.iibot.bot.weight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 测试/单 JVM 来源权重存储。 */
public final class InMemorySourceWeightStore implements SourceWeightStore {

    private final Map<String, SourceWeightPolicy> policies = new LinkedHashMap<>();
    private final Map<String, MessageWeightDecision> decisions = new LinkedHashMap<>();

    @Override
    public synchronized Optional<SourceWeightPolicy> findPolicy(String threadId) {
        return Optional.ofNullable(policies.get(threadId));
    }

    @Override
    public synchronized SourceWeightPolicy savePolicy(SourceWeightPolicy policy,
                                                       Long expectedRevision) {
        SourceWeightPolicy current = policies.get(policy.threadId());
        long actual = current == null ? 0L : current.revision();
        if (expectedRevision != null && expectedRevision.longValue() != actual) {
            throw new SourceWeightConflictException(expectedRevision, actual);
        }
        SourceWeightPolicy stored = policy.withRevision(actual + 1L, policy.updatedAt());
        policies.put(stored.threadId(), stored);
        return stored;
    }

    @Override
    public synchronized List<SourceWeightPolicy> listPolicies(int limit) {
        List<SourceWeightPolicy> result = new ArrayList<>(policies.values());
        result.sort(Comparator.comparingInt(SourceWeightPolicy::sourceWeight).reversed()
                .thenComparing(SourceWeightPolicy::updatedAt, Comparator.reverseOrder()));
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
    }

    @Override
    public synchronized Optional<MessageWeightDecision> findDecision(String messageKey) {
        return Optional.ofNullable(decisions.get(messageKey));
    }

    @Override
    public synchronized void saveDecision(MessageWeightDecision decision) {
        decisions.put(decision.messageKey(), decision);
    }
}
