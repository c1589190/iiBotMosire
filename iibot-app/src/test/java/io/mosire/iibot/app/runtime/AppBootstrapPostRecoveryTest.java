package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.SecretProvider;
import io.mosire.iibot.app.mcp.ControlMcpServer;
import io.mosire.iibot.app.publication.JsonPostStore;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.app.support.FakeMessagingAdapter;
import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStatus;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 组合根推文接线与 posts.json 重启恢复 smoke。 */
@Timeout(60)
class AppBootstrapPostRecoveryTest {

    private static final String CONTROL_TOKEN = "post-recovery-token-51d0c98a";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final SecretProvider SECRETS = SecretProvider.of(Map.of(
            "IIBOT_NAPCAT_TOKEN", "napcat-token",
            "IIBOT_LLM_KEY", "llm-token",
            "IIBOT_CONTROL_TOKEN", CONTROL_TOKEN));

    @TempDir
    Path tempDir;

    @Test
    void everySecretaryGetsWorkspaceAndPostServiceAndPostsJsonSurvivesRestart() throws Exception {
        AppConfig config = config();
        Path postsFile = config.dataDir().resolve("posts.json");
        FakeMessagingAdapter firstAdapter = new FakeMessagingAdapter();
        AppRuntime first = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(), firstAdapter));
        String postId;
        try {
            first.start();
            ControlMcpServer control = controlServer(first);
            PostService postService = postService(control);
            SecretarySessionRegistry sessions = secretarySessions(control);
            assertThat(sessions.all()).isNotEmpty().allSatisfy(session -> {
                assertThat(session.workspaceStore()).isNotNull();
                assertThat(session.postService()).isSameAs(postService);
                assertThat(session.tools()).extracting(tool -> tool.name())
                        .contains("secretary_post_create", "secretary_workspace_note");
            });

            PostCommandResult created = postService.createDraft(
                    new Actor("player-1", ActorRole.PLAYER), "重启恢复标题",
                    "重启恢复正文", PostVisibility.PRIVATE,
                    postService.collectionRevision("player-1"), "recovery-post-create");
            assertThat(created.ok()).isTrue();
            postId = created.postId();
            Post draft = postService.post(postId).orElseThrow();
            assertThat(postService.approve(new Actor("director-1", ActorRole.DIRECTOR),
                    postId, draft.revision(), "recovery-post-approve").ok()).isTrue();
            Post approved = postService.post(postId).orElseThrow();
            assertThat(postService.publish(new Actor("director-1", ActorRole.DIRECTOR),
                    postId, approved.revision(), "recovery-post-publish").ok()).isTrue();
            assertThat(Files.isRegularFile(postsFile)).isTrue();
        } finally {
            first.close();
        }

        Post persisted = new JsonPostStore(config.dataDir()).load().findPost(postId).orElseThrow();
        assertThat(persisted.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(persisted.body()).isEqualTo("重启恢复正文");

        FakeMessagingAdapter secondAdapter = new FakeMessagingAdapter();
        AppRuntime second = AppBootstrap.build(config,
                BootstrapOptions.fake(SECRETS, CLOCK, FakeLlmClient.with(), secondAdapter));
        try {
            second.start();
            ControlMcpServer control = controlServer(second);
            PostService reloaded = postService(control);
            assertThat(reloaded.post(postId)).hasValueSatisfying(post -> {
                assertThat(post.status()).isEqualTo(PostStatus.PUBLISHED);
                assertThat(post.body()).isEqualTo("重启恢复正文");
            });
            assertThat(secretarySessions(control).all())
                    .allSatisfy(session -> assertThat(session.postService()).isSameAs(reloaded));
        } finally {
            second.close();
        }
    }

    private AppConfig config() throws Exception {
        Path factsFile = TestSupport.writeFacts(
                tempDir.resolve("world").resolve("facts.json"), """
                        {"facts": [
                          {"factId": "public-1", "content": "公共事实", "visibility": "PUBLIC"}
                        ]}
                        """);
        ObjectNode node = TestSupport.validConfigNode(tempDir.resolve("data"), factsFile);
        ObjectNode control = node.putObject("control");
        control.put("enabled", true);
        control.put("host", "127.0.0.1");
        control.put("port", 0);
        control.put("path", "/control/mcp");
        control.put("bearerTokenEnv", "IIBOT_CONTROL_TOKEN");
        return TestSupport.loadConfig(TestSupport.writeConfig(
                tempDir.resolve("iibot-post-recovery.json"), node));
    }

    private static ControlMcpServer controlServer(AppRuntime runtime) throws Exception {
        Field field = AppRuntime.class.getDeclaredField("controlPlane");
        field.setAccessible(true);
        return (ControlMcpServer) field.get(runtime);
    }

    private static PostService postService(ControlMcpServer server) throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("postService");
        field.setAccessible(true);
        return (PostService) field.get(server);
    }

    private static SecretarySessionRegistry secretarySessions(ControlMcpServer server)
            throws Exception {
        Field field = ControlMcpServer.class.getDeclaredField("secretarySessions");
        field.setAccessible(true);
        return (SecretarySessionRegistry) field.get(server);
    }
}
