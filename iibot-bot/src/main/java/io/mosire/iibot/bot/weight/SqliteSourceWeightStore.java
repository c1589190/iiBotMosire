package io.mosire.iibot.bot.weight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 与 bot.db 共库的来源权重策略和消息权重判定存储。 */
public final class SqliteSourceWeightStore implements SourceWeightStore, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<WeightSignal, Integer>> SIGNAL_MAP =
            new TypeReference<>() { };
    private static final TypeReference<Map<String, Integer>> KEYWORD_MAP =
            new TypeReference<>() { };

    private final Connection connection;
    private boolean closed;

    public SqliteSourceWeightStore(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS source_weight_policy (
                          thread_id TEXT PRIMARY KEY,
                          chat_type TEXT NOT NULL,
                          target_id TEXT NOT NULL,
                          source_weight INTEGER NOT NULL,
                          notify_threshold INTEGER NOT NULL,
                          signal_weights_json TEXT NOT NULL,
                          keyword_weights_json TEXT NOT NULL,
                          max_messages INTEGER NOT NULL,
                          quiet_millis INTEGER NOT NULL,
                          cooldown_millis INTEGER NOT NULL,
                          bootstrap_delivered INTEGER NOT NULL,
                          revision INTEGER NOT NULL,
                          updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS chat_message_weight (
                          message_key TEXT PRIMARY KEY,
                          thread_id TEXT NOT NULL,
                          source_weight INTEGER NOT NULL,
                          signal_weight INTEGER NOT NULL,
                          keyword_weight INTEGER NOT NULL,
                          total_score INTEGER NOT NULL,
                          wake_reason TEXT NOT NULL,
                          matched_signals_json TEXT NOT NULL,
                          matched_keywords_json TEXT NOT NULL,
                          actionable INTEGER NOT NULL,
                          bootstrap INTEGER NOT NULL,
                          policy_revision INTEGER NOT NULL,
                          decided_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_weight_thread
                        ON chat_message_weight(thread_id)
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开来源权重存储失败", failure);
        }
    }

    @Override
    public synchronized Optional<SourceWeightPolicy> findPolicy(String threadId) {
        ensureOpen();
        try {
            return findPolicyInternal(threadId);
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("读取来源权重策略失败", failure);
        }
    }

    @Override
    public synchronized SourceWeightPolicy savePolicy(SourceWeightPolicy policy,
                                                       Long expectedRevision) {
        ensureOpen();
        Objects.requireNonNull(policy, "policy");
        try {
            SourceWeightPolicy current = findPolicyInternal(policy.threadId()).orElse(null);
            long actual = current == null ? 0L : current.revision();
            if (expectedRevision != null && expectedRevision.longValue() != actual) {
                throw new SourceWeightConflictException(expectedRevision, actual);
            }
            long nextRevision = actual + 1L;
            SourceWeightPolicy stored = policy.withRevision(nextRevision, policy.updatedAt());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO source_weight_policy (
                      thread_id, chat_type, target_id, source_weight, notify_threshold,
                      signal_weights_json, keyword_weights_json, max_messages,
                      quiet_millis, cooldown_millis, bootstrap_delivered, revision, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(thread_id) DO UPDATE SET
                      chat_type = excluded.chat_type,
                      target_id = excluded.target_id,
                      source_weight = excluded.source_weight,
                      notify_threshold = excluded.notify_threshold,
                      signal_weights_json = excluded.signal_weights_json,
                      keyword_weights_json = excluded.keyword_weights_json,
                      max_messages = excluded.max_messages,
                      quiet_millis = excluded.quiet_millis,
                      cooldown_millis = excluded.cooldown_millis,
                      bootstrap_delivered = excluded.bootstrap_delivered,
                      revision = excluded.revision,
                      updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, stored.threadId());
                statement.setString(2, stored.chatType().wire());
                statement.setString(3, stored.targetId());
                statement.setInt(4, stored.sourceWeight());
                statement.setInt(5, stored.notifyThreshold());
                statement.setString(6, JSON.writeValueAsString(stored.signalWeights()));
                statement.setString(7, JSON.writeValueAsString(stored.keywordWeights()));
                statement.setInt(8, stored.maxMessages());
                statement.setLong(9, stored.quietMillis());
                statement.setLong(10, stored.cooldownMillis());
                statement.setInt(11, stored.bootstrapDelivered() ? 1 : 0);
                statement.setLong(12, stored.revision());
                statement.setString(13, stored.updatedAt().toString());
                statement.executeUpdate();
            }
            return stored;
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("保存来源权重策略失败", failure);
        }
    }

    @Override
    public synchronized List<SourceWeightPolicy> listPolicies(int limit) {
        ensureOpen();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT thread_id, chat_type, target_id, source_weight, notify_threshold,
                       signal_weights_json, keyword_weights_json, max_messages,
                       quiet_millis, cooldown_millis, bootstrap_delivered, revision, updated_at
                FROM source_weight_policy
                ORDER BY source_weight DESC, updated_at DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<SourceWeightPolicy> policies = new ArrayList<>();
                while (result.next()) {
                    policies.add(mapPolicy(result));
                }
                return List.copyOf(policies);
            }
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("列出来源权重策略失败", failure);
        }
    }

    @Override
    public synchronized Optional<MessageWeightDecision> findDecision(String messageKey) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT message_key, thread_id, source_weight, signal_weight, keyword_weight,
                       total_score, wake_reason, matched_signals_json, matched_keywords_json,
                       actionable, bootstrap, policy_revision, decided_at
                FROM chat_message_weight WHERE message_key = ?
                """)) {
            statement.setString(1, messageKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapDecision(result));
            }
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("读取消息权重失败", failure);
        }
    }

    @Override
    public synchronized void saveDecision(MessageWeightDecision decision) {
        ensureOpen();
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_message_weight (
                  message_key, thread_id, source_weight, signal_weight, keyword_weight,
                  total_score, wake_reason, matched_signals_json, matched_keywords_json,
                  actionable, bootstrap, policy_revision, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_key) DO UPDATE SET
                  thread_id = excluded.thread_id,
                  source_weight = excluded.source_weight,
                  signal_weight = excluded.signal_weight,
                  keyword_weight = excluded.keyword_weight,
                  total_score = excluded.total_score,
                  wake_reason = excluded.wake_reason,
                  matched_signals_json = excluded.matched_signals_json,
                  matched_keywords_json = excluded.matched_keywords_json,
                  actionable = excluded.actionable,
                  bootstrap = excluded.bootstrap,
                  policy_revision = excluded.policy_revision,
                  decided_at = excluded.decided_at
                """)) {
            statement.setString(1, decision.messageKey());
            statement.setString(2, decision.threadId());
            statement.setInt(3, decision.sourceWeight());
            statement.setInt(4, decision.signalWeight());
            statement.setInt(5, decision.keywordWeight());
            statement.setInt(6, decision.totalScore());
            statement.setString(7, decision.wakeReason().name());
            statement.setString(8, JSON.writeValueAsString(decision.matchedSignals()));
            statement.setString(9, JSON.writeValueAsString(decision.matchedKeywords()));
            statement.setInt(10, decision.actionable() ? 1 : 0);
            statement.setInt(11, decision.bootstrap() ? 1 : 0);
            statement.setLong(12, decision.policyRevision());
            statement.setString(13, decision.decidedAt().toString());
            statement.executeUpdate();
        } catch (SQLException | JsonProcessingException failure) {
            throw new IllegalStateException("保存消息权重失败", failure);
        }
    }

    private Optional<SourceWeightPolicy> findPolicyInternal(String threadId)
            throws SQLException, JsonProcessingException {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT thread_id, chat_type, target_id, source_weight, notify_threshold,
                       signal_weights_json, keyword_weights_json, max_messages,
                       quiet_millis, cooldown_millis, bootstrap_delivered, revision, updated_at
                FROM source_weight_policy WHERE thread_id = ?
                """)) {
            statement.setString(1, threadId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapPolicy(result)) : Optional.empty();
            }
        }
    }

    private static SourceWeightPolicy mapPolicy(ResultSet result)
            throws SQLException, JsonProcessingException {
        Map<WeightSignal, Integer> signals = JSON.readValue(
                result.getString("signal_weights_json"), SIGNAL_MAP);
        Map<String, Integer> keywords = JSON.readValue(
                result.getString("keyword_weights_json"), KEYWORD_MAP);
        return new SourceWeightPolicy(
                result.getString("thread_id"),
                io.mosire.iibot.bot.chat.ChatType.valueOf(
                        result.getString("chat_type").toUpperCase()),
                result.getString("target_id"),
                result.getInt("source_weight"),
                result.getInt("notify_threshold"),
                signals,
                keywords,
                result.getInt("max_messages"),
                result.getLong("quiet_millis"),
                result.getLong("cooldown_millis"),
                result.getInt("bootstrap_delivered") != 0,
                result.getLong("revision"),
                Instant.parse(result.getString("updated_at")));
    }

    private static MessageWeightDecision mapDecision(ResultSet result)
            throws SQLException, JsonProcessingException {
        List<WeightSignal> signals = JSON.readValue(
                result.getString("matched_signals_json"), new TypeReference<>() { });
        List<String> keywords = JSON.readValue(
                result.getString("matched_keywords_json"), new TypeReference<>() { });
        return new MessageWeightDecision(
                result.getString("message_key"),
                result.getString("thread_id"),
                result.getInt("source_weight"),
                result.getInt("signal_weight"),
                result.getInt("keyword_weight"),
                result.getInt("total_score"),
                WeightWakeReason.valueOf(result.getString("wake_reason")),
                signals,
                keywords,
                result.getInt("actionable") != 0,
                result.getInt("bootstrap") != 0,
                result.getLong("policy_revision"),
                Instant.parse(result.getString("decided_at")));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("来源权重存储已关闭");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("关闭来源权重存储失败", failure);
        }
    }
}
