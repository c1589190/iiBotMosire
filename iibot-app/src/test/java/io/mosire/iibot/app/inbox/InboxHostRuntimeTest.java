package io.mosire.iibot.app.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.inbox.client.InboxModels;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.ticket.InMemoryTicketStore;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(60)
class InboxHostRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void startsEmbeddedServerWritesOwnerOnlyTokensAndLeavesNoRawTokenInDescriptor()
            throws Exception {
        TicketService tickets = new TicketService(new InMemoryTicketStore(), CLOCK);
        PostService posts = new PostService(new InMemoryPostStore(), CLOCK);
        Path inboxDir = tempDir.resolve("inbox-state");
        InboxHostRuntime host = InboxHostRuntime.start(inboxConfig(inboxDir), tickets, posts);
        int port = URI.create(host.descriptor().apiBaseUrl()).getPort();
        try {
            assertThat(host.dataDir()).isEqualTo(inboxDir.toAbsolutePath());
            assertThat(Files.isDirectory(host.dataDir())).isTrue();
            assertOwnerOnly(host.programTokenPath());
            assertOwnerOnly(host.wakeTokenPath());

            String programToken = Files.readString(host.programTokenPath());
            String wakeToken = Files.readString(host.wakeTokenPath());
            String descriptorText = Files.readString(host.descriptorPath());
            assertThat(descriptorText).doesNotContain(programToken).doesNotContain(wakeToken);

            JsonNode descriptor = JSON.readTree(descriptorText);
            java.util.List<String> fields = new java.util.ArrayList<>();
            descriptor.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder(
                    "inboxId", "apiBaseUrl", "adapterTokenRef", "channels", "label");
            assertThat(descriptor.path("inboxId").asText()).isEqualTo("app-a");
            assertThat(descriptor.path("apiBaseUrl").asText()).startsWith("http://127.0.0.1:");
            assertThat(descriptor.path("adapterTokenRef").asText())
                    .isEqualTo("file:" + host.wakeTokenPath().toAbsolutePath().normalize());
            assertThat(descriptor.path("label").asText()).isEqualTo("dsh-session-S1");
            assertThat(JSON.readTree(descriptorText).toString())
                    .doesNotContain(programToken).doesNotContain(wakeToken);

            assertThat(host.programClient().whoAmI().label()).isEqualTo("iibot-program-mcp");
            assertPortOpen(port);

            var ticket = tickets.submit(new Actor("player-1", ActorRole.PLAYER),
                    TicketType.ACTION_DRAFT, "HIDDEN_TICKET_TITLE", "HIDDEN_TICKET_BODY",
                    "host-ticket-1");
            var post = posts.createDraft(new Actor("player-1", ActorRole.PLAYER),
                    "HIDDEN_POST_TITLE", "HIDDEN_POST_BODY", PostVisibility.PUBLIC,
                    0L, "host-post-1");
            assertThat(ticket.ok()).isTrue();
            assertThat(post.ok()).isTrue();

            assertThat(host.scanOnce()).isEqualTo(2);
            InboxModels.InboxStatus beforeDedupe = host.programClient()
                    .status("app-a", Set.of());
            assertThat(beforeDedupe.available()).isEqualTo(2);
            long revisionAfterFirstScan = beforeDedupe.revision();

            assertThat(host.scanOnce()).isZero();
            InboxModels.InboxStatus afterDedupe = host.programClient()
                    .status("app-a", Set.of());
            assertThat(afterDedupe.available()).isEqualTo(2);
            assertThat(afterDedupe.revision()).isEqualTo(revisionAfterFirstScan);

            InboxModels.ItemList list = host.programClient().list("app-a", Set.of(), 50);
            assertThat(list.items()).hasSize(2);
            assertThat(list.items()).extracting(InboxModels.ItemSummary::channel)
                    .containsExactlyInAnyOrder("ticket", "publication-review");
            assertThat(list.toString()).doesNotContain("HIDDEN_TICKET_TITLE",
                    "HIDDEN_TICKET_BODY", "HIDDEN_POST_TITLE", "HIDDEN_POST_BODY",
                    programToken, wakeToken);
            Map<String, InboxModels.ItemSummary> byChannel = list.items().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            InboxModels.ItemSummary::channel, item -> item));
            assertThat(byChannel.get("ticket").summary()).isEqualTo("ACTION_DRAFT");
            assertThat(byChannel.get("publication-review").summary()).isEqualTo("PUBLIC");

            InboxModels.ClaimResult claimed = host.programClient().claim("app-a",
                    list.items().stream().map(InboxModels.ItemSummary::itemId).toList(),
                    java.time.Duration.ofSeconds(30));
            assertThat(claimed.claimed()).hasSize(2);
            for (InboxModels.ClaimedItem item : claimed.claimed()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> content = JSON.convertValue(item.content(), Map.class);
                if ("ticket".equals(item.channel())) {
                    assertThat(content).containsOnlyKeys(
                            "ticketId", "type", "status", "revision");
                } else {
                    assertThat(content).containsOnlyKeys("postId", "visibility", "revision");
                }
                assertThat(content.toString()).doesNotContain("HIDDEN_TICKET_TITLE",
                        "HIDDEN_TICKET_BODY", "HIDDEN_POST_TITLE", "HIDDEN_POST_BODY",
                        "player-1", programToken, wakeToken);
            }

            InboxModels.CompleteResult completed = host.programClient().complete("app-a",
                    claimed.claimed().stream()
                            .map(item -> new InboxModels.Completion(
                                    item.claimToken(), "completed", "host test"))
                            .toList());
            assertThat(completed.completed()).hasSize(2);
            assertThat(completed.available()).isZero();

            assertThat(host.scanOnce()).isZero();
            InboxModels.InboxStatus afterComplete = host.programClient()
                    .status("app-a", Set.of());
            assertThat(afterComplete.available()).isZero();
            assertThat(afterComplete.revision()).isEqualTo(completed.revision());
        } finally {
            host.close();
        }

        assertThatThrownBy(() -> assertPortOpen(port))
                .as("关闭 app InboxHostRuntime 后 embedded 端口必须关闭")
                .isInstanceOf(IOException.class);
        assertThat(Files.isDirectory(inboxDir)).isTrue();
        assertThat(Files.exists(inboxDir.resolve("inbox.db"))).isTrue();
    }

    private AppConfig.Inbox inboxConfig(Path dataDir) {
        return new AppConfig.Inbox(true, dataDir.toAbsolutePath(),
                "app-a", "dsh-session-S1", Set.of("ticket", "publication-review"),
                100L, 1L);
    }

    private static void assertOwnerOnly(Path file) throws IOException {
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static void assertPortOpen(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        }
    }
}
