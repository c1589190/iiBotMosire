package io.mosire.iibot.bot.weight;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.InboundMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 来源权重、消息信号和通知阈值。 */
public final class SourceWeightService {

    private final SourceWeightStore store;
    private final MessageBoardStore board;
    private final WeightSettings settings;
    private final Clock clock;

    public SourceWeightService(SourceWeightStore store, MessageBoardStore board,
                               WeightSettings settings, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.board = Objects.requireNonNull(board, "board");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 给升级前已有 thread 补齐默认策略，避免把旧群当成新来源引导。 */
    public synchronized void ensureExistingThreads(int threadLimit) {
        for (ChatThread thread : board.threads(threadLimit)) {
            ensurePolicy(thread.source(), true);
        }
    }

    public synchronized Optional<SourceWeightPolicy> findPolicy(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return store.findPolicy(threadId.trim());
    }

    public synchronized SourceWeightPolicy ensurePolicy(ChatSourceKey source,
                                                         boolean existingThread) {
        Objects.requireNonNull(source, "source");
        Optional<SourceWeightPolicy> existing = store.findPolicy(source.threadId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Instant now = clock.instant();
        SourceWeightPolicy policy = new SourceWeightPolicy(
                source.threadId(), source.chatType(), source.targetId(),
                settings.defaultSourceWeight(source.chatType()),
                settings.defaultNotifyThreshold(),
                settings.signalWeights(),
                settings.defaultKeywordWeights(),
                settings.maxMessagesPerDigest(),
                settings.quietMillis(),
                settings.cooldownMillis(),
                existingThread,
                0L,
                now);
        return store.savePolicy(policy, null);
    }

    public synchronized MessageWeightDecision evaluate(InboundMessage inbound,
                                                        ChatMessage stored,
                                                        SourceWeightPolicy policy) {
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(policy, "policy");
        if (!stored.source().threadId().equals(policy.threadId())) {
            throw new IllegalArgumentException("消息与来源策略不属于同一 thread");
        }
        Set<WeightSignal> signals = new LinkedHashSet<>();
        String lowerText = stored.text() == null ? "" : stored.text().toLowerCase(Locale.ROOT);
        if (stored.source().chatType() == io.mosire.iibot.bot.chat.ChatType.PRIVATE) {
            signals.add(WeightSignal.DM);
        }
        if (inbound.mentionedSelf()) {
            signals.add(WeightSignal.MENTION);
        }
        if (!inbound.optionalReplyToMessageId().isEmpty()
                && board.findByMessageId(policy.threadId(), inbound.replyToMessageId())
                        .map(message -> message.direction() == ChatDirection.OUT)
                        .orElse(false)) {
            signals.add(WeightSignal.REPLY_TO_BOT);
        }
        if (containsAlias(lowerText)) {
            signals.add(WeightSignal.NAME);
        }
        if (startsWithCommand(lowerText)) {
            signals.add(WeightSignal.COMMAND);
        }
        int signalWeight = signals.stream()
                .mapToInt(signal -> policy.signalWeights().getOrDefault(
                        signal, settings.weightOf(signal)))
                .max()
                .orElse(0);
        List<String> matchedKeywords = new ArrayList<>();
        int keywordWeight = 0;
        for (Map.Entry<String, Integer> entry : policy.keywordWeights().entrySet()) {
            String keyword = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty() && lowerText.contains(keyword)) {
                matchedKeywords.add(entry.getKey());
                keywordWeight += entry.getValue();
            }
        }
        int totalScore = clamp(policy.sourceWeight() + signalWeight + keywordWeight);
        boolean bootstrap = !policy.bootstrapDelivered();
        boolean actionable = bootstrap || totalScore >= policy.notifyThreshold();
        WeightWakeReason reason = wakeReason(
                bootstrap, signals, policy.signalWeights(), signalWeight,
                keywordWeight, totalScore, policy.notifyThreshold());
        return new MessageWeightDecision(
                stored.messageKey(), policy.threadId(), policy.sourceWeight(),
                signalWeight, keywordWeight, totalScore, reason,
                List.copyOf(signals), List.copyOf(matchedKeywords),
                actionable, bootstrap, policy.revision(), clock.instant());
    }

    public synchronized void saveDecision(MessageWeightDecision decision) {
        store.saveDecision(decision);
    }

    public synchronized Optional<MessageWeightDecision> decision(String messageKey) {
        return store.findDecision(messageKey);
    }

    public synchronized Map<String, MessageWeightDecision> decisions(List<String> messageKeys) {
        return store.decisions(messageKeys);
    }

    /** 没有旧判定时按 legacy 消息处理，避免升级后旧活动 item 丢失。 */
    public synchronized boolean actionableOrLegacy(String messageKey) {
        return store.findDecision(messageKey).map(MessageWeightDecision::actionable).orElse(true);
    }

    public synchronized void markBootstrapDelivered(String threadId) {
        SourceWeightPolicy policy = store.findPolicy(threadId).orElse(null);
        if (policy == null || policy.bootstrapDelivered()) {
            return;
        }
        store.savePolicy(policy.withBootstrapDelivered(true, clock.instant()),
                policy.revision());
    }

    public synchronized SourceWeightPolicy setPolicy(
            String threadId,
            Integer sourceWeight,
            Integer notifyThreshold,
            Map<WeightSignal, Integer> signalWeights,
            Map<String, Integer> keywordWeights,
            Integer maxMessages,
            Long quietMillis,
            Long cooldownMillis,
            Long expectedRevision) {
        ChatThread thread = board.thread(requireText(threadId, "threadId"))
                .orElseThrow(() -> new IllegalArgumentException("thread 不存在"));
        SourceWeightPolicy current = ensurePolicy(thread.source(), true);
        long expected = expectedRevision == null ? current.revision() : expectedRevision;
        SourceWeightPolicy next = new SourceWeightPolicy(
                current.threadId(), current.chatType(), current.targetId(),
                sourceWeight == null ? current.sourceWeight() : sourceWeight,
                notifyThreshold == null ? current.notifyThreshold() : notifyThreshold,
                signalWeights == null ? current.signalWeights() : signalWeights,
                keywordWeights == null ? current.keywordWeights() : keywordWeights,
                maxMessages == null ? current.maxMessages() : maxMessages,
                quietMillis == null ? current.quietMillis() : quietMillis,
                cooldownMillis == null ? current.cooldownMillis() : cooldownMillis,
                true,
                current.revision(),
                clock.instant());
        return store.savePolicy(next, expected);
    }

    public synchronized List<SourceWeightPolicy> listPolicies(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        return store.listPolicies(limit);
    }

    public synchronized long enqueueDelayMillis(SourceWeightPolicy policy,
                                                MessageWeightDecision decision) {
        if (decision.actionable()) {
            return Math.min(2_000L, policy.quietMillis());
        }
        return policy.quietMillis();
    }

    private boolean containsAlias(String lowerText) {
        for (String alias : settings.aliases()) {
            if (!alias.isBlank() && lowerText.contains(alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithCommand(String lowerText) {
        String trimmed = lowerText.stripLeading();
        for (String prefix : settings.commandPrefixes()) {
            if (!prefix.isBlank() && trimmed.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static WeightWakeReason wakeReason(
            boolean bootstrap, Set<WeightSignal> signals,
            Map<WeightSignal, Integer> weights, int signalWeight,
            int keywordWeight, int totalScore, int threshold) {
        if (bootstrap) {
            return WeightWakeReason.FIRST_CONTACT;
        }
        if (keywordWeight > 0 && keywordWeight >= signalWeight) {
            return WeightWakeReason.KEYWORD;
        }
        WeightSignal primary = signals.stream()
                .max((first, second) -> Integer.compare(
                        weights.getOrDefault(first, signalWeight(first)),
                        weights.getOrDefault(second, signalWeight(second))))
                .orElse(null);
        if (primary != null && signalWeight > 0) {
            return switch (primary) {
                case DM -> WeightWakeReason.DM;
                case MENTION -> WeightWakeReason.MENTION;
                case REPLY_TO_BOT -> WeightWakeReason.REPLY_TO_BOT;
                case NAME -> WeightWakeReason.NAME;
                case COMMAND -> WeightWakeReason.COMMAND;
            };
        }
        return totalScore >= threshold
                ? WeightWakeReason.SOURCE_WEIGHT : WeightWakeReason.BELOW_THRESHOLD;
    }

    private static int signalWeight(WeightSignal signal) {
        return switch (signal) {
            case DM, MENTION -> 200;
            case REPLY_TO_BOT -> 180;
            case NAME -> 120;
            case COMMAND -> 250;
        };
    }

    private static int clamp(int value) {
        return Math.max(-1000, Math.min(1000, value));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
