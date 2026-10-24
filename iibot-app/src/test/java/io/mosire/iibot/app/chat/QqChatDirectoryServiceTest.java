package io.mosire.iibot.app.chat;

import io.mosire.iibot.connect.core.ChatConversation;
import io.mosire.iibot.connect.core.ChatHistoryPage;
import io.mosire.iibot.connect.core.ChatHistoryQuery;
import io.mosire.iibot.connect.core.ChatMessageDetail;
import io.mosire.iibot.connect.core.GroupMember;
import io.mosire.iibot.connect.core.InboundMessage.SessionType;
import io.mosire.iibot.connect.core.QqPlatformCapability;
import io.mosire.iibot.connect.core.SendReceipt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 目录合并、默认群、去重和慢好友 partial 语义。 */
class QqChatDirectoryServiceTest {

    @Test
    void configuredScopeReturnsOnlyCurrentSimulationGroup() {
        FakePlatform platform = new FakePlatform();
        QqChatDirectoryService directory =
                new QqChatDirectoryService(platform, () -> "30003");
        QqChatDirectoryService.Result result =
                directory.list(QqChatDirectoryService.Scope.CONFIGURED, 20);

        assertThat(result.conversations()).singleElement().satisfies(conversation -> {
            assertThat(conversation.sessionType()).isEqualTo(SessionType.GROUP);
            assertThat(conversation.targetId()).isEqualTo("30003");
        });
        assertThat(result.partial()).isFalse();
    }

    @Test
    void allScopeMergesRecentGroupsFriendsAndDeduplicates() {
        FakePlatform platform = new FakePlatform();
        platform.recent = List.of(
                conversation(SessionType.PRIVATE, "20002"),
                conversation(SessionType.GROUP, "30003"));
        platform.groups = List.of(
                conversation(SessionType.GROUP, "30003"),
                conversation(SessionType.GROUP, "40004"));
        platform.friends = List.of(conversation(SessionType.PRIVATE, "20002"));
        QqChatDirectoryService directory =
                new QqChatDirectoryService(platform, () -> "30003");

        QqChatDirectoryService.Result result =
                directory.list(QqChatDirectoryService.Scope.ALL, 20);

        assertThat(result.conversations()).extracting("targetId")
                .containsExactly("20002", "30003", "40004");
        assertThat(result.partial()).isFalse();
    }

    @Test
    void slowFriendsReturnsPartialButKeepsRecentAndGroups() {
        FakePlatform platform = new FakePlatform();
        platform.recent = List.of(conversation(SessionType.GROUP, "30003"));
        platform.groups = List.of(conversation(SessionType.GROUP, "40004"));
        platform.friendsFailure = true;
        QqChatDirectoryService directory = new QqChatDirectoryService(
                platform, () -> "30003", Duration.ofMillis(200), Duration.ofMillis(50));

        QqChatDirectoryService.Result result =
                directory.list(QqChatDirectoryService.Scope.ALL, 20);

        assertThat(result.conversations()).extracting("targetId")
                .containsExactly("30003", "40004");
        assertThat(result.partial()).isTrue();
        assertThat(result.warnings()).containsExactly("FRIENDS_TIMEOUT");
    }

    @Test
    void resolveUsesRecentAndGroupsWithoutFriendList() {
        FakePlatform platform = new FakePlatform();
        platform.recent = List.of(conversation(SessionType.PRIVATE, "20002"));
        platform.groups = List.of(conversation(SessionType.GROUP, "40004"));
        QqChatDirectoryService directory =
                new QqChatDirectoryService(platform, () -> "30003");

        assertThat(directory.resolve("20002")).contains(SessionType.PRIVATE);
        assertThat(directory.resolve("40004")).contains(SessionType.GROUP);
        assertThat(directory.resolve("99999")).isEmpty();
        assertThat(platform.friendCalls).isZero();
    }

    private static ChatConversation conversation(SessionType type, String targetId) {
        return new ChatConversation(type, targetId, "name", "", null, null);
    }

    private static final class FakePlatform implements QqPlatformCapability {
        private List<ChatConversation> recent = List.of();
        private List<ChatConversation> groups = List.of();
        private List<ChatConversation> friends = List.of();
        private boolean friendsFailure;
        private int friendCalls;

        @Override
        public CompletableFuture<List<ChatConversation>> recentConversations(int count) {
            return CompletableFuture.completedFuture(recent);
        }

        @Override
        public CompletableFuture<List<ChatConversation>> joinedGroups() {
            return CompletableFuture.completedFuture(groups);
        }

        @Override
        public CompletableFuture<List<ChatConversation>> friends(boolean noCache) {
            friendCalls++;
            return friendsFailure
                    ? CompletableFuture.failedFuture(new IllegalStateException("slow"))
                    : CompletableFuture.completedFuture(friends);
        }

        @Override
        public CompletableFuture<List<GroupMember>> groupMembers(String groupId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ChatMessageDetail> message(String messageId) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<SendReceipt> sendPrivate(String targetQqId,
                                                          String temporaryGroupId,
                                                          List<String> textSegments,
                                                          String idempotencyKey) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<SendReceipt> sendGroup(String groupId,
                                                        List<String> textSegments,
                                                        String idempotencyKey) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }

        @Override
        public CompletableFuture<ChatHistoryPage> history(ChatHistoryQuery query) {
            return CompletableFuture.failedFuture(new IllegalStateException("not used"));
        }
    }
}
