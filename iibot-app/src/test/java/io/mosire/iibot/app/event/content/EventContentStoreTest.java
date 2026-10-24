package io.mosire.iibot.app.event.content;

import io.mosire.iibot.app.event.SqliteEventReadPort;
import io.mosire.iibot.taskflow.event.EventAppendRequest;
import io.mosire.iibot.taskflow.event.EventContent;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventContentStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-24T05:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void roundTripsJsonHeadersBodyHashAndSanitizesSecrets() throws Exception {
        Path db = tempDir.resolve("round-trip.db");
        String secret = "sk-p1-do-not-store";
        try (EventContentStore store = new EventContentStore(db, List.of(secret))) {
            StoredEvent appended = store.append(jsonRequest(
                    "{\"apiKey\":\"" + secret + "\",\"body\":\"contains " + secret + "\"}",
                    "{\"postId\":\"post-1\",\"token\":\"" + secret + "\"}"));

            assertThat(appended.seq()).isPositive();
            assertThat(appended.contentHash()).hasSize(64);
            assertThat(appended.contentHash()).isEqualTo(sha256(appended.content().body()));
            assertThat(appended.content()).isNotNull();
            assertThat(appended.content().type()).isEqualTo(EventContentType.JSON);
            assertThat(appended.content().json())
                    .doesNotContain(secret)
                    .contains("[REDACTED]");
            assertThat(appended.payloadJson())
                    .doesNotContain(secret)
                    .contains("[REDACTED]");

            StoredEvent read = store.read(appended.seq()).orElseThrow();
            assertThat(read).isEqualTo(appended);
            assertThat(read.occurredAt()).isEqualTo(NOW);
            assertThat(read.eventType()).isEqualTo("POST_DRAFTED");
            assertThat(read.agent()).isEqualTo("GM:gm");
            assertThat(read.correlationId()).isEqualTo("corr-1");
            assertThat(read.turnId()).isEqualTo("turn-1");
            assertThat(read.eventVersion()).isEqualTo(EventContentStore.CURRENT_EVENT_VERSION);
            assertThat(read.visibility()).isEqualTo(EventVisibility.GM);
            assertThat(read.contentMissing()).isFalse();
            assertThat(read.contentHash()).isEqualTo(sha256(read.content().body()));
        }
    }

    @Test
    void roundTripsMarkdownAndTextHeadersBodyHashAndOrderedLimit() throws Exception {
        try (EventContentStore store = new EventContentStore(tempDir.resolve("markdown.db"))) {
            StoredEvent first = store.append(new EventAppendRequest(
                    NOW, "POST_PUBLISHED", "GM:gm", "corr-md", "turn-md",
                    EventContentStore.CURRENT_EVENT_VERSION, EventVisibility.PUBLIC,
                    new EventContent(EventContentType.MARKDOWN, null, "# 公开叙事\n\n第一段。"),
                    "{\"kind\":\"public\"}"));
            StoredEvent second = store.append(new EventAppendRequest(
                    NOW.plusSeconds(1), "PLAYER_OUTCOME", "SECRETARY:sec-1", "corr-text", null,
                    EventContentStore.CURRENT_EVENT_VERSION, EventVisibility.PLAYER,
                    new EventContent(EventContentType.TEXT, null, "玩家私密结果"),
                    "{\"kind\":\"private\"}"));

            StoredEvent readFirst = store.read(first.seq()).orElseThrow();
            assertThat(readFirst).isEqualTo(first);
            assertThat(readFirst.eventType()).isEqualTo("POST_PUBLISHED");
            assertThat(readFirst.agent()).isEqualTo("GM:gm");
            assertThat(readFirst.correlationId()).isEqualTo("corr-md");
            assertThat(readFirst.turnId()).isEqualTo("turn-md");
            assertThat(readFirst.eventVersion()).isEqualTo(EventContentStore.CURRENT_EVENT_VERSION);
            assertThat(readFirst.visibility()).isEqualTo(EventVisibility.PUBLIC);
            assertThat(readFirst.contentMissing()).isFalse();
            assertThat(readFirst.content().type()).isEqualTo(EventContentType.MARKDOWN);
            assertThat(readFirst.content().text()).isEqualTo("# 公开叙事\n\n第一段。");
            assertThat(readFirst.payloadJson()).isEqualTo("{\"kind\":\"public\"}");
            assertThat(readFirst.contentHash()).isEqualTo(sha256(readFirst.content().body()));

            StoredEvent readSecond = store.read(second.seq()).orElseThrow();
            assertThat(readSecond).isEqualTo(second);
            assertThat(readSecond.eventType()).isEqualTo("PLAYER_OUTCOME");
            assertThat(readSecond.agent()).isEqualTo("SECRETARY:sec-1");
            assertThat(readSecond.correlationId()).isEqualTo("corr-text");
            assertThat(readSecond.turnId()).isNull();
            assertThat(readSecond.eventVersion()).isEqualTo(EventContentStore.CURRENT_EVENT_VERSION);
            assertThat(readSecond.visibility()).isEqualTo(EventVisibility.PLAYER);
            assertThat(readSecond.contentMissing()).isFalse();
            assertThat(readSecond.content().type()).isEqualTo(EventContentType.TEXT);
            assertThat(readSecond.content().text()).isEqualTo("玩家私密结果");
            assertThat(readSecond.payloadJson()).isEqualTo("{\"kind\":\"private\"}");
            assertThat(readSecond.contentHash()).isEqualTo(sha256(readSecond.content().body()));

            assertThat(store.readAfter(0, 1)).extracting(StoredEvent::seq)
                    .containsExactly(first.seq());
            assertThat(store.readAfter(first.seq(), 1)).extracting(StoredEvent::seq)
                    .containsExactly(second.seq());
            assertThat(store.readAfter(first.seq(), 10)).extracting(StoredEvent::seq)
                    .containsExactly(second.seq());
            assertThat(store.readAfter(second.seq(), 10)).isEmpty();
        }
    }

    @Test
    void oldAgentLibSchemaIsMigratedReadOnlyAndStillReadableBySqliteReadPort() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        createLegacyDatabase(db);

        long appendedSeq;
        try (EventContentStore store = new EventContentStore(db)) {
            assertThat(store.schemaVersion()).isEqualTo(1);
            assertThat(store.schemaVersion()).isEqualTo(EventSchemaMigrator.SCHEMA_VERSION);

            StoredEvent legacy = store.read(1).orElseThrow();
            assertThat(legacy.contentMissing()).isTrue();
            assertThat(legacy.content()).isNull();
            assertThat(legacy.eventVersion()).isZero();
            assertThat(legacy.visibility()).isEqualTo(EventVisibility.GM);
            assertThat(legacy.payloadJson()).contains("legacy-key").contains("legacy");
            assertThat(legacy.eventType()).isEqualTo("taskflow.event");

            StoredEvent appended = store.append(jsonRequest(
                    "{\"body\":\"new\"}", "{\"new\":true}"));
            appendedSeq = appended.seq();
            assertThat(appendedSeq).isGreaterThan(legacy.seq());
            assertThat(store.read(appendedSeq).orElseThrow().content()).isNotNull();
        }

        // 迁移后的原始行必须保留 v0/GM/content_missing 标记，且 payload_json 已从
        // 旧 payload 回填；同时旧事件仍可由现有只读审计端口按类型游标读取。
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT event_version, visibility, content_missing, payload, payload_json
                     FROM events WHERE seq = 1
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("event_version")).isZero();
            assertThat(rs.getString("visibility")).isEqualTo("GM");
            assertThat(rs.getInt("content_missing")).isEqualTo(1);
            assertThat(rs.getString("payload_json")).isEqualTo(rs.getString("payload"));
            try (Statement pragma = connection.createStatement();
                 ResultSet version = pragma.executeQuery("PRAGMA user_version")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getInt(1)).isEqualTo(1);
            }
        }

        try (SqliteEventReadPort port = new SqliteEventReadPort(db)) {
            List<TaskflowEvent> events = port.readAfter(0, 10);
            assertThat(events).hasSize(1);
            TaskflowEvent legacy = events.get(0);
            assertThat(legacy.seq()).isEqualTo(1);
            assertThat(legacy.type().name()).isEqualTo("TURN_OPENED");
            assertThat(legacy.idempotencyKey()).isEqualTo("legacy-key");
            assertThat(legacy.turnId()).isEqualTo("turn-legacy");
            assertThat(legacy.payload()).containsEntry("note", "legacy");
            assertThat(port.lastStoredSeq()).isEqualTo(appendedSeq);
        }
    }

    @Test
    void tamperedContentHashFailsClosedOnRead() throws Exception {
        Path db = tempDir.resolve("tampered.db");
        long seq;
        try (EventContentStore store = new EventContentStore(db)) {
            seq = store.append(request(EventContentType.TEXT, null, "original body", "{}")).seq();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE events SET content_hash = 'not-the-hash' WHERE seq = " + seq);
        }

        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.read(seq))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_CONTENT_HASH_MISMATCH]");
        }
    }

    @Test
    void tamperedBodyFailsClosedOnRead() throws Exception {
        Path db = tempDir.resolve("tampered-body.db");
        long seq;
        try (EventContentStore store = new EventContentStore(db)) {
            seq = store.append(request(EventContentType.TEXT, null, "original body", "{}")).seq();
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE event_contents SET content_text = 'tampered body' WHERE seq = " + seq);
        }

        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.read(seq))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_CONTENT_HASH_MISMATCH]");
        }
    }

    @Test
    void invalidContentJsonIsRejectedBeforeAnyRowIsWritten() {
        Path db = tempDir.resolve("invalid-json.db");
        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.append(jsonRequest("{not-json", "{}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[EVENT_JSON_INVALID]");
            assertThat(store.readAfter(0, 10)).isEmpty();
        }
    }

    @Test
    void invalidMetadataJsonIsRejectedBeforeAnyRowIsWritten() {
        Path db = tempDir.resolve("invalid-metadata-json.db");
        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, "valid body", "{not-json")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[EVENT_JSON_INVALID]");
            assertThat(store.readAfter(0, 10)).isEmpty();

            // payload_json 必须是 JSON 对象，数组/标量同样在写库前拒绝。
            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, "valid body", "[1,2,3]")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[EVENT_JSON_OBJECT_REQUIRED]");
            assertThat(store.readAfter(0, 10)).isEmpty();
        }
    }

    @Test
    void oversizedContentAndPayloadAreRejectedBeforeAnyRowIsWritten() {
        Path db = tempDir.resolve("too-large.db");
        String oversizedContent = "x".repeat(EventContentStore.MAX_CONTENT_BYTES + 1);
        String oversizedPayload = "{\"blob\":\"" + "x".repeat(EventContentStore.MAX_PAYLOAD_BYTES) + "\"}";
        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, oversizedContent, "{}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[EVENT_CONTENT_TOO_LARGE]");
            assertThat(store.readAfter(0, 10)).isEmpty();

            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, "small body", oversizedPayload)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[EVENT_CONTENT_TOO_LARGE]");
            assertThat(store.readAfter(0, 10)).isEmpty();
        }
    }

    @Test
    void contentInsertFailureRollsBackHeaderInsert() throws Exception {
        Path db = tempDir.resolve("rollback.db");
        try (EventContentStore ignored = new EventContentStore(db)) {
            // 创建 schema。
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_content_insert
                    BEFORE INSERT ON event_contents
                    BEGIN
                      SELECT RAISE(ABORT, 'forced content failure');
                    END
                    """);
        }

        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, "body", "{}")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_DB_APPEND]");
            assertThat(store.readAfter(0, 10)).isEmpty();
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM events")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void sensitiveKeysAndKnownSecretsNeverReachDatabase() throws Exception {
        Path db = tempDir.resolve("secrets.db");
        String secret = "sk-p1-raw-leak-check";
        try (EventContentStore store = new EventContentStore(db, List.of(secret))) {
            StoredEvent json = store.append(new EventAppendRequest(
                    NOW, "EVT-" + secret, "GM:" + secret, "corr-" + secret, "turn-" + secret,
                    EventContentStore.CURRENT_EVENT_VERSION, EventVisibility.GM,
                    new EventContent(EventContentType.JSON,
                            "{\"token\":\"" + secret + "\",\"nested\":{\"api_key\":\""
                                    + secret + "\"},\"note\":\"visible " + secret + "\"}", null),
                    "{\"authorization\":\"" + secret + "\",\"note\":\"visible " + secret + "\"}"));
            StoredEvent markdown = store.append(request(EventContentType.MARKDOWN, null,
                    "# 标题\n\n含密钥 " + secret, "{\"note\":\"visible " + secret + "\"}"));
            StoredEvent text = store.append(request(EventContentType.TEXT, null,
                    "纯文本含密钥 " + secret, "{\"ck\":\"" + secret + "\"}"));

            for (long seq : List.of(json.seq(), markdown.seq(), text.seq())) {
                StoredEvent read = store.read(seq).orElseThrow();
                assertThat(read.content().body()).doesNotContain(secret).contains("[REDACTED]");
                assertThat(read.payloadJson()).doesNotContain(secret).contains("[REDACTED]");
            }
            StoredEvent readJson = store.read(json.seq()).orElseThrow();
            assertThat(readJson.eventType()).doesNotContain(secret).contains("[REDACTED]");
            assertThat(readJson.agent()).doesNotContain(secret).contains("[REDACTED]");
            assertThat(readJson.correlationId()).doesNotContain(secret).contains("[REDACTED]");
            assertThat(readJson.turnId()).doesNotContain(secret).contains("[REDACTED]");
        }

        StringBuilder persisted = new StringBuilder();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT e.type, e.agent, e.correlation_id, e.turn_id,
                            e.payload, e.payload_json,
                            c.content_json, c.content_text
                     FROM events e LEFT JOIN event_contents c ON c.seq = e.seq
                     """)) {
            while (rs.next()) {
                for (String column : List.of("type", "agent", "correlation_id", "turn_id",
                        "payload", "payload_json", "content_json", "content_text")) {
                    String value = rs.getString(column);
                    if (value != null) {
                        persisted.append(value).append('\n');
                    }
                }
            }
        }
        assertThat(persisted.toString()).contains("[REDACTED]").doesNotContain(secret);
    }

    @Test
    void overlappingSecretsUseLongestFirstAndAreOrderIndependent() throws Exception {
        String shortSecret = "abc";
        String longSecret = "abcdef";

        StoredEvent firstText;
        StoredEvent firstJson;
        StoredEvent secondText;
        StoredEvent secondJson;
        try (EventContentStore first = new EventContentStore(
                tempDir.resolve("overlap-first.db"), List.of(shortSecret, longSecret));
             EventContentStore second = new EventContentStore(
                     tempDir.resolve("overlap-second.db"), List.of(longSecret, shortSecret))) {
            firstText = first.append(overlapRequest(
                    EventContentType.TEXT, null, "前缀 abcdef 后缀"));
            firstJson = first.append(overlapRequest(
                    EventContentType.JSON, "{\"value\":\"abcdef\"}", null));
            secondText = second.append(overlapRequest(
                    EventContentType.TEXT, null, "前缀 abcdef 后缀"));
            secondJson = second.append(overlapRequest(
                    EventContentType.JSON, "{\"value\":\"abcdef\"}", null));
        }

        for (StoredEvent event : List.of(firstText, firstJson, secondText, secondJson)) {
            assertThat(event.content().body())
                    .doesNotContain(shortSecret)
                    .doesNotContain(longSecret)
                    .doesNotContain("def")
                    .contains("[REDACTED]");
            assertThat(event.payloadJson())
                    .doesNotContain(shortSecret)
                    .doesNotContain(longSecret)
                    .doesNotContain("def")
                    .contains("[REDACTED]");
            // header 字段同样写入 abcdef，必须只留完整占位符。
            assertThat(event.eventType()).isEqualTo("EVT-[REDACTED]");
            assertThat(event.agent()).isEqualTo("GM:[REDACTED]");
            assertThat(event.correlationId()).isEqualTo("corr-[REDACTED]");
            assertThat(event.turnId()).isEqualTo("turn-[REDACTED]");
        }

        assertThat(firstText.content().text()).isEqualTo("前缀 [REDACTED] 后缀");
        assertThat(firstJson.content().json()).isEqualTo("{\"value\":\"[REDACTED]\"}");
        // 两种 secret 顺序必须得到相同脱敏正文、payload 与 hash。
        assertThat(secondText.content().body()).isEqualTo(firstText.content().body());
        assertThat(secondJson.content().body()).isEqualTo(firstJson.content().body());
        assertThat(secondText.contentHash()).isEqualTo(firstText.contentHash());
        assertThat(secondJson.contentHash()).isEqualTo(firstJson.contentHash());
        assertThat(secondText.payloadJson()).isEqualTo(firstText.payloadJson());
        assertThat(secondJson.payloadJson()).isEqualTo(firstJson.payloadJson());
    }

    @Test
    void crossBoundaryOverlappingSecretsAreFullyMaskedRegardlessOfOrder() throws Exception {
        String leftSecret = "abc";
        String rightSecret = "bcdef";

        StoredEvent firstText;
        StoredEvent firstJson;
        StoredEvent secondText;
        StoredEvent secondJson;
        try (EventContentStore first = new EventContentStore(
                tempDir.resolve("cross-first.db"), List.of(leftSecret, rightSecret));
             EventContentStore second = new EventContentStore(
                     tempDir.resolve("cross-second.db"), List.of(rightSecret, leftSecret))) {
            firstText = first.append(overlapRequest(
                    EventContentType.TEXT, null, "abcdef"));
            firstJson = first.append(overlapRequest(
                    EventContentType.JSON, "{\"value\":\"abcdef\"}", null));
            secondText = second.append(overlapRequest(
                    EventContentType.TEXT, null, "abcdef"));
            secondJson = second.append(overlapRequest(
                    EventContentType.JSON, "{\"value\":\"abcdef\"}", null));
        }

        for (StoredEvent event : List.of(firstText, firstJson, secondText, secondJson)) {
            assertThat(event.content().body())
                    .doesNotContain(leftSecret)
                    .doesNotContain(rightSecret)
                    .doesNotContain("abcdef")
                    .doesNotContain("def")
                    .contains("[REDACTED]");
            assertThat(event.payloadJson())
                    .doesNotContain(leftSecret)
                    .doesNotContain(rightSecret)
                    .doesNotContain("abcdef")
                    .doesNotContain("def")
                    .contains("[REDACTED]");
            assertThat(event.eventType()).isEqualTo("EVT-[REDACTED]");
            assertThat(event.agent()).isEqualTo("GM:[REDACTED]");
            assertThat(event.correlationId()).isEqualTo("corr-[REDACTED]");
            assertThat(event.turnId()).isEqualTo("turn-[REDACTED]");
        }
        // abc 覆盖 0..3，bcdef 覆盖 1..6；并集必须只产生一个占位符。
        assertThat(firstText.content().text()).isEqualTo("[REDACTED]");
        assertThat(firstJson.content().json()).isEqualTo("{\"value\":\"[REDACTED]\"}");
        assertThat(secondText.content().body()).isEqualTo(firstText.content().body());
        assertThat(secondJson.content().body()).isEqualTo(firstJson.content().body());
        assertThat(secondText.contentHash()).isEqualTo(firstText.contentHash());
        assertThat(secondJson.contentHash()).isEqualTo(firstJson.contentHash());
        assertThat(secondText.payloadJson()).isEqualTo(firstText.payloadJson());
        assertThat(secondJson.payloadJson()).isEqualTo(firstJson.payloadJson());
    }

    @Test
    void complexAndAdjacentSecretOverlapsAreOrderIndependent() throws Exception {
        StoredEvent complexTextFirst;
        StoredEvent complexJsonFirst;
        StoredEvent complexTextSecond;
        StoredEvent complexJsonSecond;
        try (EventContentStore first = new EventContentStore(
                tempDir.resolve("complex-first.db"), List.of("abcd", "cdef", "efgh"));
             EventContentStore second = new EventContentStore(
                     tempDir.resolve("complex-second.db"), List.of("efgh", "cdef", "abcd"))) {
            complexTextFirst = first.append(request(
                    EventContentType.TEXT, null, "xxabcdefghyy", "{}"));
            complexJsonFirst = first.append(request(
                    EventContentType.JSON, "{\"value\":\"abcdefgh\"}", null, "{}"));
            complexTextSecond = second.append(request(
                    EventContentType.TEXT, null, "xxabcdefghyy", "{}"));
            complexJsonSecond = second.append(request(
                    EventContentType.JSON, "{\"value\":\"abcdefgh\"}", null, "{}"));
        }

        StoredEvent adjacentFirst;
        StoredEvent adjacentSecond;
        try (EventContentStore first = new EventContentStore(
                tempDir.resolve("adjacent-first.db"), List.of("abc", "def"));
             EventContentStore second = new EventContentStore(
                     tempDir.resolve("adjacent-second.db"), List.of("def", "abc"))) {
            adjacentFirst = first.append(request(
                    EventContentType.TEXT, null, "abcdef", "{}"));
            adjacentSecond = second.append(request(
                    EventContentType.TEXT, null, "abcdef", "{}"));
        }

        assertThat(complexTextFirst.content().text()).isEqualTo("xx[REDACTED]yy");
        assertThat(complexJsonFirst.content().json()).isEqualTo("{\"value\":\"[REDACTED]\"}");
        // 相邻区间 abc + def 也应合并成一个完整占位符，不留下任何片段。
        assertThat(adjacentFirst.content().text()).isEqualTo("[REDACTED]");
        assertThat(adjacentFirst.content().text()).doesNotContain("abc").doesNotContain("def");

        assertThat(complexTextSecond.content().body())
                .isEqualTo(complexTextFirst.content().body());
        assertThat(complexJsonSecond.content().body())
                .isEqualTo(complexJsonFirst.content().body());
        assertThat(adjacentSecond.content().body())
                .isEqualTo(adjacentFirst.content().body());
        assertThat(complexTextSecond.contentHash()).isEqualTo(complexTextFirst.contentHash());
        assertThat(complexJsonSecond.contentHash()).isEqualTo(complexJsonFirst.contentHash());
        assertThat(adjacentSecond.contentHash()).isEqualTo(adjacentFirst.contentHash());
    }

    @Test
    void redactionExpansionStillEnforcesContentSizeLimitBeforeWrite() {
        Path db = tempDir.resolve("redaction-expansion.db");
        // 用 a + 分隔符交替，避免连续命中被合并成一个掩码区间；每个 a 单独扩成
        // 10 字节 [REDACTED]。原文 800000 字节未超 4MiB，脱敏后 4.4MB 才超限。
        String body = "a.".repeat(400_000);
        assertThat(body.length()).isLessThan(EventContentStore.MAX_CONTENT_BYTES);
        try (EventContentStore store = new EventContentStore(db, List.of("a"))) {
            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, body, "{}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[EVENT_CONTENT_TOO_LARGE]");
            assertThat(store.readAfter(0, 10)).isEmpty();
        }
    }

    @Test
    void legacyMigrationIsIdempotentAcrossThreeConsecutiveOpens() throws Exception {
        Path db = tempDir.resolve("legacy-three-opens.db");
        createLegacyDatabase(db);

        String originalPayloadJson = null;
        for (int open = 1; open <= 3; open++) {
            try (EventContentStore store = new EventContentStore(db)) {
                assertThat(store.schemaVersion()).isEqualTo(1);
                StoredEvent legacy = store.read(1).orElseThrow();
                assertThat(legacy.eventVersion()).isZero();
                assertThat(legacy.visibility()).isEqualTo(EventVisibility.GM);
                assertThat(legacy.contentMissing()).isTrue();
                assertThat(legacy.content()).isNull();
                if (originalPayloadJson == null) {
                    originalPayloadJson = legacy.payloadJson();
                } else {
                    assertThat(legacy.payloadJson()).isEqualTo(originalPayloadJson);
                }
            }

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 Statement statement = connection.createStatement()) {
                try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM events")) {
                    assertThat(count.next()).isTrue();
                    assertThat(count.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rs = statement.executeQuery("""
                        SELECT event_version, visibility, content_missing, payload, payload_json
                        FROM events WHERE seq = 1
                        """)) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("event_version")).isZero();
                    assertThat(rs.getString("visibility")).isEqualTo("GM");
                    assertThat(rs.getInt("content_missing")).isEqualTo(1);
                    assertThat(rs.getString("payload_json"))
                            .isEqualTo(rs.getString("payload"))
                            .isEqualTo(originalPayloadJson);
                }
                try (ResultSet version = statement.executeQuery("PRAGMA user_version")) {
                    assertThat(version.next()).isTrue();
                    assertThat(version.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void transactionRecoversAfterContentInsertFailureAndNextAppendSucceeds() throws Exception {
        Path db = tempDir.resolve("rollback-recover.db");
        try (EventContentStore ignored = new EventContentStore(db)) {
            // 创建 schema。
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            // 只让特定正文失败，以便同一 store 上验证事务恢复后下一次 append 可成功。
            statement.execute("""
                    CREATE TRIGGER fail_specific_content_insert
                    BEFORE INSERT ON event_contents
                    WHEN NEW.content_text = 'fail-me'
                    BEGIN
                      SELECT RAISE(ABORT, 'forced content failure');
                    END
                    """);
        }

        try (EventContentStore store = new EventContentStore(db)) {
            assertThatThrownBy(() -> store.append(request(
                    EventContentType.TEXT, null, "fail-me", "{}")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[EVENT_DB_APPEND]");
            assertThat(store.readAfter(0, 10)).isEmpty();

            StoredEvent recovered = store.append(
                    request(EventContentType.TEXT, null, "recovered", "{\"ok\":true}"));
            assertThat(recovered.seq()).isPositive();
            assertThat(recovered.content().text()).isEqualTo("recovered");
            assertThat(recovered.contentMissing()).isFalse();

            List<StoredEvent> all = store.readAfter(0, 10);
            assertThat(all).hasSize(1);
            assertThat(all.get(0).seq()).isEqualTo(recovered.seq());
            assertThat(all.get(0).content().text()).isEqualTo("recovered");
            assertThat(all.get(0).contentHash()).isEqualTo(recovered.contentHash());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT (SELECT COUNT(*) FROM events) AS headers, "
                             + "(SELECT COUNT(*) FROM event_contents) AS contents")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("headers")).isEqualTo(1);
            assertThat(rs.getInt("contents")).isEqualTo(1);
        }
    }

    @Test
    void unsupportedSchemaVersionFailsClosed() throws Exception {
        Path db = tempDir.resolve("future-schema.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }

        assertThatThrownBy(() -> new EventContentStore(db))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[EVENT_SCHEMA_UNSUPPORTED]");
    }

    @Test
    void databaseAndWalCompanionsUseOwnerOnlyPermissionsWhenPosixIsAvailable() throws Exception {
        Path db = tempDir.resolve("permissions.db");
        try (EventContentStore store = new EventContentStore(db)) {
            store.append(request(EventContentType.TEXT, null, "body", "{}"));
            if (Files.getFileStore(db).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(db))
                        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                for (String suffix : List.of("-wal", "-shm")) {
                    Path companion = db.resolveSibling(db.getFileName() + suffix);
                    if (Files.exists(companion)) {
                        assertThat(Files.getPosixFilePermissions(companion))
                                .as("SQLite companion %s permissions", suffix)
                                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                    }
                }
            }
        }
    }

    private static EventAppendRequest jsonRequest(String json, String payloadJson) {
        return new EventAppendRequest(
                NOW, "POST_DRAFTED", "GM:gm", "corr-1", "turn-1",
                EventContentStore.CURRENT_EVENT_VERSION, EventVisibility.GM,
                new EventContent(EventContentType.JSON, json, null), payloadJson);
    }

    private static EventAppendRequest overlapRequest(EventContentType type, String json,
                                                     String text) {
        return new EventAppendRequest(
                NOW, "EVT-abcdef", "GM:abcdef", "corr-abcdef", "turn-abcdef",
                EventContentStore.CURRENT_EVENT_VERSION, EventVisibility.GM,
                new EventContent(type, json, text), "{\"note\":\"abcdef\"}");
    }

    private static EventAppendRequest request(EventContentType type, String json,
                                              String text, String payloadJson) {
        return new EventAppendRequest(
                NOW, "POST_PUBLISHED", "GM:gm", "corr-1", "turn-1",
                EventContentStore.CURRENT_EVENT_VERSION, EventVisibility.GM,
                new EventContent(type, json, text), payloadJson);
    }

    private static String sha256(String body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void createLegacyDatabase(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE events (
                      seq            INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts             TEXT    NOT NULL,
                      type           TEXT    NOT NULL,
                      agent          TEXT    NOT NULL,
                      payload        TEXT    NOT NULL DEFAULT '',
                      correlation_id TEXT    NOT NULL DEFAULT ''
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO events (ts, type, agent, payload, correlation_id) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, NOW.toString());
                insert.setString(2, "taskflow.event");
                insert.setString(3, "GM:gm");
                insert.setString(4, """
                        {"schemaVersion":1,"seq":1,"domainSeq":1,"type":"TURN_OPENED",
                         "eventType":"TURN_OPENED","actorRole":"GM","actorId":"gm",
                         "turnId":"turn-legacy","idempotencyKey":"legacy-key",
                         "occurredAt":"2026-10-24T05:00:00Z","payload":{"note":"legacy"}}
                        """);
                insert.setString(5, "turn-legacy");
                insert.executeUpdate();
            }
        }
    }
}
