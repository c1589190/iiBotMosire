package io.mosire.iibot.bot.note;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单 JVM 内存用户备注存储。 */
public final class InMemoryUserNoteStore implements UserNoteStore {

    private final Clock clock;
    private final Map<String, UserNote> notes = new LinkedHashMap<>();

    public InMemoryUserNoteStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Optional<UserNote> find(String accountId, String qqId) {
        if (accountId == null || accountId.isBlank() || qqId == null || qqId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(notes.get(key(accountId, qqId)));
    }

    @Override
    public synchronized List<UserNote> list(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        return notes.values().stream()
                .sorted(Comparator.comparingInt(UserNote::priority).reversed()
                        .thenComparing(UserNote::updatedAt, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized UserNote upsert(UserNoteDraft draft, Long expectedRevision) {
        Objects.requireNonNull(draft, "draft");
        String key = key(draft.accountId(), draft.qqId());
        UserNote current = notes.get(key);
        long currentRevision = current == null ? 0L : current.revision();
        if (expectedRevision != null) {
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("expectedRevision 不能为负");
            }
            if (expectedRevision.longValue() != currentRevision) {
                throw new UserNoteRevisionConflictException(expectedRevision, currentRevision);
            }
        }
        long nextRevision = currentRevision + 1L;
        UserNote next = new UserNote(draft.accountId(), draft.qqId(), draft.displayName(),
                draft.tags(), draft.note(), draft.priority(), nextRevision, clock.instant());
        notes.put(key, next);
        return next;
    }

    private static String key(String accountId, String qqId) {
        return accountId.trim() + "\n" + qqId.trim();
    }
}
