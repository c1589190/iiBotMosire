package io.mosire.iibot.bot.weight;

import java.util.List;
import java.util.Objects;

/** 写入 Inbox digest content 的权重解释；不包含消息正文。 */
public record InboxWeight(
        int sourceWeight,
        int signalWeight,
        int keywordWeight,
        int totalScore,
        int threshold,
        List<WeightSignal> matchedSignals,
        List<String> matchedKeywords) {

    public InboxWeight {
        checkWeight(sourceWeight, "sourceWeight");
        checkWeight(signalWeight, "signalWeight");
        checkWeight(keywordWeight, "keywordWeight");
        checkWeight(totalScore, "totalScore");
        checkWeight(threshold, "threshold");
        matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
    }

    public static InboxWeight none(int priority) {
        return new InboxWeight(priority, 0, 0, priority, priority,
                List.of(), List.of());
    }

    private static void checkWeight(int value, String name) {
        if (value < -1000 || value > 1000) {
            throw new IllegalArgumentException(name + " 必须在 -1000..1000");
        }
    }
}
