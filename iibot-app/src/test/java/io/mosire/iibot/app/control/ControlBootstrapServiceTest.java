package io.mosire.iibot.app.control;

import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.app.support.FakeLlmClient;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.agent.BriefingResult;
import io.mosire.iibot.taskflow.agent.MappedIdentity;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P3 控制初始化服务：事件先行控制状态、动态身份/秘书路由、世界装载与 fail-closed。 */
class ControlBootstrapServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor SUPERVISOR = new Actor(
            ControlBootstrapService.SUPERVISOR_ACTOR_ID, ActorRole.SUPERVISOR);
    private static final Actor BRIEF_ISSUER = new Actor("director-1", ActorRole.DIRECTOR);

    @TempDir
    Path tempDir;

    @Test
    void initializeAndSetPublicGroupAppendEventsBeforeMemoryAndRestoreControlState() {
        Path db = tempDir.resolve("control-state.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);

            assertThat(fixture.service.initialize().ok()).isTrue();
            assertThat(fixture.service.initialize().ok()).isTrue();
            assertThat(count(store, DomainEventTypes.CONTROL_INITIALIZED)).isEqualTo(1);

            assertThat(fixture.service.publicGroupId()).isEqualTo("group-initial");
            ControlOperationResult changed = fixture.service.setPublicGroup("group-new");
            assertThat(changed.ok()).isTrue();
            assertThat(fixture.service.publicGroupId()).isEqualTo("group-new");
            assertThat(fixture.service.state().revision()).isEqualTo(1);
            assertThat(count(store, DomainEventTypes.CONTROL_STATE)).isEqualTo(1);
            StoredEvent stateEvent = store.latestByType(
                    DomainEventTypes.CONTROL_STATE).orElseThrow();
            assertThat(stateEvent.content().json())
                    .contains("\"publicGroupId\":\"group-new\"");

            // 同群重复调用幂等，不追加第二条 CONTROL_STATE。
            assertThat(fixture.service.setPublicGroup("group-new").ok()).isTrue();
            assertThat(count(store, DomainEventTypes.CONTROL_STATE)).isEqualTo(1);

            ControlBootstrapService restored = rebuild(fixture, store);
            assertThat(restored.publicGroupId()).isEqualTo("group-new");
            assertThat(restored.state().revision()).isEqualTo(1);
        }
    }

    @Test
    void setPublicGroupAppendFailureLeavesMemoryStateUnchanged() {
        Path db = tempDir.resolve("control-state-failure.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);
            DomainEventJournal failing = event -> {
                throw new IllegalStateException("模拟控制状态事件写入失败");
            };
            ControlBootstrapService service = builder(fixture, store)
                    .journal(failing)
                    .build();

            ControlOperationResult result = service.setPublicGroup("group-new");
            assertThat(result.ok()).isFalse();
            assertThat(result.code()).isEqualTo("CONTROL_STATE_APPEND_FAILED");
            assertThat(service.publicGroupId()).isEqualTo("group-initial");
            assertThat(store.latestByType(DomainEventTypes.CONTROL_STATE)).isEmpty();
        }
    }

    @Test
    void registerPlayerBuildsEngineProfileWorkspaceSessionAndDynamicRoutes() {
        Path db = tempDir.resolve("register-player.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);

            ControlOperationResult registered = fixture.service.registerPlayer(
                    "player-9", "qq-p9", "动态玩家", "register-player-9");
            assertThat(registered.ok()).as(registered.toString()).isTrue();
            assertThat(fixture.engine.playerProfiles())
                    .extracting(PlayerProfile::playerId).contains("player-9");
            PlayerProfile profile = fixture.engine.playerProfiles().stream()
                    .filter(candidate -> candidate.playerId().equals("player-9"))
                    .findFirst().orElseThrow();
            assertThat(profile.qqId()).isEqualTo("qq-p9");
            assertThat(profile.displayName()).isEqualTo("动态玩家");

            MappedIdentity identity = fixture.service.resolve("qq-p9").orElseThrow();
            assertThat(identity.actor().actorId()).isEqualTo("player-9");
            assertThat(identity.actor().role()).isEqualTo(ActorRole.PLAYER);
            assertThat(fixture.service.qqForPlayer("player-9")).contains("qq-p9");
            assertThat(fixture.service.ackTextByQq("qq-p9")).isPresent();
            assertThat(fixture.sessions.find("player-9")).isPresent();
            assertThat(fixture.workspaces.rootFor("player-9")).isDirectory();
            assertThat(count(store, DomainEventTypes.CONTROL_PLAYER_REGISTERED)).isEqualTo(1);

            // 重复完整身份幂等：不新增 profile，不替换现有 Session。
            SecretarySession existingSession = fixture.sessions.find("player-9").orElseThrow();
            ControlOperationResult repeat = fixture.service.registerPlayer(
                    "player-9", "qq-p9", "动态玩家", "register-player-9-repeat");
            assertThat(repeat.ok()).isTrue();
            assertThat(fixture.engine.playerProfiles()).hasSize(1);
            assertThat(fixture.sessions.find("player-9")).contains(existingSession);

            // QQ / playerId 冲突 fail-closed，且不新增引擎身份。
            ControlOperationResult qqConflict = fixture.service.registerPlayer(
                    "player-other", "qq-p9", "冲突 QQ", "conflict-qq");
            assertThat(qqConflict.ok()).isFalse();
            assertThat(qqConflict.code()).isEqualTo("CONFLICT");
            ControlOperationResult idConflict = fixture.service.registerPlayer(
                    "player-9", "qq-other", "冲突 ID", "conflict-id");
            assertThat(idConflict.ok()).isFalse();
            assertThat(idConflict.code()).isEqualTo("CONFLICT");
            assertThat(fixture.engine.playerProfiles()).hasSize(1);

            ControlOperationResult invalid = fixture.service.registerPlayer(
                    "bad id", "qq-bad", "非法", "invalid-id");
            assertThat(invalid.ok()).isFalse();
            assertThat(invalid.code()).isEqualTo("INVALID_ARGUMENTS");
        }
    }

    @Test
    void registerDirectorCreatesOperatorRouteButNoSecretarySessionAndBindIsIdempotent() {
        Path db = tempDir.resolve("register-director.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);

            ControlOperationResult registered = fixture.service.registerDirector(
                    "director-9", "qq-d9", "动态推演", "register-director-9");
            assertThat(registered.ok()).as(registered.toString()).isTrue();
            assertThat(fixture.engine.directorProfiles())
                    .extracting(PlayerProfile::playerId).contains("director-9");
            MappedIdentity identity = fixture.service.resolve("qq-d9").orElseThrow();
            assertThat(identity.actor().role()).isEqualTo(ActorRole.DIRECTOR);
            assertThat(identity.isOperator()).isTrue();
            assertThat(fixture.sessions.find("director-9")).isEmpty();
            assertThat(count(store, DomainEventTypes.CONTROL_DIRECTOR_REGISTERED)).isEqualTo(1);

            assertThat(fixture.service.registerPlayer(
                    "player-10", "qq-p10", "绑定玩家", "bind-player-10").ok()).isTrue();
            assertThat(fixture.sessions.find("player-10")).isPresent();
            SecretarySession session = fixture.sessions.find("player-10").orElseThrow();
            ControlOperationResult firstBind = fixture.service.bindSecretary(
                    "player-10", "bind-1");
            assertThat(firstBind.ok()).isTrue();
            assertThat(firstBind.data()).containsEntry("secretaryBound", true);
            ControlOperationResult repeatBind = fixture.service.bindSecretary(
                    "player-10", "bind-2");
            assertThat(repeatBind.ok()).isTrue();
            assertThat(repeatBind.data()).containsEntry("idempotent", true);
            assertThat(fixture.sessions.find("player-10")).contains(session);

            ControlOperationResult missing = fixture.service.bindSecretary(
                    "player-missing", "bind-missing");
            assertThat(missing.ok()).isFalse();
            assertThat(missing.code()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void loadWorldSupportsVisibilityAndInvalidFactsLeaveEngineUnchanged() {
        Path db = tempDir.resolve("load-world.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);
            assertThat(fixture.service.registerPlayer(
                    "player-9", "qq-p9", "世界玩家", "world-player").ok()).isTrue();

            ControlOperationResult loaded = fixture.service.loadWorld(List.of(
                    new ControlBootstrapService.WorldFactInput(
                            "public-1", "公共事实", "PUBLIC"),
                    new ControlBootstrapService.WorldFactInput(
                            "director-1", "推演秘密", "DIRECTOR_ONLY"),
                    new ControlBootstrapService.WorldFactInput(
                            "private-1", "玩家私有事实", "PLAYERS:player-9")),
                    "load-world-1");
            assertThat(loaded.ok()).as(loaded.toString()).isTrue();
            assertThat(loaded.data()).containsEntry("worldVersion", 1L);
            assertThat(fixture.engine.status().worldVersion()).isEqualTo(1);
            assertThat(fixture.engine.allWorldFacts()).hasSize(3);
            assertThat(fixture.engine.allWorldFacts())
                    .extracting(WorldFact::factId)
                    .containsExactlyInAnyOrder("public-1", "director-1", "private-1");
            assertThat(fixture.engine.allWorldFacts().stream()
                    .filter(fact -> fact.factId().equals("public-1"))
                    .findFirst().orElseThrow().visibility()).isEqualTo(Visibility.PUBLIC);
            assertThat(fixture.engine.allWorldFacts().stream()
                    .filter(fact -> fact.factId().equals("director-1"))
                    .findFirst().orElseThrow().visibility())
                    .isEqualTo(Visibility.DIRECTOR_ONLY);
            assertThat(count(store, DomainEventTypes.CONTROL_WORLD_LOADED)).isEqualTo(1);

            long versionBefore = fixture.engine.status().worldVersion();
            int factsBefore = fixture.engine.allWorldFacts().size();
            ControlOperationResult invalidVisibility = fixture.service.loadWorld(
                    List.of(new ControlBootstrapService.WorldFactInput(
                            "bad-1", "非法可见性", "SECRET")), "load-world-invalid");
            assertThat(invalidVisibility.ok()).isFalse();
            assertThat(invalidVisibility.code()).isEqualTo("INVALID_ARGUMENTS");
            ControlOperationResult unregisteredPlayer = fixture.service.loadWorld(
                    List.of(new ControlBootstrapService.WorldFactInput(
                            "bad-2", "未注册玩家", "PLAYERS:not-registered")),
                    "load-world-unregistered");
            assertThat(unregisteredPlayer.ok()).isFalse();
            assertThat(unregisteredPlayer.code()).isEqualTo("INVALID_ARGUMENTS");
            assertThat(fixture.engine.status().worldVersion()).isEqualTo(versionBefore);
            assertThat(fixture.engine.allWorldFacts()).hasSize(factsBefore);
        }
    }

    @Test
    void sharedEngineIdempotencyKeyAcrossRoleOrProfileConflictsAndCreatesNothing() {
        Path db = tempDir.resolve("idempotency-conflict.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);
            assertThat(fixture.service.registerPlayer(
                    "player-9", "qq-p9", "先注册玩家", "shared-engine-key").ok()).isTrue();

            ControlOperationResult roleConflict = fixture.service.registerDirector(
                    "director-9", "qq-d9", "同 key 推演", "shared-engine-key");
            assertThat(roleConflict.ok()).isFalse();
            assertThat(roleConflict.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
            assertThat(fixture.engine.directorProfiles())
                    .extracting(PlayerProfile::playerId).doesNotContain("director-9");
            assertThat(fixture.service.resolve("qq-d9")).isEmpty();
            assertThat(fixture.sessions.find("director-9")).isEmpty();
            assertThatThrownBy(() -> fixture.workspaces.rootFor("director-9"))
                    .isInstanceOf(IllegalArgumentException.class);

            ControlOperationResult profileConflict = fixture.service.registerPlayer(
                    "player-4", "qq-p4", "同 key 不同玩家", "shared-engine-key");
            assertThat(profileConflict.ok()).isFalse();
            assertThat(profileConflict.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
            assertThat(fixture.engine.playerProfiles())
                    .extracting(PlayerProfile::playerId)
                    .containsExactly("player-9");
            assertThat(fixture.service.resolve("qq-p4")).isEmpty();
            assertThat(fixture.sessions.find("player-4")).isEmpty();
            assertThatThrownBy(() -> fixture.workspaces.rootFor("player-4"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void engineIdempotencyReplayPostconditionMismatchCreatesNoRouteWorkspaceOrSession() {
        Path db = tempDir.resolve("idempotency-replay.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);
            assertThat(fixture.service.registerPlayer(
                    "player-9", "qq-p9", "原始玩家", "replay-engine-key").ok()).isTrue();

            // 第二个服务实例没有本地 fingerprint 缓存，会真实走到 engine 的旧 key replay。
            ControlBootstrapService replayService = rebuild(fixture, store);
            ControlOperationResult replay = replayService.registerPlayer(
                    "player-4", "qq-p4", "Replay 玩家", "replay-engine-key");
            assertThat(replay.ok()).isFalse();
            assertThat(replay.code()).isEqualTo("ENGINE_POSTCONDITION_MISMATCH");
            assertThat(fixture.engine.playerProfiles())
                    .extracting(PlayerProfile::playerId)
                    .containsExactly("player-9");
            assertThat(replayService.resolve("qq-p4")).isEmpty();
            assertThat(fixture.sessions.find("player-4")).isEmpty();
            assertThatThrownBy(() -> fixture.workspaces.rootFor("player-4"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(replayService.resolve("qq-p9")).isPresent();
        }
    }

    @Test
    void loadWorldSameKeyReplayKeepsVersionAndDifferentFactsConflicts() {
        Path db = tempDir.resolve("world-idempotency.db");
        try (EventContentStore store = new EventContentStore(db)) {
            Fixture fixture = fixture(store);
            List<ControlBootstrapService.WorldFactInput> facts = List.of(
                    new ControlBootstrapService.WorldFactInput(
                            "fact-a", "第一版世界事实", "PUBLIC"));
            ControlOperationResult first = fixture.service.loadWorld(facts, "shared-world-key");
            assertThat(first.ok()).isTrue();
            assertThat(fixture.engine.status().worldVersion()).isEqualTo(1);

            ControlOperationResult replay = fixture.service.loadWorld(facts, "shared-world-key");
            assertThat(replay.ok()).isTrue();
            assertThat(fixture.engine.status().worldVersion()).isEqualTo(1);
            assertThat(fixture.engine.allWorldFacts()).hasSize(1);

            ControlOperationResult conflict = fixture.service.loadWorld(
                    List.of(new ControlBootstrapService.WorldFactInput(
                            "fact-b", "不同世界事实", "PUBLIC")), "shared-world-key");
            assertThat(conflict.ok()).isFalse();
            assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
            assertThat(fixture.engine.status().worldVersion()).isEqualTo(1);
            assertThat(fixture.engine.allWorldFacts())
                    .extracting(WorldFact::factId)
                    .containsExactly("fact-a");
        }
    }

    private Fixture fixture(EventContentStore store) {
        TaskflowEngine engine = new TaskflowEngine(CLOCK, new InMemoryEventStore());
        SecretarySessionRegistry sessions = new SecretarySessionRegistry();
        SecretaryWorkspaceStore workspaces = SecretaryWorkspaceStore.open(
                tempDir.resolve("workspaces"), List.of());
        Function<PlayerProfile, SecretarySession> sessionFactory = profile ->
                new SecretarySession(profile.playerId(),
                        new Actor(profile.playerId(), ActorRole.PLAYER), engine,
                        FakeLlmClient.with(),
                        (playerId, question, facts) -> new BriefingResult("", Set.of()),
                        BRIEF_ISSUER, CLOCK, workspaces);
        ControlBootstrapService service = ControlBootstrapService
                .builder(engine, "group-initial", SUPERVISOR, CLOCK)
                .sessions(sessions)
                .workspaces(workspaces)
                .eventContentStore(store)
                .journal(new SqliteDomainEventJournal(store))
                .sessionFactory(sessionFactory)
                .build();
        return new Fixture(engine, sessions, workspaces, sessionFactory, service);
    }

    private ControlBootstrapService rebuild(Fixture fixture, EventContentStore store) {
        return builder(fixture, store).build();
    }

    private ControlBootstrapService.Builder builder(Fixture fixture, EventContentStore store) {
        return ControlBootstrapService
                .builder(fixture.engine, "group-initial", SUPERVISOR, CLOCK)
                .sessions(fixture.sessions)
                .workspaces(fixture.workspaces)
                .eventContentStore(store)
                .journal(new SqliteDomainEventJournal(store))
                .sessionFactory(fixture.sessionFactory);
    }

    private static long count(EventContentStore store, String eventType) {
        return store.readAfter(0, 10_000).stream()
                .filter(event -> event.eventType().equals(eventType))
                .count();
    }

    private record Fixture(TaskflowEngine engine, SecretarySessionRegistry sessions,
                           SecretaryWorkspaceStore workspaces,
                           Function<PlayerProfile, SecretarySession> sessionFactory,
                           ControlBootstrapService service) {
    }
}
