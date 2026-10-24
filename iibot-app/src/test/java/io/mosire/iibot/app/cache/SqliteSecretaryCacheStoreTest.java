package io.mosire.iibot.app.cache;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SqliteSecretaryCacheStore 的持久化、owner 隔离与损坏 fail-closed 测试。 */
class SqliteSecretaryCacheStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsRawMessagesAndActiveCacheAcrossReopen() {
        Path root = tempDir.resolve("secretary-cache");
        String cacheId;
        try (SqliteSecretaryCacheStore store = SqliteSecretaryCacheStore.open(root)) {
            CacheSummary created = store.ensureDefaultCache("player-1");
            cacheId = created.cacheId();
            store.appendMessages("player-1", cacheId, List.of(
                    LlmMessage.user("你好"),
                    LlmMessage.assistant(List.of(new ContentPart.Text("你好，我在。")))));
        }

        try (SqliteSecretaryCacheStore reopened = SqliteSecretaryCacheStore.open(root)) {
            CacheSummary active = reopened.ensureDefaultCache("player-1");
            assertThat(active.cacheId()).isEqualTo(cacheId);
            assertThat(active.messageCount()).isEqualTo(2);
            List<LlmMessage> messages = reopened.loadMessages("player-1", cacheId);
            assertThat(messages).extracting(LlmMessage::role)
                    .containsExactly(LlmMessage.ROLE_USER, LlmMessage.ROLE_ASSISTANT);
            assertThat(messages.getFirst().content().getFirst())
                    .isEqualTo(new ContentPart.Text("你好"));
        }
    }

    @Test
    void preservesToolCallsAndResultsInRawCache() {
        Path root = tempDir.resolve("secretary-cache");
        try (SqliteSecretaryCacheStore store = SqliteSecretaryCacheStore.open(root)) {
            CacheSummary cache = store.ensureDefaultCache("player-1");
            store.appendMessages("player-1", cache.cacheId(), List.of(
                    LlmMessage.assistant(List.of(new ContentPart.ToolCall(
                            "call-1", "secretary_workspace_read",
                            Map.of("path", "AGENTS.md")))),
                    LlmMessage.tool(new ContentPart.ToolResult(
                            "call-1", "secretary_workspace_read", "ok", null))));

            List<LlmMessage> messages = store.loadMessages("player-1", cache.cacheId());
            assertThat(messages).hasSize(2);
            assertThat(messages.getFirst().content().getFirst())
                    .isInstanceOf(ContentPart.ToolCall.class);
            assertThat(messages.get(1).content().getFirst())
                    .isInstanceOf(ContentPart.ToolResult.class);
        }
    }

    @Test
    void createsAndSwitchesIndependentCachesWithoutChangingOtherPlayer() {
        Path root = tempDir.resolve("secretary-cache");
        try (SqliteSecretaryCacheStore store = SqliteSecretaryCacheStore.open(root)) {
            CacheSummary first = store.ensureDefaultCache("player-1");
            CacheSummary second = store.createCache("player-1", "旧对话", "APP");
            store.appendMessages("player-1", first.cacheId(), List.of(LlmMessage.user("A")));
            store.appendMessages("player-1", second.cacheId(), List.of(LlmMessage.user("B")));

            store.setActiveCache("player-1", second.cacheId());

            assertThat(store.activeCacheId("player-1")).isEqualTo(second.cacheId());
            assertThat(store.listCaches("player-1"))
                    .filteredOn(CacheSummary::active)
                    .extracting(CacheSummary::cacheId)
                    .containsExactly(second.cacheId());
            assertThat(store.loadMessages("player-1", first.cacheId()).getFirst().content()
                    .getFirst()).isEqualTo(new ContentPart.Text("A"));
            assertThat(store.loadMessages("player-1", second.cacheId()).getFirst().content()
                    .getFirst()).isEqualTo(new ContentPart.Text("B"));

            CacheSummary other = store.ensureDefaultCache("player-2");
            assertThat(other.cacheId()).isNotIn(first.cacheId(), second.cacheId());
            assertThat(store.findCache("player-2", first.cacheId())).isEmpty();
            assertThatThrownBy(() -> store.setActiveCache("player-2", first.cacheId()))
                    .isInstanceOf(SecretaryCacheException.class)
                    .satisfies(error -> assertThat(((SecretaryCacheException) error).code())
                            .isEqualTo(SecretaryCacheException.CACHE_NOT_FOUND));
        }
    }

    @Test
    void corruptedIndexFailsClosedInsteadOfCreatingSilentEmptyCache() throws Exception {
        Path root = tempDir.resolve("secretary-cache");
        try (SqliteSecretaryCacheStore ignored = SqliteSecretaryCacheStore.open(root)) {
            // 只创建目录，随后写入损坏索引。
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("player-1".getBytes(StandardCharsets.UTF_8));
        Path playerDir = root.resolve(encoded);
        Files.createDirectories(playerDir);
        Files.writeString(playerDir.resolve("index.json"), "{not-json");

        try (SqliteSecretaryCacheStore store = SqliteSecretaryCacheStore.open(root)) {
            assertThatThrownBy(() -> store.ensureDefaultCache("player-1"))
                    .isInstanceOf(SecretaryCacheException.class)
                    .satisfies(error -> assertThat(((SecretaryCacheException) error).code())
                            .isEqualTo(SecretaryCacheException.CACHE_UNAVAILABLE));
        }
    }
}
