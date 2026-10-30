package io.mosire.iibot.bot.store;

import io.mosire.iibot.bot.chat.ChatDirection;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatThread;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.connect.core.MessageSegment;

import java.io.IOException;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite 消息板。
 *
 * <p>数据库只保存 Bot 通用消息事实；入站消息以 messageKey 去重，出站消息以幂等键
 * 派生 messageKey。localSeq 由 SQLite 分配，是 thread 内排序的唯一依据。</p>
 */
public final class SqliteMessageBoardStore implements MessageBoardStore, AutoCloseable {

    private static final String MESSAGE_COLUMNS = """
            local_seq, message_key, thread_id, account_id, chat_type, target_id,
            direction, sender_id, sender_name, message_id, sent_at, received_at,
            text, non_text_summary, reply_to_message_id, delivery_state, idempotency_key
            """;

    private final Connection connection;
    private boolean closed;

    public SqliteMessageBoardStore(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS chat_message (
                          local_seq INTEGER PRIMARY KEY AUTOINCREMENT,
                          message_key TEXT NOT NULL UNIQUE,
                          thread_id TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          chat_type TEXT NOT NULL,
                          target_id TEXT NOT NULL,
                          direction TEXT NOT NULL,
                          sender_id TEXT NOT NULL,
                          sender_name TEXT NOT NULL,
                          message_id TEXT NOT NULL,
                          sent_at TEXT,
                          received_at TEXT NOT NULL,
                          text TEXT NOT NULL,
                          non_text_summary TEXT NOT NULL,
                          reply_to_message_id TEXT NOT NULL,
                          delivery_state TEXT NOT NULL,
                          idempotency_key TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS chat_thread (
                          thread_id TEXT PRIMARY KEY,
                          account_id TEXT NOT NULL,
                          chat_type TEXT NOT NULL,
                          target_id TEXT NOT NULL,
                          display_name TEXT NOT NULL,
                          last_seq INTEGER NOT NULL,
                          message_count INTEGER NOT NULL,
                          last_message_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS chat_message_segment (
                          message_key TEXT NOT NULL,
                          segment_index INTEGER NOT NULL,
                          type TEXT NOT NULL,
                          text TEXT NOT NULL,
                          qq_id TEXT NOT NULL,
                          target_message_id TEXT NOT NULL,
                          file_id TEXT NOT NULL,
                          url TEXT NOT NULL,
                          media_type TEXT NOT NULL,
                          byte_size INTEGER NOT NULL,
                          forward_id TEXT NOT NULL,
                          raw_type TEXT NOT NULL,
                          PRIMARY KEY (message_key, segment_index)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_thread_seq
                        ON chat_message(thread_id, local_seq)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_thread_message_id
                        ON chat_message(thread_id, message_id)
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite 消息板失败", failure);
        }
    }

    @Override
    public synchronized MessageAppendResult append(ChatMessage message) {
        return append(message, List.of());
    }

    @Override
    public synchronized MessageAppendResult append(ChatMessage message,
                                                   List<MessageSegment> segments) {
        Objects.requireNonNull(message, "message");
        ensureOpen();
        if (message.localSeq() != 0L) {
            throw new IllegalArgumentException("append 只接受尚未入库的消息（localSeq=0）");
        }
        try {
            connection.setAutoCommit(false);
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_message (
                      message_key, thread_id, account_id, chat_type, target_id,
                      direction, sender_id, sender_name, message_id, sent_at, received_at,
                      text, non_text_summary, reply_to_message_id, delivery_state,
                      idempotency_key
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(message_key) DO NOTHING
                    """)) {
                bindMessage(statement, message);
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                ChatMessage existing = findInternal(message.messageKey())
                        .orElseThrow(() -> new IllegalStateException("重复消息未找到已有记录"));
                connection.rollback();
                return new MessageAppendResult(existing, false);
            }
            long seq = lastInsertSeq();
            ChatMessage stored = message.withLocalSeq(seq);
            updateThread(stored);
            insertSegments(stored.messageKey(), segments);
            connection.commit();
            return new MessageAppendResult(stored, true);
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("写入 SQLite 消息板失败", failure);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized Optional<ChatMessage> find(String messageKey) {
        ensureOpen();
        if (messageKey == null || messageKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return findInternal(messageKey);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 消息板失败", failure);
        }
    }

    @Override
    public synchronized ChatMessage updateDelivery(String messageKey, DeliveryState state,
                                                   String externalMessageId, Instant sentAt) {
        ensureOpen();
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("messageKey 不能为空");
        }
        if (state == null || state == DeliveryState.NONE) {
            throw new IllegalArgumentException("出站消息必须使用有效投递状态");
        }
        try {
            connection.setAutoCommit(false);
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE chat_message
                    SET delivery_state = ?,
                        message_id = CASE
                          WHEN ? IS NULL OR ? = '' THEN message_id
                          ELSE ?
                        END,
                        sent_at = COALESCE(?, sent_at)
                    WHERE message_key = ? AND direction = 'OUT'
                    """)) {
                statement.setString(1, state.name());
                statement.setString(2, externalMessageId);
                statement.setString(3, externalMessageId);
                statement.setString(4, externalMessageId);
                statement.setString(5, sentAt == null ? null : sentAt.toString());
                statement.setString(6, messageKey);
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                connection.rollback();
                throw new IllegalArgumentException("出站消息不存在或不是 OUT: " + messageKey);
            }
            ChatMessage result = findInternal(messageKey)
                    .orElseThrow(() -> new IllegalStateException("更新后未找到出站消息"));
            connection.commit();
            return result;
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("更新 SQLite 投递状态失败", failure);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized List<ChatMessage> readThread(String threadId, long afterSeq, int limit) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (afterSeq < 0L) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM chat_message
                WHERE thread_id = ? AND local_seq > ?
                ORDER BY local_seq ASC
                LIMIT ?
                """.formatted(MESSAGE_COLUMNS))) {
            statement.setString(1, threadId);
            statement.setLong(2, afterSeq);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ChatMessage> messages = new ArrayList<>();
                while (result.next()) {
                    messages.add(mapMessage(result));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite thread 失败", failure);
        }
    }

    @Override
    public synchronized List<ChatMessage> readInboundThread(
            String threadId, long afterSeq, int limit) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (afterSeq < 0L) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM chat_message
                WHERE thread_id = ? AND local_seq > ? AND direction = 'IN'
                ORDER BY local_seq ASC
                LIMIT ?
                """.formatted(MESSAGE_COLUMNS))) {
            statement.setString(1, threadId);
            statement.setLong(2, afterSeq);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ChatMessage> messages = new ArrayList<>();
                while (result.next()) {
                    messages.add(mapMessage(result));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 入站消息失败", failure);
        }
    }

    @Override
    public synchronized long countInboundAfter(String threadId, long afterSeq) {
        if (threadId == null || threadId.isBlank() || afterSeq < 0L) {
            throw new IllegalArgumentException("threadId/afterSeq 非法");
        }
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM chat_message
                WHERE thread_id = ? AND local_seq > ? AND direction = 'IN'
                """)) {
            ps.setString(1, threadId);
            ps.setLong(2, afterSeq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("统计未读入站消息失败", failure);
        }
    }

    @Override
    public synchronized long lastInboundSeqAfter(String threadId, long afterSeq) {
        ensureOpen();
        if (threadId == null || threadId.isBlank() || afterSeq < 0L) {
            throw new IllegalArgumentException("threadId/afterSeq 非法");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT MAX(local_seq) AS max_seq
                FROM chat_message
                WHERE thread_id = ? AND direction = 'IN' AND local_seq > ?
                """)) {
            statement.setString(1, threadId);
            statement.setLong(2, afterSeq);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Math.max(afterSeq, result.getLong("max_seq")) : afterSeq;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 最后入站 seq 失败", failure);
        }
    }

    @Override
    public synchronized List<ChatMessage> readThreadBefore(
            String threadId, long beforeSeq, int limit) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (beforeSeq < 0L) {
            throw new IllegalArgumentException("beforeSeq 不能为负");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM chat_message
                WHERE thread_id = ? AND local_seq < ?
                ORDER BY local_seq DESC
                LIMIT ?
                """.formatted(MESSAGE_COLUMNS))) {
            statement.setString(1, threadId);
            statement.setLong(2, beforeSeq);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ChatMessage> messages = new ArrayList<>();
                while (result.next()) {
                    messages.add(mapMessage(result));
                }
                Collections.reverse(messages);
                return List.copyOf(messages);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite thread 历史失败", failure);
        }
    }

    @Override
    public synchronized Optional<ChatMessage> findByMessageId(
            String threadId, String messageId) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()
                || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s
                FROM chat_message
                WHERE thread_id = ? AND message_id = ?
                ORDER BY local_seq DESC
                LIMIT 1
                """.formatted(MESSAGE_COLUMNS))) {
            statement.setString(1, threadId);
            statement.setString(2, messageId.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapMessage(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("按 messageId 读取消息失败", failure);
        }
    }

    @Override
    public synchronized List<MessageSegment> segments(String messageKey) {
        ensureOpen();
        if (messageKey == null || messageKey.isBlank()) {
            return List.of();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT segment_index, type, text, qq_id, target_message_id,
                       file_id, url, media_type, byte_size, forward_id, raw_type
                FROM chat_message_segment
                WHERE message_key = ?
                ORDER BY segment_index ASC
                """)) {
            statement.setString(1, messageKey);
            try (ResultSet result = statement.executeQuery()) {
                List<MessageSegment> segments = new ArrayList<>();
                while (result.next()) {
                    segments.add(new MessageSegment(
                            result.getInt("segment_index"),
                            MessageSegment.Type.valueOf(result.getString("type")),
                            result.getString("text"),
                            result.getString("qq_id"),
                            result.getString("target_message_id"),
                            result.getString("file_id"),
                            result.getString("url"),
                            result.getString("media_type"),
                            result.getLong("byte_size"),
                            result.getString("forward_id"),
                            result.getString("raw_type")));
                }
                return List.copyOf(segments);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取消息段失败", failure);
        }
    }

    @Override
    public synchronized boolean updateThreadDisplayName(String threadId, String displayName) {
        ensureOpen();
        if (threadId == null || threadId.isBlank() || displayName == null
                || displayName.isBlank()) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat_thread
                SET display_name = ?
                WHERE thread_id = ? AND display_name <> ?
                """)) {
            String normalized = displayName.trim();
            statement.setString(1, normalized);
            statement.setString(2, threadId);
            statement.setString(3, normalized);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 SQLite thread 展示名失败", failure);
        }
    }

    @Override
    public synchronized Optional<ChatThread> thread(String threadId) {
        ensureOpen();
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT thread_id, account_id, chat_type, target_id, display_name,
                       last_seq, message_count, last_message_at
                FROM chat_thread
                WHERE thread_id = ?
                """)) {
            statement.setString(1, threadId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapThread(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite thread 摘要失败", failure);
        }
    }

    @Override
    public synchronized List<ChatThread> threads(int limit) {
        ensureOpen();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT thread_id, account_id, chat_type, target_id, display_name,
                       last_seq, message_count, last_message_at
                FROM chat_thread
                ORDER BY last_seq DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ChatThread> threads = new ArrayList<>();
                while (result.next()) {
                    threads.add(mapThread(result));
                }
                return List.copyOf(threads);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite thread 列表失败", failure);
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
            throw new IllegalStateException("关闭 SQLite 消息板失败", failure);
        }
    }

    private void insertSegments(String messageKey, List<MessageSegment> segments)
            throws SQLException {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_message_segment (
                  message_key, segment_index, type, text, qq_id, target_message_id,
                  file_id, url, media_type, byte_size, forward_id, raw_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (MessageSegment segment : segments) {
                statement.setString(1, messageKey);
                statement.setInt(2, segment.index());
                statement.setString(3, segment.type().name());
                statement.setString(4, segment.text());
                statement.setString(5, segment.qqId());
                statement.setString(6, segment.targetMessageId());
                statement.setString(7, segment.fileId());
                statement.setString(8, segment.url());
                statement.setString(9, segment.mediaType());
                statement.setLong(10, segment.byteSize());
                statement.setString(11, segment.forwardId());
                statement.setString(12, segment.rawType());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<ChatMessage> findInternal(String messageKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s FROM chat_message WHERE message_key = ?
                """.formatted(MESSAGE_COLUMNS))) {
            statement.setString(1, messageKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapMessage(result)) : Optional.empty();
            }
        }
    }

    private void bindMessage(PreparedStatement statement, ChatMessage message)
            throws SQLException {
        statement.setString(1, message.messageKey());
        statement.setString(2, message.source().threadId());
        statement.setString(3, message.source().accountId());
        statement.setString(4, message.source().chatType().name());
        statement.setString(5, message.source().targetId());
        statement.setString(6, message.direction().name());
        statement.setString(7, message.senderId());
        statement.setString(8, message.senderName());
        statement.setString(9, message.messageId());
        statement.setString(10, message.sentAt() == null ? null : message.sentAt().toString());
        statement.setString(11, message.receivedAt().toString());
        statement.setString(12, message.text());
        statement.setString(13, message.nonTextSummary());
        statement.setString(14, message.replyToMessageId());
        statement.setString(15, message.deliveryState().name());
        statement.setString(16, message.idempotencyKey());
    }

    private long lastInsertSeq() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            if (!result.next()) {
                throw new SQLException("SQLite 未返回 last_insert_rowid");
            }
            return result.getLong(1);
        }
    }

    private void updateThread(ChatMessage message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_thread (
                  thread_id, account_id, chat_type, target_id, display_name,
                  last_seq, message_count, last_message_at
                ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT(thread_id) DO UPDATE SET
                  last_seq = excluded.last_seq,
                  message_count = chat_thread.message_count + 1,
                  last_message_at = excluded.last_message_at
                """)) {
            statement.setString(1, message.source().threadId());
            statement.setString(2, message.source().accountId());
            statement.setString(3, message.source().chatType().name());
            statement.setString(4, message.source().targetId());
            statement.setString(5, message.source().targetId());
            statement.setLong(6, message.localSeq());
            statement.setString(7, message.receivedAt().toString());
            statement.executeUpdate();
        }
    }

    private static ChatMessage mapMessage(ResultSet result) throws SQLException {
        ChatSourceKey source = new ChatSourceKey(
                result.getString("account_id"),
                ChatType.valueOf(result.getString("chat_type")),
                result.getString("target_id"));
        String sentAt = result.getString("sent_at");
        return new ChatMessage(
                result.getString("message_key"),
                source,
                result.getLong("local_seq"),
                ChatDirection.valueOf(result.getString("direction")),
                result.getString("sender_id"),
                result.getString("sender_name"),
                result.getString("message_id"),
                sentAt == null ? null : Instant.parse(sentAt),
                Instant.parse(result.getString("received_at")),
                result.getString("text"),
                result.getString("non_text_summary"),
                result.getString("reply_to_message_id"),
                DeliveryState.valueOf(result.getString("delivery_state")),
                result.getString("idempotency_key"));
    }

    private static ChatThread mapThread(ResultSet result) throws SQLException {
        ChatSourceKey source = new ChatSourceKey(
                result.getString("account_id"),
                ChatType.valueOf(result.getString("chat_type")),
                result.getString("target_id"));
        return new ChatThread(
                source,
                result.getString("display_name"),
                result.getLong("last_seq"),
                result.getLong("message_count"),
                Instant.parse(result.getString("last_message_at")));
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始写入异常。
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接已在失败路径中不可用时，后续操作会再次报错。
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SQLite 消息板已关闭");
        }
    }
}
