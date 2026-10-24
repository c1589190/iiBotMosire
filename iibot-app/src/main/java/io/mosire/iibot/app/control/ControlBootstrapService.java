package io.mosire.iibot.app.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.app.event.SqliteDomainEventJournal;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.ErrorCode;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.PlayerProfile;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.agent.MappedIdentity;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 运行期控制初始化适配器。
 *
 * <p>身份事实源是 {@link TaskflowEngine} 的玩家/推演 profile；本服务只在控制面缓存
 * QQ 路由与本地 {@link ControlStateSnapshot}，不复制第二套身份状态。启动时从引擎重建
 * 动态映射，并为每个玩家幂等建立 workspace 与秘书 Session。所有写方法串行执行，事件
 * 追加失败不会替换内存控制状态。</p>
 */
public final class ControlBootstrapService {

    private static final Logger LOG = LoggerFactory.getLogger(ControlBootstrapService.class);

    public static final String SUPERVISOR_ACTOR_ID = "external-supervisor";
    public static final int MAX_PLAYER_ID_CHARS = 128;
    public static final int MAX_QQ_ID_CHARS = 64;
    public static final int MAX_DISPLAY_NAME_CHARS = 128;
    public static final int MAX_GROUP_ID_CHARS = 128;
    public static final int MAX_FACT_ID_CHARS = 128;
    public static final int MAX_FACT_CONTENT_CHARS = 65_536;
    public static final int MAX_FACTS_PER_LOAD = 10_000;
    public static final int MAX_IDEMPOTENCY_KEY_CHARS = 200;
    public static final int MAX_VISIBILITY_CHARS = 1_024;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SAFE_VISIBILITY = Pattern.compile("[A-Za-z0-9_.,:-]{1,1024}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskflowEngine engine;
    private final SecretarySessionRegistry sessions;
    private final SecretaryWorkspaceStore workspaces;
    private final EventContentStore eventContentStore;
    private final DomainEventJournal journal;
    private final String startupPublicGroupId;
    private final Actor supervisor;
    private final Clock clock;
    private final Function<PlayerProfile, SecretarySession> sessionFactory;
    private final Runnable worldDocsRefresh;
    private final Object mutex = new Object();
    private final Map<String, ControlOperationResult> idempotentResults = new LinkedHashMap<>();
    /** engine idempotencyKey → 完整请求 fingerprint；用于阻止同 key 被不同请求复用。 */
    private final Map<String, String> engineRequestFingerprints = new LinkedHashMap<>();

    private volatile Map<String, MappedIdentity> identitiesByQq = Map.of();
    private volatile Map<String, String> qqByPlayerId = Map.of();
    private volatile Map<String, String> ackTextByQq = Map.of();
    private volatile ControlStateSnapshot state;
    private volatile boolean initialized;

    /** 完整生产构造器：events.db 读取 + journal + 动态秘书工厂 + 世界文档刷新。 */
    public ControlBootstrapService(TaskflowEngine engine,
                                   SecretarySessionRegistry sessions,
                                   SecretaryWorkspaceStore workspaces,
                                   EventContentStore eventContentStore,
                                   DomainEventJournal journal,
                                   String startupPublicGroupId,
                                   Actor supervisor,
                                   Clock clock,
                                   Function<PlayerProfile, SecretarySession> sessionFactory,
                                   Runnable worldDocsRefresh) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.eventContentStore = eventContentStore;
        this.journal = journal != null ? journal
                : eventContentStore == null ? null : new SqliteDomainEventJournal(eventContentStore);
        this.startupPublicGroupId = requireGroupId(startupPublicGroupId);
        this.supervisor = requireSupervisor(supervisor);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionFactory = sessionFactory;
        this.worldDocsRefresh = worldDocsRefresh;
        synchronized (mutex) {
            restoreStateLocked();
            rebuildIdentitiesLocked();
        }
    }

    /** 生产便捷构造器：journal 由共享 EventContentStore 构造。 */
    public ControlBootstrapService(TaskflowEngine engine,
                                   SecretarySessionRegistry sessions,
                                   SecretaryWorkspaceStore workspaces,
                                   EventContentStore eventContentStore,
                                   String startupPublicGroupId,
                                   Actor supervisor,
                                   Clock clock,
                                   Function<PlayerProfile, SecretarySession> sessionFactory) {
        this(engine, sessions, workspaces, eventContentStore, null, startupPublicGroupId,
                supervisor, clock, sessionFactory, null);
    }

    /** 仅恢复控制状态/身份路由的轻量构造器；无 workspace/session 装配。 */
    public ControlBootstrapService(TaskflowEngine engine, EventContentStore eventContentStore,
                                   String startupPublicGroupId, Clock clock, Actor supervisor) {
        this(engine, null, null, eventContentStore, null, startupPublicGroupId,
                supervisor, clock, null, null);
    }

    public static Builder builder(TaskflowEngine engine, String startupPublicGroupId,
                                  Actor supervisor, Clock clock) {
        return new Builder(engine, startupPublicGroupId, supervisor, clock);
    }

    /** 每次查询都从当前不可变路由快照解析；不访问 LLM/文件，也不写事件。 */
    public Optional<MappedIdentity> resolve(String qqId) {
        if (qqId == null || qqId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(identitiesByQq.get(qqId));
    }

    public String publicGroupId() {
        return state.publicGroupId();
    }

    public Optional<String> qqForPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(qqByPlayerId.get(playerId));
    }

    /** 动态分发端口便捷形式：未知玩家返回 null；调用方必须按缺失映射 fail-closed。 */
    public String qqForPlayerOrNull(String playerId) {
        return qqForPlayer(playerId).orElse(null);
    }

    public Optional<String> ackTextByQq(String qqId) {
        if (qqId == null || qqId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ackTextByQq.get(qqId));
    }

    /** 当前 ACK 路由的不可变快照（仅诊断/测试，动态策略应每次调用单 QQ 查询）。 */
    public Map<String, String> ackTextByQq() {
        return ackTextByQq;
    }

    public ControlStateSnapshot state() {
        return state;
    }

    /** 初始化幂等摘要；首次调用追加 CONTROL_INITIALIZED 完整控制状态快照。 */
    public ControlOperationResult initialize() {
        synchronized (mutex) {
            if (initialized) {
                return ControlOperationResult.success(controlSummaryLocked());
            }
            ControlStateSnapshot snapshot = state;
            try {
                appendControlEventLocked(DomainEventTypes.CONTROL_INITIALIZED,
                        snapshot, stateSummaryLocked(snapshot), "control-initialize");
            } catch (RuntimeException failure) {
                LOG.error("控制初始化事件追加失败 type={}",
                        failure.getClass().getSimpleName());
                return ControlOperationResult.failure("CONTROL_STATE_APPEND_FAILED",
                        "控制初始化状态写入失败，未改变内存状态");
            }
            initialized = true;
            return ControlOperationResult.success(controlSummaryLocked());
        }
    }

    /** 便捷入口：以确定性本地幂等键设置公开群。 */
    public ControlOperationResult setPublicGroup(String publicGroupId) {
        return setPublicGroup(publicGroupId, "control-set-public-group:" + publicGroupId);
    }

    /** 设置公开群。事件 append 成功后才替换内存值；同群重复调用幂等。 */
    public ControlOperationResult setPublicGroup(String publicGroupId, String idempotencyKey) {
        String key = idempotencyKey(idempotencyKey, "control-set-public-group:" + publicGroupId);
        synchronized (mutex) {
            ControlOperationResult cached = idempotentResults.get("set-public-group:" + key);
            if (cached != null) {
                return cached;
            }
            String groupId;
            try {
                groupId = requireGroupId(publicGroupId);
            } catch (IllegalArgumentException invalid) {
                return memoize("set-public-group:" + key,
                        ControlOperationResult.failure("INVALID_ARGUMENTS", invalid.getMessage()));
            }
            if (groupId.equals(state.publicGroupId())) {
                return memoize("set-public-group:" + key,
                        ControlOperationResult.success(controlSummaryLocked()));
            }
            ControlStateSnapshot next = new ControlStateSnapshot(
                    state.revision() + 1, groupId, state.initializedAt());
            long eventSeq;
            try {
                eventSeq = appendControlEventLocked(DomainEventTypes.CONTROL_STATE,
                        next, stateSummaryLocked(next), "control-set-public-group:" + key);
            } catch (RuntimeException failure) {
                LOG.error("控制状态事件追加失败 type={}",
                        failure.getClass().getSimpleName());
                return memoize("set-public-group:" + key,
                        ControlOperationResult.failure("CONTROL_STATE_APPEND_FAILED",
                                "公开群状态写入失败，未改变内存状态"));
            }
            state = next;
            initialized = true;
            Map<String, Object> data = controlSummaryLocked();
            data.put("eventSeq", eventSeq);
            return memoize("set-public-group:" + key,
                    ControlOperationResult.success(data));
        }
    }

    public ControlOperationResult registerPlayer(String playerId, String qqId,
                                                 String displayName) {
        return registerPlayer(playerId, qqId, displayName,
                "control-register-player:" + playerId);
    }

    public ControlOperationResult registerPlayer(String playerId, String qqId,
                                                 String displayName, String idempotencyKey) {
        return registerPlayer(playerId, qqId, displayName, idempotencyKey, "");
    }

    /** 注册玩家；相同完整身份重复调用幂等返回。 */
    public ControlOperationResult registerPlayer(String playerId, String qqId,
                                                 String displayName, String idempotencyKey,
                                                 String reason) {
        return registerIdentity(playerId, qqId, displayName, idempotencyKey, reason,
                ActorRole.PLAYER);
    }

    public ControlOperationResult registerDirector(String playerId, String qqId,
                                                   String displayName) {
        return registerDirector(playerId, qqId, displayName,
                "control-register-director:" + playerId);
    }

    public ControlOperationResult registerDirector(String playerId, String qqId,
                                                   String displayName, String idempotencyKey) {
        return registerDirector(playerId, qqId, displayName, idempotencyKey, "");
    }

    /** 注册推演；推演不创建秘书 Session。 */
    public ControlOperationResult registerDirector(String playerId, String qqId,
                                                   String displayName, String idempotencyKey,
                                                   String reason) {
        return registerIdentity(playerId, qqId, displayName, idempotencyKey, reason,
                ActorRole.DIRECTOR);
    }

    /** 便捷入口：以确定性本地幂等键绑定秘书。 */
    public ControlOperationResult bindSecretary(String playerId) {
        return bindSecretary(playerId, "control-bind-secretary:" + playerId);
    }

    /** 绑定玩家秘书；玩家必须已注册，已存在 Session 时幂等返回既有。 */
    public ControlOperationResult bindSecretary(String playerId, String idempotencyKey) {
        String key = idempotencyKey(idempotencyKey, "control-bind-secretary:" + playerId);
        synchronized (mutex) {
            ControlOperationResult cached = idempotentResults.get("bind-secretary:" + key);
            if (cached != null) {
                return cached;
            }
            String normalizedPlayerId;
            try {
                normalizedPlayerId = requirePlayerId(playerId);
            } catch (IllegalArgumentException invalid) {
                return memoize("bind-secretary:" + key,
                        ControlOperationResult.failure("INVALID_ARGUMENTS", invalid.getMessage()));
            }
            PlayerProfile profile = playerProfileLocked(normalizedPlayerId).orElse(null);
            if (profile == null) {
                return memoize("bind-secretary:" + key,
                        ControlOperationResult.failure("NOT_FOUND", "玩家尚未注册"));
            }
            if (sessions == null || sessionFactory == null) {
                return memoize("bind-secretary:" + key,
                        ControlOperationResult.failure("SECRETARY_SESSION_UNAVAILABLE",
                                "秘书会话依赖未装配"));
            }
            boolean existed = sessions.find(normalizedPlayerId).isPresent();
            try {
                ensureWorkspaceAndSessionLocked(profile);
            } catch (RuntimeException failure) {
                LOG.error("秘书会话装配失败 playerId={} type={}",
                        normalizedPlayerId, failure.getClass().getSimpleName());
                return memoize("bind-secretary:" + key,
                        ControlOperationResult.failure("SECRETARY_SESSION_UNAVAILABLE",
                                "秘书会话装配失败（内部错误已脱敏）"));
            }
            if (!existed) {
                appendAuditBestEffortLocked(DomainEventTypes.CONTROL_SECRETARY_BOUND,
                        Map.of("playerId", normalizedPlayerId, "role", ActorRole.PLAYER.name()),
                        Map.of("playerId", normalizedPlayerId),
                        "control-bind-secretary:" + key);
            }
            Map<String, Object> data = identitySummaryLocked(profile, ActorRole.PLAYER);
            data.put("secretaryBound", true);
            data.put("idempotent", existed);
            return memoize("bind-secretary:" + key,
                    ControlOperationResult.success(data));
        }
    }

    /** 便捷入口：以确定性本地幂等键装载一批事实。 */
    public ControlOperationResult loadWorld(List<?> facts) {
        return loadWorld(facts, "control-load-world");
    }

    /**
     * 装载世界事实。输入项支持 {@link WorldFactInput} 或含
     * {@code factId/content/visibility} 的 Map；worldVersion 固定为当前值 + 1。
     */
    public ControlOperationResult loadWorld(List<?> facts, String idempotencyKey) {
        String key = idempotencyKey(idempotencyKey, "control-load-world");
        synchronized (mutex) {
            if (facts == null || facts.isEmpty() || facts.size() > MAX_FACTS_PER_LOAD) {
                return ControlOperationResult.failure("INVALID_ARGUMENTS",
                        "世界事实数量非法");
            }
            long currentVersion = engine.status().worldVersion();
            long targetVersion = currentVersion + 1;
            List<WorldFact> worldFacts = new ArrayList<>(facts.size());
            Set<String> factIds = new LinkedHashSet<>();
            try {
                for (Object raw : facts) {
                    WorldFactInput input = parseWorldFactInput(raw);
                    if (!factIds.add(input.factId())) {
                        return ControlOperationResult.failure("INVALID_ARGUMENTS",
                                "同一批次 factId 重复");
                    }
                    Visibility visibility = parseVisibility(input.visibility());
                    worldFacts.add(new WorldFact(input.factId(), targetVersion,
                            input.content(), visibility));
                }
            } catch (IllegalArgumentException invalid) {
                return ControlOperationResult.failure("INVALID_ARGUMENTS",
                        invalid.getMessage() == null ? "世界事实参数非法"
                                : invalid.getMessage());
            }

            ControlOperationResult cached = idempotentResults.get("load-world:" + key);
            // 重放时引擎 worldVersion 已推进；必须用首次成功回执中的 targetVersion 重建
            // fingerprint，否则同一请求会被误判成不同目标版本。
            long fingerprintVersion = cached == null ? targetVersion
                    : cached.data().get("worldVersion") instanceof Number number
                    ? number.longValue() : targetVersion;
            String fingerprint = worldFingerprint(fingerprintVersion, worldFacts);
            ControlOperationResult keyConflict = engineFingerprintConflictLocked(
                    key, fingerprint);
            if (keyConflict != null) {
                return keyConflict;
            }
            if (cached != null) {
                return cached;
            }
            rememberEngineFingerprintLocked(key, fingerprint);

            CommandResult result = engine.loadWorldFacts(supervisor, currentVersion,
                    worldFacts, engine.status().engineRevision(), key);
            if (result == null || !result.ok()) {
                return fromEngineFailure(result,
                        "CONTROL_WORLD_LOAD_FAILED", "世界事实装载失败");
            }
            // engine idempotency replay 可能返回旧 key 的成功回执。只有回查目标版本与
            // 全部 fact 内容/可见性都一致，才允许返回成功、刷新 docs 或缓存回执。
            if (!worldPostconditionLocked(targetVersion, worldFacts)) {
                LOG.error("世界装载 engine 后置校验失败 targetWorldVersion={} factCount={}",
                        targetVersion, worldFacts.size());
                return ControlOperationResult.failure("ENGINE_POSTCONDITION_MISMATCH",
                        "世界装载后置校验失败");
            }
            Map<String, Object> eventSummary = new LinkedHashMap<>();
            eventSummary.put("worldVersion", Long.toString(targetVersion));
            eventSummary.put("factCount", worldFacts.size());
            eventSummary.put("factIds", List.copyOf(factIds));
            appendAuditBestEffortLocked(DomainEventTypes.CONTROL_WORLD_LOADED,
                    Map.of("worldVersion", targetVersion,
                            "factIds", List.copyOf(factIds)),
                    eventSummary, "control-load-world:" + key);
            refreshWorldDocsBestEffortLocked();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("worldVersion", targetVersion);
            data.put("factCount", worldFacts.size());
            data.put("factIds", List.copyOf(factIds));
            data.put("eventSeq", result.eventSeq());
            data.put("engineRevision", result.engineRevision());
            data.put("domainCode", result.code().name());
            data.put("domainMessage", result.message());
            if (!result.details().isEmpty()) {
                data.put("details", result.details());
            }
            return memoize("load-world:" + key,
                    ControlOperationResult.success(data, result));
        }
    }

    /** 单条世界事实便捷入口。 */
    public ControlOperationResult loadWorld(String factId, String content,
                                            String visibility, String idempotencyKey) {
        return loadWorld(List.of(new WorldFactInput(factId, content, visibility)), idempotencyKey);
    }

    /** 控制面摘要：不含正文、QQ 凭据或物理路径。 */
    public Map<String, Object> summary() {
        synchronized (mutex) {
            return controlSummaryLocked();
        }
    }

    private ControlOperationResult registerIdentity(String playerId, String qqId,
                                                    String displayName, String idempotencyKey,
                                                    String reason, ActorRole role) {
        String cachePrefix = role == ActorRole.PLAYER
                ? "register-player:" : "register-director:";
        String key = idempotencyKey(idempotencyKey,
                cachePrefix + playerId + ":" + qqId);
        synchronized (mutex) {
            PlayerProfile profile;
            try {
                profile = new PlayerProfile(requirePlayerId(playerId), requireQqId(qqId),
                        requireDisplayName(displayName));
            } catch (IllegalArgumentException invalid) {
                return ControlOperationResult.failure("INVALID_ARGUMENTS", invalid.getMessage());
            }
            String fingerprint = identityFingerprint(role, profile);
            ControlOperationResult keyConflict = engineFingerprintConflictLocked(
                    key, fingerprint);
            if (keyConflict != null) {
                return keyConflict;
            }
            ControlOperationResult cached = idempotentResults.get(cachePrefix + key);
            if (cached != null) {
                return cached;
            }

            ExistingIdentity samePlayer = findIdentityByPlayerIdLocked(profile.playerId());
            ExistingIdentity sameQq = findIdentityByQqLocked(profile.qqId());
            if (samePlayer != null || sameQq != null) {
                ExistingIdentity existing = samePlayer != null ? samePlayer : sameQq;
                if (samePlayer != null && sameQq != null
                        && !(samePlayer.profile().equals(sameQq.profile())
                        && samePlayer.role() == sameQq.role())) {
                    return ControlOperationResult.failure("CONFLICT",
                            "QQ 与 playerId 已分别绑定到不同身份");
                }
                if (existing.role() == role && existing.profile().equals(profile)) {
                    try {
                        if (role == ActorRole.PLAYER) {
                            ensureWorkspaceAndSessionLocked(profile);
                        }
                    } catch (RuntimeException failure) {
                        LOG.error("幂等注册后的秘书装配失败 playerId={} type={}",
                                profile.playerId(), failure.getClass().getSimpleName());
                        return ControlOperationResult.failure(
                                "SECRETARY_SESSION_UNAVAILABLE",
                                "秘书会话装配失败（内部错误已脱敏）");
                    }
                    rememberEngineFingerprintLocked(key, fingerprint);
                    Map<String, Object> data = identitySummaryLocked(profile, role);
                    data.put("idempotent", true);
                    return memoize(cachePrefix + key,
                            ControlOperationResult.success(data));
                }
                return ControlOperationResult.failure("CONFLICT",
                        "身份映射已存在且与请求不一致");
            }

            // 在触碰 engine 前只做依赖检查；workspace/Session 必须在 postcondition 通过后
            // 才允许建立，避免 engine idempotency replay 命中旧结果时污染运行期路由。
            if (role == ActorRole.PLAYER && (sessions == null || sessionFactory == null)) {
                return ControlOperationResult.failure("SECRETARY_SESSION_UNAVAILABLE",
                        "秘书会话依赖未装配（未注册身份）");
            }
            rememberEngineFingerprintLocked(key, fingerprint);

            CommandResult result = role == ActorRole.PLAYER
                    ? engine.registerPlayer(supervisor, profile,
                            engine.status().engineRevision(), key)
                    : engine.registerDirector(supervisor, profile,
                            engine.status().engineRevision(), key);
            if (result == null || !result.ok()) {
                return fromEngineFailure(result,
                        role == ActorRole.PLAYER
                                ? "CONTROL_PLAYER_REGISTER_FAILED"
                                : "CONTROL_DIRECTOR_REGISTER_FAILED",
                        "身份注册失败");
            }
            // engine 可能用同 key 返回旧成功回执；必须验证 referenceId 与回查 profile，
            // 否则不得更新 route/workspace/session，也不得缓存为成功。
            if (!identityPostconditionLocked(role, profile, result)) {
                LOG.error("身份注册 engine 后置校验失败 role={} playerId={}",
                        role, profile.playerId());
                return ControlOperationResult.failure("ENGINE_POSTCONDITION_MISMATCH",
                        "身份注册后置校验失败");
            }

            try {
                putIdentityLocked(profile, role);
            } catch (IllegalArgumentException conflict) {
                LOG.error("引擎注册成功但控制路由更新冲突 playerId={}",
                        profile.playerId());
                return ControlOperationResult.failure("CONFLICT",
                        "身份已由引擎提交，但控制路由更新冲突");
            }

            boolean sessionFailure = false;
            if (role == ActorRole.PLAYER) {
                try {
                    ensureWorkspaceAndSessionLocked(profile);
                } catch (RuntimeException failure) {
                    sessionFailure = true;
                    LOG.error("动态玩家秘书会话创建失败 playerId={} type={}",
                            profile.playerId(), failure.getClass().getSimpleName());
                }
            }

            String eventType = role == ActorRole.PLAYER
                    ? DomainEventTypes.CONTROL_PLAYER_REGISTERED
                    : DomainEventTypes.CONTROL_DIRECTOR_REGISTERED;
            appendAuditBestEffortLocked(eventType,
                    Map.of("playerId", profile.playerId(), "role", role.name()),
                    Map.of("playerId", profile.playerId(), "role", role.name()),
                    "control-register:" + key);

            Map<String, Object> data = identitySummaryLocked(profile, role);
            data.put("idempotent", false);
            data.put("eventSeq", result.eventSeq());
            data.put("engineEventSeq", result.eventSeq());
            data.put("domainCode", result.code().name());
            boolean sessionPresent = sessions != null
                    && sessions.find(profile.playerId()).isPresent();
            data.put("secretaryBound", role == ActorRole.PLAYER && !sessionFailure
                    && sessionPresent);
            if (sessionFailure) {
                data.put("identityCommitted", true);
                return new ControlOperationResult(false, "SECRETARY_SESSION_UNAVAILABLE",
                        "身份已注册，但秘书会话装配失败", data, result);
            }
            return memoize(cachePrefix + key,
                    ControlOperationResult.success(data, result));
        }
    }

    private ControlOperationResult engineFingerprintConflictLocked(String engineKey,
                                                                   String fingerprint) {
        String previous = engineRequestFingerprints.get(engineKey);
        if (previous != null && !previous.equals(fingerprint)) {
            return ControlOperationResult.failure("IDEMPOTENCY_KEY_CONFLICT",
                    "同一幂等键已被不同请求使用");
        }
        return null;
    }

    private void rememberEngineFingerprintLocked(String engineKey, String fingerprint) {
        String previous = engineRequestFingerprints.putIfAbsent(engineKey, fingerprint);
        if (previous != null && !previous.equals(fingerprint)) {
            throw new IllegalStateException("engine idempotency key fingerprint 冲突");
        }
    }

    private boolean identityPostconditionLocked(ActorRole role, PlayerProfile profile,
                                                CommandResult result) {
        if (result == null || !result.ok()
                || result.referenceId().filter(profile.playerId()::equals).isEmpty()) {
            return false;
        }
        var profiles = role == ActorRole.PLAYER
                ? engine.playerProfiles() : engine.directorProfiles();
        return profiles.stream().filter(profile::equals).count() == 1;
    }

    private boolean worldPostconditionLocked(long targetVersion, List<WorldFact> requested) {
        if (engine.status().worldVersion() != targetVersion) {
            return false;
        }
        Map<String, WorldFact> actual = new LinkedHashMap<>();
        for (WorldFact fact : engine.allWorldFacts()) {
            actual.put(fact.factId(), fact);
        }
        for (WorldFact expected : requested) {
            if (!expected.equals(actual.get(expected.factId()))) {
                return false;
            }
        }
        return true;
    }

    private static String identityFingerprint(ActorRole role, PlayerProfile profile) {
        return requestFingerprint("REGISTER_" + role.name(), -1,
                List.of(profile.playerId(), profile.qqId(), profile.displayName()));
    }

    private static String worldFingerprint(long targetVersion, List<WorldFact> facts) {
        List<WorldFact> ordered = new ArrayList<>(facts);
        ordered.sort(java.util.Comparator.comparing(WorldFact::factId));
        List<String> fields = new ArrayList<>(ordered.size() * 3 + 2);
        fields.add(Long.toString(targetVersion));
        fields.add(Integer.toString(ordered.size()));
        for (WorldFact fact : ordered) {
            fields.add(fact.factId());
            fields.add(fact.content());
            fields.add(visibilityText(fact.visibility()));
        }
        return requestFingerprint("LOAD_WORLD", targetVersion, fields);
    }

    private static String requestFingerprint(String command, long version,
                                             List<String> fields) {
        StringBuilder canonical = new StringBuilder();
        appendFingerprintField(canonical, command);
        appendFingerprintField(canonical, Long.toString(version));
        for (String field : fields) {
            appendFingerprintField(canonical, field);
        }
        return Hashes.sha256(canonical.toString());
    }

    private static void appendFingerprintField(StringBuilder canonical, String value) {
        String safe = value == null ? "" : value;
        canonical.append(safe.length()).append(':').append(safe).append('|');
    }

    private void restoreStateLocked() {
        Optional<StoredEvent> latest = latestControlStateLocked();
        if (latest.isEmpty()) {
            state = new ControlStateSnapshot(0, startupPublicGroupId, clock.instant());
            initialized = false;
            return;
        }
        StoredEvent event = latest.orElseThrow();
        if (event.contentMissing() || event.content() == null
                || event.content().json() == null) {
            throw new IllegalStateException("CONTROL_STATE_CORRUPT 控制状态事件缺少正文");
        }
        try {
            JsonNode root = JSON.readTree(event.content().json());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("not object");
            }
            JsonNode revisionNode = root.get("revision");
            JsonNode groupNode = root.get("publicGroupId");
            JsonNode initializedNode = root.get("initializedAt");
            if (revisionNode == null || !revisionNode.isIntegralNumber()
                    || groupNode == null || !groupNode.isTextual()
                    || initializedNode == null || !initializedNode.isTextual()) {
                throw new IllegalArgumentException("missing field");
            }
            state = new ControlStateSnapshot(revisionNode.asLong(),
                    requireGroupId(groupNode.asText()),
                    Instant.parse(initializedNode.asText()));
            initialized = true;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("CONTROL_STATE_CORRUPT 控制状态事件不可解析");
        }
    }

    private Optional<StoredEvent> latestControlStateLocked() {
        if (eventContentStore == null) {
            return Optional.empty();
        }
        Optional<StoredEvent> stateEvent = eventContentStore.latestByType(
                DomainEventTypes.CONTROL_STATE);
        if (stateEvent.isPresent()) {
            return stateEvent;
        }
        return eventContentStore.latestByType(DomainEventTypes.CONTROL_INITIALIZED);
    }

    private void rebuildIdentitiesLocked() {
        Map<String, MappedIdentity> byQq = new LinkedHashMap<>();
        Map<String, String> qqByPlayer = new LinkedHashMap<>();
        Map<String, String> ack = new LinkedHashMap<>();
        for (PlayerProfile profile : engine.playerProfiles()) {
            addIdentity(profile, ActorRole.PLAYER, byQq, qqByPlayer, ack);
        }
        for (PlayerProfile profile : engine.directorProfiles()) {
            addIdentity(profile, ActorRole.DIRECTOR, byQq, qqByPlayer, ack);
        }
        identitiesByQq = Map.copyOf(byQq);
        qqByPlayerId = Map.copyOf(qqByPlayer);
        ackTextByQq = Map.copyOf(ack);

        // 所有 engine 玩家（含重启恢复的动态玩家）都必须可重建秘书会话。
        for (PlayerProfile profile : engine.playerProfiles()) {
            ensureWorkspaceAndSessionLocked(profile);
        }
    }

    private void addIdentity(PlayerProfile profile, ActorRole role,
                             Map<String, MappedIdentity> byQq,
                             Map<String, String> qqByPlayer,
                             Map<String, String> ack) {
        if (byQq.containsKey(profile.qqId()) || qqByPlayer.containsKey(profile.playerId())) {
            throw new IllegalStateException("CONTROL_IDENTITY_DUPLICATE engine 身份映射重复");
        }
        Actor actor = new Actor(profile.playerId(), role);
        MappedIdentity identity = new MappedIdentity(profile.qqId(), actor, profile);
        byQq.put(profile.qqId(), identity);
        qqByPlayer.put(profile.playerId(), profile.qqId());
        ack.put(profile.qqId(), role == ActorRole.PLAYER
                ? "消息已收到，秘书正在处理，请稍候。"
                : "消息已收到，GM 正在处理，请稍候。");
    }

    private void putIdentityLocked(PlayerProfile profile, ActorRole role) {
        Map<String, MappedIdentity> byQq = new LinkedHashMap<>(identitiesByQq);
        Map<String, String> qqByPlayer = new LinkedHashMap<>(qqByPlayerId);
        Map<String, String> ack = new LinkedHashMap<>(ackTextByQq);
        if (byQq.containsKey(profile.qqId()) || qqByPlayer.containsKey(profile.playerId())) {
            throw new IllegalArgumentException("身份映射已存在");
        }
        addIdentity(profile, role, byQq, qqByPlayer, ack);
        identitiesByQq = Map.copyOf(byQq);
        qqByPlayerId = Map.copyOf(qqByPlayer);
        ackTextByQq = Map.copyOf(ack);
    }

    private void ensureWorkspaceAndSessionLocked(PlayerProfile profile) {
        if (workspaces != null) {
            workspaces.ensurePlayer(profile.playerId());
        }
        if (sessions == null || sessionFactory == null) {
            return;
        }
        if (sessions.find(profile.playerId()).isPresent()) {
            return;
        }
        SecretarySession created = sessionFactory.apply(profile);
        if (created == null) {
            throw new IllegalStateException("秘书 Session 工厂返回 null");
        }
        sessions.ensure(created);
    }

    private Optional<PlayerProfile> playerProfileLocked(String playerId) {
        return engine.playerProfiles().stream()
                .filter(profile -> profile.playerId().equals(playerId))
                .findFirst();
    }

    private ExistingIdentity findIdentityByPlayerIdLocked(String playerId) {
        for (PlayerProfile profile : engine.playerProfiles()) {
            if (profile.playerId().equals(playerId)) {
                return new ExistingIdentity(profile, ActorRole.PLAYER);
            }
        }
        for (PlayerProfile profile : engine.directorProfiles()) {
            if (profile.playerId().equals(playerId)) {
                return new ExistingIdentity(profile, ActorRole.DIRECTOR);
            }
        }
        return null;
    }

    private ExistingIdentity findIdentityByQqLocked(String qqId) {
        for (PlayerProfile profile : engine.playerProfiles()) {
            if (profile.qqId().equals(qqId)) {
                return new ExistingIdentity(profile, ActorRole.PLAYER);
            }
        }
        for (PlayerProfile profile : engine.directorProfiles()) {
            if (profile.qqId().equals(qqId)) {
                return new ExistingIdentity(profile, ActorRole.DIRECTOR);
            }
        }
        return null;
    }

    private WorldFactInput parseWorldFactInput(Object raw) {
        if (raw instanceof WorldFactInput input) {
            return new WorldFactInput(requireFactId(input.factId()),
                    requireFactContent(input.content()),
                    requireVisibilityText(input.visibility()));
        }
        if (raw instanceof WorldFact fact) {
            return new WorldFactInput(requireFactId(fact.factId()),
                    requireFactContent(fact.content()), visibilityText(fact.visibility()));
        }
        if (raw instanceof Map<?, ?> map) {
            Object factId = map.get("factId");
            Object content = map.get("content");
            Object visibility = map.get("visibility");
            if (!(factId instanceof String factIdText)
                    || !(content instanceof String contentText)
                    || !(visibility instanceof String visibilityText)) {
                throw new IllegalArgumentException(
                        "世界事实必须提供字符串 factId/content/visibility");
            }
            return new WorldFactInput(requireFactId(factIdText),
                    requireFactContent(contentText), requireVisibilityText(visibilityText));
        }
        throw new IllegalArgumentException("世界事实项类型不支持");
    }

    private Visibility parseVisibility(String raw) {
        String value = requireVisibilityText(raw);
        if ("PUBLIC".equals(value)) {
            return Visibility.PUBLIC;
        }
        if ("DIRECTOR_ONLY".equals(value)) {
            return Visibility.DIRECTOR_ONLY;
        }
        if (value.startsWith("PLAYERS:")) {
            String list = value.substring("PLAYERS:".length()).trim();
            if (list.isEmpty()) {
                throw new IllegalArgumentException("PLAYERS 可见性必须列出至少一个玩家");
            }
            Set<String> playerIds = new LinkedHashSet<>();
            for (String part : list.split(",", -1)) {
                String playerId = requirePlayerId(part.trim());
                if (playerProfileLocked(playerId).isEmpty()) {
                    throw new IllegalArgumentException("PLAYERS 可见性包含未注册玩家");
                }
                playerIds.add(playerId);
            }
            return Visibility.forPlayers(playerIds);
        }
        throw new IllegalArgumentException(
                "visibility 只允许 PUBLIC、DIRECTOR_ONLY 或 PLAYERS:p1,p2");
    }

    private static String visibilityText(Visibility visibility) {
        if (visibility == Visibility.PUBLIC) {
            return "PUBLIC";
        }
        if (visibility == Visibility.DIRECTOR_ONLY) {
            return "DIRECTOR_ONLY";
        }
        if (visibility instanceof Visibility.PlayerSetVisibility playerSet) {
            return "PLAYERS:" + String.join(",", playerSet.playerIds());
        }
        throw new IllegalArgumentException("不支持的世界事实可见性");
    }

    private long appendControlEventLocked(String eventType, Object content,
                                          Map<String, Object> summary, String correlation) {
        if (journal == null) {
            throw new IllegalStateException("CONTROL_EVENT_STORE_UNAVAILABLE");
        }
        return journal.append(new DomainEvent(clock.instant(), eventType,
                ActorRole.SUPERVISOR, supervisor.actorId(), correlation, null,
                EventVisibility.GM, content, summary));
    }

    private void appendAuditBestEffortLocked(String eventType, Object content,
                                             Map<String, Object> summary,
                                             String correlation) {
        try {
            appendControlEventLocked(eventType, content, summary, correlation);
        } catch (RuntimeException failure) {
            // engine 身份已提交时审计失败不回滚；只记录稳定类型，不打印正文/异常消息。
            LOG.error("控制审计事件追加失败 type={} errorType={}",
                    eventType, failure.getClass().getSimpleName());
        }
    }

    private void refreshWorldDocsBestEffortLocked() {
        if (worldDocsRefresh == null) {
            return;
        }
        try {
            worldDocsRefresh.run();
        } catch (RuntimeException failure) {
            LOG.warn("世界文档刷新失败 type={}", failure.getClass().getSimpleName());
        }
    }

    private Map<String, Object> controlSummaryLocked() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revision", state.revision());
        data.put("publicGroupId", state.publicGroupId());
        data.put("initializedAt", state.initializedAt().toString());
        data.put("engineRevision", engine.status().engineRevision());
        data.put("playerCount", engine.status().playerCount());
        data.put("directorCount", engine.status().directorCount());
        data.put("secretaryCount", sessions == null ? 0 : sessions.size());
        data.put("initialized", initialized);
        data.put("playerIds", engine.playerProfiles().stream()
                .map(PlayerProfile::playerId).toList());
        data.put("directorIds", engine.directorProfiles().stream()
                .map(PlayerProfile::playerId).toList());
        return data;
    }

    private Map<String, Object> identitySummaryLocked(PlayerProfile profile, ActorRole role) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playerId", profile.playerId());
        data.put("qqId", profile.qqId());
        data.put("displayName", profile.displayName());
        data.put("role", role.name());
        data.put("engineRevision", engine.status().engineRevision());
        data.put("controlRevision", state.revision());
        return data;
    }

    private Map<String, Object> stateSummaryLocked(ControlStateSnapshot snapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revision", snapshot.revision());
        summary.put("publicGroupId", snapshot.publicGroupId());
        summary.put("playerCount", engine.status().playerCount());
        summary.put("directorCount", engine.status().directorCount());
        return summary;
    }

    private ControlOperationResult fromEngineFailure(CommandResult result,
                                                     String fallbackCode, String fallbackMessage) {
        if (result == null) {
            return ControlOperationResult.failure(fallbackCode, fallbackMessage);
        }
        String code = switch (result.code()) {
            case INVALID_ARGUMENT -> "INVALID_ARGUMENTS";
            case FORBIDDEN -> "FORBIDDEN";
            case REVISION_CONFLICT, WORLD_VERSION_MISMATCH -> "REVISION_CONFLICT";
            case PLAYER_ALREADY_REGISTERED, QQ_ALREADY_REGISTERED -> "CONFLICT";
            case NOT_FOUND, UNKNOWN_PLAYER -> "NOT_FOUND";
            default -> fallbackCode;
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domainCode", result.code().name());
        data.put("engineRevision", result.engineRevision());
        data.put("eventSeq", result.eventSeq());
        data.put("details", result.details());
        return new ControlOperationResult(false, code,
                result.message() == null || result.message().isBlank()
                        ? fallbackMessage : result.message(), data, result);
    }

    private ControlOperationResult memoize(String key, ControlOperationResult result) {
        // 只缓存成功回执。瞬时失败（workspace/session、事件/引擎暂时失败等）必须允许
        // 调用方用同一 key 重试，不得把失败结果伪装成幂等成功。
        if (result != null && result.ok()) {
            idempotentResults.put(key, result);
        }
        return result;
    }

    private static String idempotencyKey(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if (raw.length() > MAX_IDEMPOTENCY_KEY_CHARS) {
            throw new IllegalArgumentException("idempotencyKey 超过长度上限");
        }
        return raw;
    }

    private static String requirePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空");
        }
        if (playerId.length() > MAX_PLAYER_ID_CHARS
                || !SAFE_ID.matcher(playerId).matches()
                || ".".equals(playerId) || "..".equals(playerId)) {
            throw new IllegalArgumentException("playerId 格式非法");
        }
        return playerId;
    }

    private static String requireQqId(String qqId) {
        if (qqId == null || qqId.isBlank()) {
            throw new IllegalArgumentException("qqId 不能为空");
        }
        if (qqId.length() > MAX_QQ_ID_CHARS || !SAFE_ID.matcher(qqId).matches()) {
            throw new IllegalArgumentException("qqId 格式非法");
        }
        return qqId;
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 不能为空");
        }
        if (displayName.length() > MAX_DISPLAY_NAME_CHARS
                || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName 格式非法");
        }
        return displayName;
    }

    private static String requireGroupId(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        if (groupId.length() > MAX_GROUP_ID_CHARS || !SAFE_ID.matcher(groupId).matches()) {
            throw new IllegalArgumentException("publicGroupId 格式非法");
        }
        return groupId;
    }

    private static String requireFactId(String factId) {
        if (factId == null || factId.isBlank()) {
            throw new IllegalArgumentException("factId 不能为空");
        }
        if (factId.length() > MAX_FACT_ID_CHARS || !SAFE_ID.matcher(factId).matches()) {
            throw new IllegalArgumentException("factId 格式非法");
        }
        return factId;
    }

    private static String requireFactContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("世界事实 content 不能为空");
        }
        if (content.length() > MAX_FACT_CONTENT_CHARS) {
            throw new IllegalArgumentException("世界事实 content 超过长度上限");
        }
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\n' || character == '\r' || character == '\t') {
                continue;
            }
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("世界事实 content 含非法控制字符");
            }
        }
        return content;
    }

    private static String requireVisibilityText(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            throw new IllegalArgumentException("visibility 不能为空");
        }
        if (visibility.length() > MAX_VISIBILITY_CHARS
                || !SAFE_VISIBILITY.matcher(visibility).matches()) {
            throw new IllegalArgumentException("visibility 格式非法");
        }
        return visibility;
    }

    private static Actor requireSupervisor(Actor supervisor) {
        Objects.requireNonNull(supervisor, "supervisor");
        if (supervisor.role() != ActorRole.SUPERVISOR) {
            throw new IllegalArgumentException("控制初始化 actor 必须是 SUPERVISOR");
        }
        return supervisor;
    }

    private record ExistingIdentity(PlayerProfile profile, ActorRole role) {
    }

    /** 世界事实输入；worldVersion 由服务统一取当前值 + 1。 */
    public record WorldFactInput(String factId, String content, String visibility) {
        public WorldFactInput {
            Objects.requireNonNull(factId, "factId");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    /** 便于测试/组合根逐步注入可选依赖。 */
    public static final class Builder {
        private final TaskflowEngine engine;
        private final String startupPublicGroupId;
        private final Actor supervisor;
        private final Clock clock;
        private SecretarySessionRegistry sessions;
        private SecretaryWorkspaceStore workspaces;
        private EventContentStore eventContentStore;
        private DomainEventJournal journal;
        private Function<PlayerProfile, SecretarySession> sessionFactory;
        private Runnable worldDocsRefresh;

        private Builder(TaskflowEngine engine, String startupPublicGroupId,
                        Actor supervisor, Clock clock) {
            this.engine = Objects.requireNonNull(engine, "engine");
            this.startupPublicGroupId = startupPublicGroupId;
            this.supervisor = supervisor;
            this.clock = clock;
        }

        public Builder sessions(SecretarySessionRegistry sessions) {
            this.sessions = sessions;
            return this;
        }

        public Builder workspaces(SecretaryWorkspaceStore workspaces) {
            this.workspaces = workspaces;
            return this;
        }

        public Builder eventContentStore(EventContentStore eventContentStore) {
            this.eventContentStore = eventContentStore;
            return this;
        }

        public Builder journal(DomainEventJournal journal) {
            this.journal = journal;
            return this;
        }

        public Builder sessionFactory(Function<PlayerProfile, SecretarySession> sessionFactory) {
            this.sessionFactory = sessionFactory;
            return this;
        }

        public Builder worldDocsRefresh(Runnable worldDocsRefresh) {
            this.worldDocsRefresh = worldDocsRefresh;
            return this;
        }

        public ControlBootstrapService build() {
            return new ControlBootstrapService(engine, sessions, workspaces,
                    eventContentStore, journal, startupPublicGroupId, supervisor, clock,
                    sessionFactory, worldDocsRefresh);
        }
    }
}
