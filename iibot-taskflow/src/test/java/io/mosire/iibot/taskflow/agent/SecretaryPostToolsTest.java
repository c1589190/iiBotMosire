package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.post.InMemoryPostStore;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostStore;
import io.mosire.iibot.taskflow.post.PostVisibility;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 秘书推文工具的 metadata、授权与身份绑定。 */
class SecretaryPostToolsTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_A = new Actor("player-a", ActorRole.PLAYER);
    private static final Actor PLAYER_B = new Actor("player-b", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);

    @Test
    void toolsHaveSecretaryMetadataPostResourceAndNoPlayerIdArgument() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        List<AgentTool> tools = SecretaryPostTools.all(service);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactly(SecretaryPostTools.LIST, SecretaryPostTools.READ,
                        SecretaryPostTools.CREATE);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.spec()).isEqualTo(ToolSpec.DEFAULT);
            assertThat(tool.spec().destructive()).isFalse();
            assertThat(tool.spec().sensitive()).isFalse();
            assertThat(tool.roles()).isEqualTo(Set.of("secretary"));
            assertThat(tool.resources().namespaces())
                    .containsExactly(PostStore.RESOURCE_NAMESPACE);
            assertThat(tool.jsonSchema()).doesNotContainKey("playerId");
            assertThat(tool.jsonSchema().toString()).doesNotContain("playerId");
        });
        assertThat(tools).extracting(AgentTool::name)
                .noneMatch(name -> name.contains("approve") || name.contains("publish")
                        || name.contains("send"));
    }

    @Test
    void createUsesBoundPlayerAndNeverReturnsBody() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        ToolResult created = execute(service, "player-a", SecretaryPostTools.CREATE,
                Map.of("title", "标题", "body", "只应进入领域存储的正文",
                        "visibility", "PRIVATE", "expectedRevision", 0,
                        "idempotencyKey", "tool-create-1", "playerId", "player-b"),
                permissions("secretary", true));

        assertThat(created.success()).isTrue();
        assertThat(created.message())
                .contains("PENDING_APPROVAL")
                .doesNotContain("只应进入领域存储的正文")
                .doesNotContain("标题");
        assertThat(service.postsForGm()).hasSize(1);
        Post post = service.postsForGm().get(0);
        assertThat(post.authorPlayerId()).isEqualTo("player-a");
        assertThat(post.visibility()).isEqualTo(PostVisibility.PRIVATE);
        assertThat(post.status()).isEqualTo(io.mosire.iibot.taskflow.post.PostStatus.PENDING_APPROVAL);
    }

    @Test
    void listAndReadFilterOtherPlayersDraftsAndPrivatePosts() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        PostCommandResult own = service.createDraft(PLAYER_A, "A 草稿", "A 可见正文",
                PostVisibility.PRIVATE, 0, "a-create");
        PostCommandResult otherPrivate = service.createDraft(PLAYER_B, "B 私密",
                "B 私密正文", PostVisibility.PRIVATE, 0, "b-private");
        PostCommandResult otherPublic = service.createDraft(PLAYER_B, "B 公共草稿",
                "B 公共草稿正文", PostVisibility.PUBLIC, 1, "b-public");
        assertThat(service.approve(GM, otherPublic.postId(), 1, "b-approve").ok()).isTrue();
        assertThat(service.publish(GM, otherPublic.postId(), 2, "b-publish").ok()).isTrue();

        ToolResult list = execute(service, "player-a", SecretaryPostTools.LIST, Map.of(),
                permissions("secretary", true));
        assertThat(list.success()).isTrue();
        assertThat(list.message())
                .contains(own.postId())
                .contains(otherPublic.postId())
                .contains("collectionRevision:")
                .doesNotContain(otherPrivate.postId())
                .doesNotContain("B 私密正文");

        ToolResult ownRead = execute(service, "player-a", SecretaryPostTools.READ,
                Map.of("postId", own.postId()), permissions("secretary", true));
        assertThat(ownRead.success()).isTrue();
        assertThat(ownRead.message()).contains("A 可见正文");

        ToolResult publicRead = execute(service, "player-a", SecretaryPostTools.READ,
                Map.of("postId", otherPublic.postId()), permissions("secretary", true));
        assertThat(publicRead.success()).isTrue();
        assertThat(publicRead.message()).contains("B 公共草稿正文");

        ToolResult privateRead = execute(service, "player-a", SecretaryPostTools.READ,
                Map.of("postId", otherPrivate.postId()), permissions("secretary", true));
        assertThat(privateRead.success()).isFalse();
        assertThat(privateRead.code()).isEqualTo(SecretaryPostTools.POST_NOT_VISIBLE);
        assertThat(privateRead.message()).doesNotContain("B 私密正文").doesNotContain("B 私密");

        ToolResult missing = execute(service, "player-a", SecretaryPostTools.READ,
                Map.of("postId", "does-not-exist"), permissions("secretary", true));
        assertThat(missing.code()).isEqualTo(SecretaryPostTools.POST_NOT_VISIBLE);
        assertThat(missing.message()).isEqualTo(privateRead.message());
    }

    @Test
    void authorizerRejectsWrongRoleAndMissingPostResourceScope() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);

        ToolResult wrongRole = execute(service, "player-a", SecretaryPostTools.LIST, Map.of(),
                permissions("gm", true));
        assertThat(wrongRole.code()).isEqualTo("PERMISSION_DENIED");

        ToolResult noResource = execute(service, "player-a", SecretaryPostTools.LIST, Map.of(),
                permissions("secretary", false));
        assertThat(noResource.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    }

    @Test
    void modelCannotForgeActorOrInjectAWriteForAnotherPlayer() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        ToolResult created = execute(service, "player-a", SecretaryPostTools.CREATE,
                Map.of("title", "t", "body", "b", "visibility", "PUBLIC",
                        "expectedRevision", 0, "idempotencyKey", "k",
                        "actor", new Actor("gm-1", ActorRole.GM)),
                permissions("secretary", true));

        assertThat(created.success()).isTrue();
        assertThat(service.postsForGm()).singleElement()
                .extracting(Post::authorPlayerId)
                .isEqualTo("player-a");
        assertThat(service.postsForGm().get(0).approvedBy()).isNull();
        assertThat(service.postsForGm().get(0).publishedBy()).isNull();
    }

    @Test
    void revisionConflictIsStableAndDoesNotCreateAnotherPost() {
        PostService service = new PostService(new InMemoryPostStore(), CLOCK);
        assertThat(service.createDraft(PLAYER_A, "t", "b", PostVisibility.PUBLIC, 0,
                "direct-create").ok()).isTrue();

        ToolResult conflict = execute(service, "player-a", SecretaryPostTools.CREATE,
                Map.of("title", "t2", "body", "b2", "visibility", "PUBLIC",
                        "expectedRevision", 0, "idempotencyKey", "tool-create-2"),
                permissions("secretary", true));

        assertThat(conflict.success()).isFalse();
        assertThat(conflict.code()).isEqualTo(PostService.REVISION_CONFLICT);
        assertThat(service.postsForGm()).hasSize(1);
    }

    private static ToolResult execute(PostService service, String playerId, String toolName,
                                      Map<String, Object> arguments,
                                      AgentPermissionSet permissions) {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(SecretaryPostTools.all(service));
        Map<String, Object> config = Map.of(
                SecretaryToolContext.PLAYER_ID, playerId,
                SecretaryToolContext.ACTOR, new Actor(playerId, ActorRole.PLAYER));
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                config, arguments);
        return ToolCallAuthorizer.standard().execute(registry, toolName, context);
    }

    private static AgentPermissionSet permissions(String role, boolean withPostScope) {
        AgentPermissionSet.Builder builder = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(SecretaryPostTools.LIST, SecretaryPostTools.READ,
                        SecretaryPostTools.CREATE)
                .roles(RoleSet.of(role));
        if (withPostScope) {
            builder.resourceScopes(new ResourceScopeMap(Map.of(
                    PostStore.RESOURCE_NAMESPACE,
                    ResourceScope.of(PostStore.RESOURCE_NAMESPACE))));
        }
        return builder.build();
    }
}
