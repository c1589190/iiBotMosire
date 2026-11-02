package io.mosire.iibot.bot.group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 内存群目录，用于测试与无持久化场景。 */
public final class InMemoryGroupCatalogueStore implements GroupCatalogueStore {

    private final Map<String, GroupCatalogueEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized void upsert(GroupCatalogueEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.groupId(), entry);
    }

    @Override
    public synchronized void upsertAll(Collection<GroupCatalogueEntry> values) {
        if (values == null) {
            return;
        }
        for (GroupCatalogueEntry entry : values) {
            if (entry != null) {
                upsert(entry);
            }
        }
    }

    @Override
    public synchronized List<GroupCatalogueEntry> search(String keyword, int limit) {
        List<GroupCatalogueEntry> matched = new ArrayList<>();
        for (GroupCatalogueEntry entry : entries.values()) {
            if (entry.matches(keyword)) {
                matched.add(entry);
            }
        }
        matched.sort(Comparator.comparing(GroupCatalogueEntry::lastSeenAt).reversed());
        if (matched.size() <= limit) {
            return List.copyOf(matched);
        }
        return List.copyOf(matched.subList(0, Math.max(0, limit)));
    }

    @Override
    public List<GroupCatalogueEntry> list(int limit) {
        return search("", limit);
    }

    @Override
    public synchronized Optional<GroupCatalogueEntry> get(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(groupId.trim()));
    }
}
