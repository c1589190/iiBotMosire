package io.mosire.iibot.app.chat;

import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.QqPlatformCapability;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 控制面 QQ 会话目录：合并最近会话、群列表和好友列表，去重并标记当前推演群。
 *
 * <p>好友列表在 NapCat 上是慢路径；all/friends 查询超时只返回 partial + warning，
 * 不阻塞 recent/groups。读取只返回会话元数据，不返回消息正文。</p>
 */
public final class QqChatDirectoryService {

    public enum Scope {
        CONFIGURED,
        RECENT,
        GROUPS,
        FRIENDS,
        ALL
    }

    public record Result(List<ChatConversation> conversations, boolean partial,
                         List<String> warnings) {
        public Result {
            conversations = List.copyOf(conversations);
            warnings = List.copyOf(warnings);
        }
    }

    private final QqPlatformCapability platform;
    private final Supplier<String> publicGroupId;
    private final Duration fastTimeout;
    private final Duration friendsTimeout;

    public QqChatDirectoryService(QqPlatformCapability platform,
                                  Supplier<String> publicGroupId) {
        this(platform, publicGroupId, Duration.ofSeconds(15), Duration.ofSeconds(8));
    }

    public QqChatDirectoryService(QqPlatformCapability platform,
                                  Supplier<String> publicGroupId,
                                  Duration fastTimeout,
                                  Duration friendsTimeout) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.publicGroupId = Objects.requireNonNull(publicGroupId, "publicGroupId");
        this.fastTimeout = positive(fastTimeout, "fastTimeout");
        this.friendsTimeout = positive(friendsTimeout, "friendsTimeout");
    }

    public Result list(Scope scope, int count) {
        Scope effective = scope == null ? Scope.CONFIGURED : scope;
        if (count < 1 || count > 200) {
            throw new IllegalArgumentException("count 必须在 1–200 之间");
        }
        return switch (effective) {
            case CONFIGURED -> new Result(configured(count), false, List.of());
            case RECENT -> new Result(fetchRecent(count), false, List.of());
            case GROUPS -> new Result(fetchGroups(count), false, List.of());
            case FRIENDS -> {
                List<String> warnings = new ArrayList<>();
                List<ChatConversation> result = safeFetch(
                        () -> platform.friends(false), friendsTimeout,
                        "FRIENDS_TIMEOUT", warnings);
                yield new Result(limit(result, count), !warnings.isEmpty(), warnings);
            }
            case ALL -> fetchAll(count);
        };
    }

    /**
     * 对已知 targetId 做类型解析。优先 current public group、recent、groups；
     * 不调用慢好友列表。无法唯一解析时返回 empty。
     */
    public Optional<SessionType> resolve(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return Optional.empty();
        }
        String normalized = targetId.trim();
        Set<SessionType> matches = new LinkedHashSet<>();
        String configured = publicGroupId.get();
        if (configured != null && configured.equals(normalized)) {
            matches.add(SessionType.GROUP);
        }
        List<String> warnings = new ArrayList<>();
        List<ChatConversation> recent = safeFetch(
                () -> platform.recentConversations(200), fastTimeout,
                "RECENT_TIMEOUT", warnings);
        for (ChatConversation conversation : recent) {
            if (conversation.targetId().equals(normalized)) {
                matches.add(conversation.sessionType());
            }
        }
        if (matches.isEmpty()) {
            List<ChatConversation> groups = safeFetch(
                    platform::joinedGroups, fastTimeout, "GROUPS_TIMEOUT", warnings);
            for (ChatConversation conversation : groups) {
                if (conversation.targetId().equals(normalized)) {
                    matches.add(conversation.sessionType());
                }
            }
        }
        return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
    }

    private Result fetchAll(int count) {
        List<String> warnings = new ArrayList<>();
        Map<String, ChatConversation> merged = new LinkedHashMap<>();
        merge(merged, safeFetch(() -> platform.recentConversations(200),
                fastTimeout, "RECENT_TIMEOUT", warnings));
        merge(merged, safeFetch(platform::joinedGroups,
                fastTimeout, "GROUPS_TIMEOUT", warnings));
        merge(merged, safeFetch(() -> platform.friends(false),
                friendsTimeout, "FRIENDS_TIMEOUT", warnings));
        return new Result(limit(new ArrayList<>(merged.values()), count),
                !warnings.isEmpty(), warnings);
    }

    private List<ChatConversation> fetchRecent(int count) {
        return limit(fetch(platform.recentConversations(
                Math.max(count, Math.min(200, count * 2))), fastTimeout), count);
    }

    private List<ChatConversation> fetchGroups(int count) {
        return limit(fetch(platform.joinedGroups(), fastTimeout), count);
    }

    private List<ChatConversation> configured(int count) {
        String groupId = publicGroupId.get();
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        return limit(List.of(new ChatConversation(SessionType.GROUP, groupId,
                "当前推演群", "", null, null)), count);
    }

    private List<ChatConversation> fetch(
            java.util.concurrent.CompletionStage<List<ChatConversation>> stage,
            Duration timeout) {
        try {
            return stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QQ 目录读取被中断", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("QQ 目录读取失败", failure);
        }
    }

    private List<ChatConversation> safeFetch(
            java.util.function.Supplier<java.util.concurrent.CompletionStage<
                    List<ChatConversation>>> supplier,
            Duration timeout,
            String timeoutCode,
            List<String> warnings) {
        try {
            return fetch(supplier.get(), timeout);
        } catch (RuntimeException failure) {
            warnings.add(timeoutCode);
            return List.of();
        }
    }

    private void merge(Map<String, ChatConversation> merged,
                       List<ChatConversation> additions) {
        for (ChatConversation conversation : additions) {
            merged.putIfAbsent(conversation.sessionType() + ":" + conversation.targetId(),
                    conversation);
        }
    }

    private static List<ChatConversation> limit(List<ChatConversation> input, int count) {
        if (input.size() <= count) {
            return List.copyOf(input);
        }
        return List.copyOf(input.subList(0, count));
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return value;
    }
}
