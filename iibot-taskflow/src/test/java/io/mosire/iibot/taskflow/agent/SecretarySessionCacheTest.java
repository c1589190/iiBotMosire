package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.cache.CacheSummary;
import io.mosire.iibot.taskflow.cache.SecretaryCacheException;
import io.mosire.iibot.taskflow.cache.SecretaryCacheStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SecretarySession 的 cache 水合、落库、切换、玩家工具和 GM 隔离测试。 */
class SecretarySessionCacheTest {

    @Test
    void hydratesOldMessagesAndPersistsBothSidesOfNewTurn() {
        AgentTestFixture f = AgentTestFixture.create();
        InMemoryCacheStore store = new InMemoryCacheStore();
        CacheSummary cache = store.ensureDefaultCache("player-1");
        store.appendMessages("player-1", cache.cacheId(), List.of(
                LlmMessage.user("旧问题"),
                LlmMessage.assistant(List.of(new ContentPart.Text("旧回答")))));

        ScriptedLlm llm = ScriptedLlm.with(LlmResponse.text("新回答"));
        SecretarySession session = session(f, llm, store);

        assertThat(session.activeCacheId()).isEqualTo(cache.cacheId());
        assertThat(session.cacheStatus()).isEqualTo("READY");
        assertThat(session.chat("新问题")).isEqualTo("新回答");

        assertThat(llm.allPromptText())
                .contains("旧问题")
                .contains("旧回答")
                .contains("新问题");
        List<LlmMessage> persisted = store.loadMessages("player-1", cache.cacheId());
        assertThat(persisted).hasSize(4);
        assertThat(persisted.get(2).role()).isEqualTo(LlmMessage.ROLE_USER);
        assertThat(persisted.get(2).content().getFirst())
                .isEqualTo(new ContentPart.Text("新问题"));
        assertThat(persisted.get(3).role()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
    }

    @Test
    void switchesCacheAndUsesOnlyTargetHistory() {
        AgentTestFixture f = AgentTestFixture.create();
        InMemoryCacheStore store = new InMemoryCacheStore();
        CacheSummary first = store.ensureDefaultCache("player-1");
        CacheSummary second = store.createCache("player-1", "旧对话", "APP");
        store.appendMessages("player-1", first.cacheId(), List.of(
                LlmMessage.user("A-旧问题"),
                LlmMessage.assistant(List.of(new ContentPart.Text("A-旧回答")))));
        store.appendMessages("player-1", second.cacheId(), List.of(
                LlmMessage.user("B-旧问题"),
                LlmMessage.assistant(List.of(new ContentPart.Text("B-旧回答")))));

        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.text("A-新回答"), LlmResponse.text("B-新回答"));
        SecretarySession session = session(f, llm, store);
        assertThat(session.chat("A-新问题")).isEqualTo("A-新回答");
        assertThat(llm.allPromptText()).contains("A-旧问题").doesNotContain("B-旧问题");

        session.switchCache(second.cacheId());
        assertThat(session.activeCacheId()).isEqualTo(second.cacheId());
        assertThat(session.chat("B-新问题")).isEqualTo("B-新回答");

        assertThat(llm.requests()).hasSize(2);
        assertThat(llm.requests().get(1).messages().toString())
                .contains("B-旧问题")
                .doesNotContain("A-旧问题");
        assertThat(store.loadMessages("player-1", first.cacheId())).hasSize(4);
        assertThat(store.loadMessages("player-1", second.cacheId())).hasSize(4);
    }

    @Test
    void exposesOnlyOwnCachesAndRendersVisibleTranscript() {
        AgentTestFixture f = AgentTestFixture.create();
        InMemoryCacheStore store = new InMemoryCacheStore();
        CacheSummary own = store.ensureDefaultCache("player-1");
        CacheSummary other = store.ensureDefaultCache("player-2");
        store.appendMessages("player-1", own.cacheId(), List.of(
                LlmMessage.user("玩家可见"),
                LlmMessage.tool(new ContentPart.ToolResult(
                        "call-1", "tool", "工具内部正文", null)),
                LlmMessage.assistant(List.of(new ContentPart.Text("秘书回复")))));
        store.appendMessages("player-2", other.cacheId(), List.of(
                LlmMessage.user("其他玩家秘密")));

        SecretarySession session = session(f, ScriptedLlm.with(), store);

        assertThat(session.tools()).extracting(tool -> tool.name())
                .contains(SecretaryCacheTools.LIST, SecretaryCacheTools.READ);
        assertThat(session.permissions().isToolAllowed(SecretaryCacheTools.READ)).isTrue();
        assertThat(session.permissions().resourceScopes()
                .declaredScope(SecretaryCacheStore.RESOURCE_NAMESPACE)).isNotNull();
        assertThat(session.cacheSummaries()).extracting(CacheSummary::cacheId)
                .containsExactly(own.cacheId());

        String transcript = session.readCacheTranscript(null, 20);
        assertThat(transcript).contains("玩家可见", "秘书回复")
                .doesNotContain("工具内部正文", "其他玩家秘密");
        assertThatThrownBy(() -> session.readCacheTranscript(other.cacheId(), 20))
                .isInstanceOf(SecretaryCacheException.class)
                .satisfies(error -> assertThat(((SecretaryCacheException) error).code())
                        .isEqualTo(SecretaryCacheException.CACHE_NOT_FOUND));

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(session.tools());
        ToolContext context = new ToolContext(AccessToken.DEFAULT, session.permissions(),
                Map.of(), Map.of());
        ToolResult list = ToolCallAuthorizer.standard().execute(
                registry, SecretaryCacheTools.LIST, context);
        ToolResult read = ToolCallAuthorizer.standard().execute(
                registry, SecretaryCacheTools.READ,
                new ToolContext(AccessToken.DEFAULT, session.permissions(),
                        Map.of(), Map.of("limit", 20)));
        assertThat(list.success()).as(list.message()).isTrue();
        assertThat(read.success()).as(read.message()).isTrue();
        assertThat(list.message()).contains(own.cacheId());
        assertThat(read.message()).contains("玩家可见", "秘书回复")
                .doesNotContain("其他玩家秘密");
    }

    @Test
    void gmChatUsesTemporaryWindowAndDoesNotPollutePlayerCache() {
        AgentTestFixture f = AgentTestFixture.create();
        InMemoryCacheStore store = new InMemoryCacheStore();
        store.ensureDefaultCache("player-1");
        ScriptedLlm llm = ScriptedLlm.with(
                LlmResponse.text("GM 回复"), LlmResponse.text("玩家回复"));
        SecretarySession session = session(f, llm, store);

        assertThat(session.gmChat("GM 调试消息")).isEqualTo("GM 回复");
        assertThat(session.contextWindow().messages()).isEmpty();
        assertThat(store.loadMessages("player-1", session.activeCacheId())).isEmpty();

        assertThat(session.chat("玩家消息")).isEqualTo("玩家回复");
        assertThat(llm.requests().get(1).messages().toString())
                .contains("玩家消息")
                .doesNotContain("GM 调试消息");
        assertThat(store.loadMessages("player-1", session.activeCacheId()))
                .extracting(LlmMessage::role)
                .containsExactly(LlmMessage.ROLE_USER, LlmMessage.ROLE_ASSISTANT);
    }

    @Test
    void switchingToForeignCacheFailsWithoutChangingActiveCache() {
        AgentTestFixture f = AgentTestFixture.create();
        InMemoryCacheStore store = new InMemoryCacheStore();
        CacheSummary own = store.ensureDefaultCache("player-1");
        CacheSummary foreign = store.ensureDefaultCache("player-2");
        SecretarySession session = session(f, ScriptedLlm.with(), store);

        assertThatThrownBy(() -> session.switchCache(foreign.cacheId()))
                .isInstanceOf(SecretaryCacheException.class)
                .satisfies(error -> assertThat(((SecretaryCacheException) error).code())
                        .isEqualTo(SecretaryCacheException.CACHE_NOT_FOUND));
        assertThat(session.activeCacheId()).isEqualTo(own.cacheId());
    }

    private static SecretarySession session(AgentTestFixture f, ScriptedLlm llm,
                                            SecretaryCacheStore store) {
        return new SecretarySession("player-1", f.p1, f.engine, llm,
                f.noopBriefing(), f.director, f.clock,
                Duration.ofMinutes(15), Duration.ofMinutes(15),
                null, null, null, null, null, null, store);
    }

    /**
     * 测试专用内存实现。它与生产实现遵守同一 owner 约束和 raw LlmMessage 语义。
     */
    private static final class InMemoryCacheStore implements SecretaryCacheStore {

        private final Map<String, LinkedHashMap<String, CacheEntry>> byPlayer =
                new LinkedHashMap<>();
        private final Map<String, String> active = new LinkedHashMap<>();
        private int sequence;

        @Override
        public CacheSummary ensureDefaultCache(String playerId) {
            LinkedHashMap<String, CacheEntry> caches = byPlayer.computeIfAbsent(
                    playerId, ignored -> new LinkedHashMap<>());
            String activeId = active.get(playerId);
            if (activeId == null || !caches.containsKey(activeId)) {
                if (caches.isEmpty()) {
                    CacheEntry created = createEntry(playerId, "默认对话");
                    caches.put(created.cacheId, created);
                    active.put(playerId, created.cacheId);
                } else {
                    active.put(playerId, caches.keySet().iterator().next());
                }
            }
            return summary(playerId, caches.get(active.get(playerId)));
        }

        @Override
        public CacheSummary createCache(String playerId, String title, String source) {
            LinkedHashMap<String, CacheEntry> caches = byPlayer.computeIfAbsent(
                    playerId, ignored -> new LinkedHashMap<>());
            CacheEntry created = createEntry(playerId, title);
            caches.put(created.cacheId, created);
            active.putIfAbsent(playerId, created.cacheId);
            return summary(playerId, created);
        }

        @Override
        public List<CacheSummary> listCaches(String playerId) {
            return byPlayer.getOrDefault(playerId, new LinkedHashMap<>()).values().stream()
                    .sorted(Comparator.comparing(entry -> entry.cacheId))
                    .map(entry -> summary(playerId, entry))
                    .toList();
        }

        @Override
        public Optional<CacheSummary> findCache(String playerId, String cacheId) {
            CacheEntry entry = byPlayer.getOrDefault(playerId, new LinkedHashMap<>())
                    .get(cacheId);
            return entry == null ? Optional.empty() : Optional.of(summary(playerId, entry));
        }

        @Override
        public String activeCacheId(String playerId) {
            return active.get(playerId);
        }

        @Override
        public void setActiveCache(String playerId, String cacheId) {
            if (!byPlayer.getOrDefault(playerId, new LinkedHashMap<>()).containsKey(cacheId)) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_NOT_FOUND,
                        "cache 不存在");
            }
            active.put(playerId, cacheId);
        }

        @Override
        public List<LlmMessage> loadMessages(String playerId, String cacheId) {
            CacheEntry entry = byPlayer.getOrDefault(playerId, new LinkedHashMap<>())
                    .get(cacheId);
            if (entry == null) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_NOT_FOUND,
                        "cache 不存在");
            }
            return List.copyOf(entry.messages);
        }

        @Override
        public void appendMessages(String playerId, String cacheId,
                                   List<LlmMessage> messages) {
            CacheEntry entry = byPlayer.getOrDefault(playerId, new LinkedHashMap<>())
                    .get(cacheId);
            if (entry == null) {
                throw new SecretaryCacheException(SecretaryCacheException.CACHE_NOT_FOUND,
                        "cache 不存在");
            }
            entry.messages.addAll(messages);
        }

        private CacheEntry createEntry(String playerId, String title) {
            String cacheId = "cache-" + (++sequence);
            return new CacheEntry(cacheId, playerId, title);
        }

        private CacheSummary summary(String playerId, CacheEntry entry) {
            return new CacheSummary(entry.cacheId, playerId, entry.title, "APP",
                    "2026-10-24T00:00:00Z", "2026-10-24T00:00:00Z",
                    entry.messages.size(), entry.cacheId.equals(active.get(playerId)));
        }

        private static final class CacheEntry {
            private final String cacheId;
            private final String playerId;
            private final String title;
            private final List<LlmMessage> messages = new ArrayList<>();

            private CacheEntry(String cacheId, String playerId, String title) {
                this.cacheId = cacheId;
                this.playerId = playerId;
                this.title = title;
            }
        }
    }
}
