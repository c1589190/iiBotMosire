package io.mosire.iibot.bot.group;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteGroupCatalogueStoreTest {

    private static final Instant T0 = Instant.parse("2026-11-02T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void keepsFirstSeenAndRefreshesLastSeenOnUpsert() {
        Path db = tempDir.resolve("bot.db");
        try (SqliteGroupCatalogueStore store = new SqliteGroupCatalogueStore(db)) {
            store.upsert(new GroupCatalogueEntry("938269591", "目标群",
                    GroupCatalogueEntry.SOURCE_INVITE, T0));
            store.upsert(new GroupCatalogueEntry("938269591", "目标群改名",
                    GroupCatalogueEntry.SOURCE_JOINED, T0.plusSeconds(60)));

            GroupCatalogueEntry entry = store.get("938269591").orElseThrow();
            assertThat(entry.groupName()).isEqualTo("目标群改名");
            assertThat(entry.source()).isEqualTo(GroupCatalogueEntry.SOURCE_JOINED);
            assertThat(entry.lastSeenAt()).isEqualTo(T0.plusSeconds(60));
        }
        try (SqliteGroupCatalogueStore reopened = new SqliteGroupCatalogueStore(db)) {
            assertThat(reopened.get("938269591")).isPresent();
            assertThat(reopened.get("missing")).isEmpty();
        }
    }

    @Test
    void searchesByGroupNumberAndNameWithLimit() {
        try (SqliteGroupCatalogueStore store = new SqliteGroupCatalogueStore(
                tempDir.resolve("bot.db"))) {
            store.upsertAll(List.of(
                    new GroupCatalogueEntry("915899297", "公共群",
                            GroupCatalogueEntry.SOURCE_JOINED, T0),
                    new GroupCatalogueEntry("938269591", "目标群",
                            GroupCatalogueEntry.SOURCE_INVITE, T0.plusSeconds(10)),
                    new GroupCatalogueEntry("1072031273", "另一个群",
                            GroupCatalogueEntry.SOURCE_JOIN_REQUEST, T0.plusSeconds(20))));

            assertThat(store.search("938269591", 10)).singleElement()
                    .extracting(GroupCatalogueEntry::groupName)
                    .isEqualTo("目标群");
            assertThat(store.search("群", 10)).hasSize(3);
            assertThat(store.search("群", 2)).hasSize(2);
            assertThat(store.search("", 10)).extracting(GroupCatalogueEntry::groupId)
                    .containsExactly("1072031273", "938269591", "915899297");
            assertThat(store.search("不存在", 10)).isEmpty();
        }
    }

    @Test
    void entryMatchesKeywordsCaseInsensitively() {
        GroupCatalogueEntry entry = new GroupCatalogueEntry("915899297", "PublicGroup",
                GroupCatalogueEntry.SOURCE_JOINED, T0);

        assertThat(entry.matches("")).isTrue();
        assertThat(entry.matches("9158")).isTrue();
        assertThat(entry.matches("public")).isTrue();
        assertThat(entry.matches("在其他")).isFalse();
    }
}
