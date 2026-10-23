package io.mosire.iibot.taskflow;

import io.mosire.iibot.taskflow.event.EventSink;
import io.mosire.iibot.taskflow.event.EventType;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;
import io.mosire.iibot.taskflow.event.TaskflowEvent;
import io.mosire.iibot.taskflow.snapshot.FactSnapshot;
import io.mosire.iibot.taskflow.snapshot.PartySnapshot;
import io.mosire.iibot.taskflow.snapshot.SettingProposalSnapshot;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import io.mosire.iibot.taskflow.snapshot.TurnStateSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 纯内存、线程安全的交互-推演领域内核。
 *
 * <p>本类只接收结构化值对象和宿主已认证的 {@link Actor}，不解析自然语言、不调用
 * LLM、不接触 QQ/OneBot/NapCat，也不直接产生网络副作用。所有写命令都在同一把
 * 重入锁内完成“幂等检查 -> 版本/状态/权限/内容校验 -> 追加单个事件 -> 应用状态”；
 * 因此非法命令不改变状态，重复 idempotencyKey 返回第一次回执且不会重复追加事件。</p>
 *
 * <p>世界写入以显式提案为边界：GM 只能提交 {@link AdjudicationProposal}，宿主在
 * 原子提交前校验 schema、世界版本、玩家、hash、delta 类型和大小。成功提交只增加
 * 一个世界版本；重复提交不会再次增加。</p>
 */
public final class TaskflowEngine {
    public static final String DEFAULT_PROMPT_VERSION = "prompt-v1";

    private static final int MAX_FACT_CONTENT_CHARS = 65_536;
    private static final int MAX_QUESTION_CHARS = 8_192;
    private static final int MAX_BRIEF_CONTENT_CHARS = 65_536;
    private static final int MAX_NARRATIVE_CHARS = 32_768;
    private static final int MAX_HOOK_CHARS = 4_096;
    private static final int MAX_HOOKS = 64;
    private static final int MAX_FACTS_PER_LOAD = 10_000;

    private final Clock clock;
    private final EventSink eventSink;
    private final String promptVersion;
    private final Set<String> allowedDeltaTypes;
    private final int maxWorldDeltas;
    private final int maxProposalChars;
    private final int maxOutcomeChars;

    private final ReentrantLock mutex = new ReentrantLock();
    private final Map<String, Party> parties = new LinkedHashMap<>();
    private final Map<String, PlayerProfile> players = new LinkedHashMap<>();
    private final Map<String, PlayerProfile> directors = new LinkedHashMap<>();
    private final Map<String, WorldFact> worldFacts = new LinkedHashMap<>();
    private final Map<String, SettingProposal> settingProposals = new LinkedHashMap<>();
    private final Map<String, InfoRequest> infoRequests = new LinkedHashMap<>();
    private final Map<String, InfoBrief> briefsByRequest = new LinkedHashMap<>();
    private final Map<String, MutableTurn> turns = new LinkedHashMap<>();
    private final Map<String, CommandResult> idempotencyResults = new LinkedHashMap<>();

    private long engineRevision;
    private long worldVersion;
    private long eventCount;
    private long lastEventSeq;
    private int lastTurnNo;
    private MutableTurn currentTurn;
    private StateCommitListener stateCommitListener;

    /** 生产组合根通常注入自有 EventSink；测试可直接使用 InMemoryEventStore。 */
    public TaskflowEngine(Clock clock, EventSink eventSink) {
        this(clock, eventSink, DEFAULT_PROMPT_VERSION, WorldDelta.DEFAULT_ALLOWED_TYPES, 64, 200_000, 8_192);
    }

    /** 使用默认内存事件存储的便捷构造，方便单测和本地推演。 */
    public TaskflowEngine(Clock clock, InMemoryEventStore eventStore) {
        this(clock, (EventSink) eventStore);
    }

    public TaskflowEngine(Clock clock, EventSink eventSink, String promptVersion,
                          Set<String> allowedDeltaTypes, int maxWorldDeltas,
                          int maxProposalChars, int maxOutcomeChars) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion 不能为空");
        }
        this.promptVersion = promptVersion;
        if (allowedDeltaTypes == null || allowedDeltaTypes.isEmpty()
                || allowedDeltaTypes.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new IllegalArgumentException("allowedDeltaTypes 不能为空");
        }
        this.allowedDeltaTypes = Set.copyOf(new LinkedHashSet<>(allowedDeltaTypes));
        if (maxWorldDeltas <= 0 || maxProposalChars <= 0 || maxOutcomeChars <= 0) {
            throw new IllegalArgumentException("大小限制必须为正");
        }
        this.maxWorldDeltas = maxWorldDeltas;
        this.maxProposalChars = maxProposalChars;
        this.maxOutcomeChars = maxOutcomeChars;
    }

    // ---------------------------------------------------------------------
    // 状态提交监听与快照持久化
    // ---------------------------------------------------------------------

    /**
     * 返回已设置的提交监听器；未设置时为空。
     */
    public Optional<StateCommitListener> stateCommitListener() {
        mutex.lock();
        try {
            return Optional.ofNullable(stateCommitListener);
        } finally {
            mutex.unlock();
        }
    }

    /** {@link #stateCommitListener()} 的 JavaBean 命名别名。 */
    public Optional<StateCommitListener> getStateCommitListener() {
        return stateCommitListener();
    }

    /**
     * 设置一次性的状态提交监听器。
     *
     * <p>监听器只会被设置一次。设置后，每个返回 {@link CommandResult#ok()} 的写命令
     * 都会在引擎仍持锁时回调；回调异常不会被吞掉。</p>
     */
    public void setStateCommitListener(StateCommitListener listener) {
        if (listener == null) {
            return;
        }
        mutex.lock();
        try {
            if (stateCommitListener != null) {
                throw new IllegalStateException("StateCommitListener 只能设置一次");
            }
            stateCommitListener = listener;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * 导出完整、深拷贝的纯数据状态快照。
     *
     * <p>导出在引擎锁内完成；快照不携带 EventSink、Clock、LLM、QQ 或其他运行时对象，
     * 也不暴露内部 MutableTurn/Party。恢复所需的当前回合、历史回合、世界事实、brief、
     * 设定提案、幂等回执和事件序号都包含在返回值中。</p>
     */
    public TaskflowStateSnapshot exportState() {
        mutex.lock();
        try {
            List<PartySnapshot> partySnapshots = parties.values().stream()
                    .sorted(Comparator.comparing(party -> party.profile.playerId()))
                    .map(party -> new PartySnapshot(party.profile, party.role))
                    .toList();

            Map<String, FactSnapshot> facts = new LinkedHashMap<>();
            worldFacts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> facts.put(entry.getKey(),
                            FactSnapshot.fromDomain(entry.getValue())));

            Map<String, InfoRequest> requests = new LinkedHashMap<>();
            infoRequests.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> requests.put(entry.getKey(), entry.getValue()));

            Map<String, InfoBrief> briefs = new LinkedHashMap<>();
            briefsByRequest.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> briefs.put(entry.getKey(), entry.getValue()));

            Map<String, SettingProposalSnapshot> proposals = new LinkedHashMap<>();
            settingProposals.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> proposals.put(entry.getKey(),
                            SettingProposalSnapshot.fromDomain(entry.getValue())));

            Map<String, TurnStateSnapshot> turnSnapshots = new LinkedHashMap<>();
            turns.values().stream()
                    .sorted(Comparator.comparingInt((MutableTurn turn) -> turn.turnNo)
                            .thenComparing(turn -> turn.turnId))
                    .forEach(turn -> turnSnapshots.put(turn.turnId, toTurnStateSnapshot(turn)));

            Map<String, CommandResult> idempotency = new LinkedHashMap<>();
            idempotencyResults.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> idempotency.put(entry.getKey(), entry.getValue()));

            return new TaskflowStateSnapshot(TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION,
                    promptVersion, engineRevision, worldVersion, eventCount, lastEventSeq,
                    lastTurnNo, currentTurn == null ? null : currentTurn.turnId,
                    partySnapshots, facts, requests, briefs, proposals, turnSnapshots,
                    idempotency);
        } finally {
            mutex.unlock();
        }
    }

    /**
     * 从纯数据快照恢复引擎。
     *
     * <p>恢复会严格校验 schema、promptVersion、世界版本、回合 key/currentTurnId、
     * 身份映射、事实 key/版本、brief 引用和设定提案/世界事实关系。非法快照抛出
     * {@link IllegalArgumentException}，不会被静默截断或忽略。</p>
     */
    public static TaskflowEngine restore(Clock clock, EventSink eventSink,
                                         Set<String> allowedDeltaTypes, int maxWorldDeltas,
                                         int maxProposalChars, int maxOutcomeChars,
                                         TaskflowStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return restore(clock, eventSink, snapshot.promptVersion(), allowedDeltaTypes,
                maxWorldDeltas, maxProposalChars, maxOutcomeChars, snapshot);
    }

    /**
     * 带期望 promptVersion 的恢复入口；用于宿主在版本升级时拒绝不兼容快照。
     */
    public static TaskflowEngine restore(Clock clock, EventSink eventSink,
                                         String expectedPromptVersion,
                                         Set<String> allowedDeltaTypes, int maxWorldDeltas,
                                         int maxProposalChars, int maxOutcomeChars,
                                         TaskflowStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.schemaVersion() != TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的快照 schemaVersion: "
                    + snapshot.schemaVersion());
        }
        if (expectedPromptVersion == null || expectedPromptVersion.isBlank()) {
            throw new IllegalArgumentException("expectedPromptVersion 不能为空");
        }
        if (!expectedPromptVersion.equals(snapshot.promptVersion())) {
            throw new IllegalArgumentException("快照 promptVersion 与期望值不一致");
        }
        TaskflowEngine engine = new TaskflowEngine(clock, eventSink, expectedPromptVersion,
                allowedDeltaTypes, maxWorldDeltas, maxProposalChars, maxOutcomeChars);
        engine.restoreState(snapshot);
        return engine;
    }

    // ---------------------------------------------------------------------
    // 注册与世界装载
    // ---------------------------------------------------------------------

    /** 注册玩家。只有推演/监督角色可以写身份映射。 */
    public CommandResult registerPlayer(Actor actor, PlayerProfile profile,
                                        long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(profile, "profile");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isAuthorizer(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "只有 DIRECTOR/GM/SUPERVISOR 可以注册玩家", engineRevision, null));
            }
            if (expectedRevision != engineRevision) {
                return remember(idempotencyKey, fail(ErrorCode.REVISION_CONFLICT,
                        "引擎版本不匹配", engineRevision, null));
            }
            if (parties.containsKey(profile.playerId())) {
                return remember(idempotencyKey, fail(ErrorCode.PLAYER_ALREADY_REGISTERED,
                        "playerId 已注册", engineRevision, null));
            }
            if (qqExists(profile.qqId())) {
                return remember(idempotencyKey, fail(ErrorCode.QQ_ALREADY_REGISTERED,
                        "qqId 已映射到其他身份", engineRevision, null));
            }
            long seq = append(EventType.ACTOR_REGISTERED, actor, null, idempotencyKey,
                    Map.of("playerId", profile.playerId(), "role", ActorRole.PLAYER.name()));
            parties.put(profile.playerId(), new Party(profile, ActorRole.PLAYER));
            players.put(profile.playerId(), profile);
            engineRevision++;
            return remember(idempotencyKey, success(engineRevision, engineRevision, seq, null,
                    "玩家已注册", profile.playerId(), Map.of("playerId", profile.playerId())));
        });
    }

    /** 注册推演身份；推演也需要稳定 qqId 映射，但不会被当作玩家。 */
    public CommandResult registerDirector(Actor actor, PlayerProfile profile,
                                          long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(profile, "profile");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isAuthorizer(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "只有 DIRECTOR/GM/SUPERVISOR 可以注册推演", engineRevision, null));
            }
            if (expectedRevision != engineRevision) {
                return remember(idempotencyKey, fail(ErrorCode.REVISION_CONFLICT,
                        "引擎版本不匹配", engineRevision, null));
            }
            if (parties.containsKey(profile.playerId())) {
                return remember(idempotencyKey, fail(ErrorCode.PLAYER_ALREADY_REGISTERED,
                        "身份 ID 已注册", engineRevision, null));
            }
            if (qqExists(profile.qqId())) {
                return remember(idempotencyKey, fail(ErrorCode.QQ_ALREADY_REGISTERED,
                        "qqId 已映射到其他身份", engineRevision, null));
            }
            long seq = append(EventType.ACTOR_REGISTERED, actor, null, idempotencyKey,
                    Map.of("playerId", profile.playerId(), "role", ActorRole.DIRECTOR.name()));
            parties.put(profile.playerId(), new Party(profile, ActorRole.DIRECTOR));
            directors.put(profile.playerId(), profile);
            engineRevision++;
            return remember(idempotencyKey, success(engineRevision, engineRevision, seq, null,
                    "推演已注册", profile.playerId(), Map.of("playerId", profile.playerId())));
        });
    }

    /**
     * 装载一个新版本的世界事实。
     *
     * <p>facts 中的 worldVersion 必须全都等于 expectedWorldVersion + 1，防止调用方
     * 把不同版本的碎片混入同一视图。装载本身也受引擎 expectedRevision 乐观锁保护。</p>
     */
    public CommandResult loadWorldFacts(Actor actor, long expectedWorldVersion,
                                        Collection<WorldFact> facts, long expectedRevision,
                                        String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isAuthorizer(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "只有 DIRECTOR/GM/SUPERVISOR 可以装载世界事实", engineRevision, null));
            }
            if (expectedRevision != engineRevision) {
                return remember(idempotencyKey, fail(ErrorCode.REVISION_CONFLICT,
                        "引擎版本不匹配", engineRevision, null));
            }
            if (expectedWorldVersion != worldVersion) {
                return remember(idempotencyKey, fail(ErrorCode.WORLD_VERSION_MISMATCH,
                        "期望世界版本不是当前版本", engineRevision, null));
            }
            if (facts == null || facts.isEmpty() || facts.size() > MAX_FACTS_PER_LOAD) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "世界事实数量非法", engineRevision, null));
            }
            long targetVersion = worldVersion + 1;
            Set<String> seenIds = new LinkedHashSet<>();
            for (WorldFact fact : facts) {
                if (fact == null || fact.worldVersion() != targetVersion) {
                    return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                            "事实版本必须等于目标版本", engineRevision, null));
                }
                if (!seenIds.add(fact.factId())) {
                    return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                            "同一批次 factId 重复", engineRevision, null));
                }
                if (fact.content().length() > MAX_FACT_CONTENT_CHARS) {
                    return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                            "世界事实内容过大", engineRevision, null));
                }
            }
            long seq = append(EventType.WORLD_FACTS_LOADED, actor, null, idempotencyKey,
                    Map.of("worldVersion", Long.toString(targetVersion),
                            "factCount", Integer.toString(facts.size())));
            facts.forEach(f -> worldFacts.put(f.factId(), f));
            worldVersion = targetVersion;
            engineRevision++;
            return remember(idempotencyKey, success(engineRevision, engineRevision, seq, null,
                    "世界事实已装载", null,
                    Map.of("worldVersion", Long.toString(worldVersion))));
        });
    }

    // ---------------------------------------------------------------------
    // 设定提案与审核
    // ---------------------------------------------------------------------

    /**
     * 提交一条尚未生效的设定提案。
     *
     * <p>玩家只能提交 PUBLIC 提案；DIRECTOR/GM/SUPERVISOR 可以指定玩家集合或仅推演
     * 可见。提案本身不会进入 worldFacts，因此批准前任何玩家/秘书的可见事实查询都不会
     * 看到正文。正文最多 {@value SettingProposal#MAX_CONTENT_CHARS} 字符。</p>
     */
    public CommandResult proposeSetting(Actor author, String content, Visibility visibility,
                                        long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(author, "author");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isRegisteredSettingAuthor(author)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "只有已登记的 PLAYER/DIRECTOR/GM/SUPERVISOR 可以提交设定提案",
                        engineRevision, null));
            }
            if (expectedRevision != engineRevision) {
                return remember(idempotencyKey, fail(ErrorCode.REVISION_CONFLICT,
                        "引擎版本不匹配", engineRevision, null));
            }
            if (content == null || content.isBlank()) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "设定正文不能为空", engineRevision, null));
            }
            if (content.length() > SettingProposal.MAX_CONTENT_CHARS) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "设定正文不能超过 " + SettingProposal.MAX_CONTENT_CHARS + " 字符",
                        engineRevision, null));
            }
            if (visibility == null) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "设定可见性不能为空", engineRevision, null));
            }
            if (author.role() == ActorRole.PLAYER
                    && !(visibility instanceof Visibility.PublicVisibility)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "玩家只能提交 PUBLIC 设定提案", engineRevision, null));
            }

            String proposalId = UUID.randomUUID().toString();
            String visibilitySummary = visibilitySummary(visibility);
            Instant now = clock.instant();
            long seq = append(EventType.SETTING_PROPOSED, author, null, idempotencyKey,
                    Map.of("proposalId", proposalId,
                            "authorId", author.actorId(),
                            "authorRole", author.role().name(),
                            "visibility", visibilitySummary,
                            "worldVersion", Long.toString(worldVersion),
                            "contentLength", Integer.toString(content.length())));
            SettingProposal proposal = new SettingProposal(proposalId, author.actorId(),
                    author.role(), content, visibility, SettingProposalStatus.PENDING, now,
                    null, null, null, null);
            settingProposals.put(proposalId, proposal);
            engineRevision++;
            return remember(idempotencyKey, success(engineRevision, engineRevision, seq, null,
                    "设定提案已提交，等待推演审核", proposalId,
                    Map.of("proposalId", proposalId,
                            "status", SettingProposalStatus.PENDING.name(),
                            "visibility", visibilitySummary)));
        });
    }

    /**
     * 一次性审核设定提案。批准时生成稳定 {@code setting-<proposalId>} 世界事实并把
     * worldVersion 恰好提升 1；拒绝只改变提案状态，不改变 worldVersion 或 facts。
     *
     * <p>只有 PENDING 提案可被审核；任何权限、版本、未知 ID 或状态错误都不会追加事件
     * 或修改世界状态。审核事件只携带 ID/可见性/版本等审计摘要，不携带设定正文。</p>
     */
    public CommandResult decideSetting(Actor reviewer, String proposalId, boolean approve,
                                       String note, long expectedRevision,
                                       String idempotencyKey) {
        Objects.requireNonNull(reviewer, "reviewer");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isAuthorizer(reviewer)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "只有 DIRECTOR/GM/SUPERVISOR 可以审核设定提案", engineRevision, null));
            }
            if (expectedRevision != engineRevision) {
                return remember(idempotencyKey, fail(ErrorCode.REVISION_CONFLICT,
                        "引擎版本不匹配", engineRevision, null));
            }
            if (proposalId == null || proposalId.isBlank()) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "proposalId 不能为空", engineRevision, null));
            }
            SettingProposal proposal = settingProposals.get(proposalId);
            if (proposal == null) {
                return remember(idempotencyKey, fail(ErrorCode.NOT_FOUND,
                        "设定提案不存在", engineRevision, null));
            }
            if (proposal.status() != SettingProposalStatus.PENDING) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "设定提案已经审核，不能重复决定", engineRevision, null));
            }

            Instant decidedAt = clock.instant();
            String visibilitySummary = visibilitySummary(proposal.visibility());
            if (approve) {
                long targetWorldVersion = worldVersion + 1;
                String worldFactId = "setting-" + proposal.proposalId();
                WorldFact fact = new WorldFact(worldFactId, targetWorldVersion,
                        proposal.content(), proposal.visibility());
                long seq = append(EventType.SETTING_APPROVED, reviewer, null, idempotencyKey,
                        Map.of("proposalId", proposal.proposalId(),
                                "reviewerId", reviewer.actorId(),
                                "visibility", visibilitySummary,
                                "worldFactId", worldFactId,
                                "worldVersion", Long.toString(targetWorldVersion),
                                "contentLength", Integer.toString(proposal.content().length())));
                worldFacts.put(worldFactId, fact);
                worldVersion = targetWorldVersion;
                settingProposals.put(proposal.proposalId(), new SettingProposal(
                        proposal.proposalId(), proposal.authorActorId(), proposal.authorRole(),
                        proposal.content(), proposal.visibility(),
                        SettingProposalStatus.APPROVED, proposal.createdAt(),
                        reviewer.actorId(), note, decidedAt, worldFactId));
                engineRevision++;
                return remember(idempotencyKey, success(engineRevision, engineRevision, seq, null,
                        "设定已批准并写入世界事实", worldFactId,
                        Map.of("proposalId", proposal.proposalId(),
                                "status", SettingProposalStatus.APPROVED.name(),
                                "worldFactId", worldFactId,
                                "worldVersion", Long.toString(worldVersion),
                                "visibility", visibilitySummary)));
            }

            long seq = append(EventType.SETTING_REJECTED, reviewer, null, idempotencyKey,
                    Map.of("proposalId", proposal.proposalId(),
                            "reviewerId", reviewer.actorId(),
                            "visibility", visibilitySummary,
                            "worldVersion", Long.toString(worldVersion),
                            "hasReviewNote", Boolean.toString(note != null && !note.isBlank())));
            settingProposals.put(proposal.proposalId(), new SettingProposal(
                    proposal.proposalId(), proposal.authorActorId(), proposal.authorRole(),
                    proposal.content(), proposal.visibility(),
                    SettingProposalStatus.REJECTED, proposal.createdAt(),
                    reviewer.actorId(), note, decidedAt, null));
            engineRevision++;
            return remember(idempotencyKey, success(engineRevision, engineRevision, seq, null,
                    "设定提案已拒绝", proposal.proposalId(),
                    Map.of("proposalId", proposal.proposalId(),
                            "status", SettingProposalStatus.REJECTED.name(),
                            "worldVersion", Long.toString(worldVersion),
                            "visibility", visibilitySummary)));
        });
    }

    /** 按 ID 读取设定提案；不存在时返回空。 */
    public Optional<SettingProposal> settingProposal(String proposalId) {
        mutex.lock();
        try {
            if (proposalId == null || proposalId.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(settingProposals.get(proposalId));
        } finally {
            mutex.unlock();
        }
    }

    /** 稳定排序（createdAt 后按 proposalId）的待审设定提案快照。 */
    public List<SettingProposal> pendingSettings() {
        mutex.lock();
        try {
            return settingProposals.values().stream()
                    .filter(proposal -> proposal.status() == SettingProposalStatus.PENDING)
                    .sorted(Comparator.comparing(SettingProposal::createdAt)
                            .thenComparing(SettingProposal::proposalId))
                    .toList();
        } finally {
            mutex.unlock();
        }
    }

    // ---------------------------------------------------------------------
    // 回合与决策
    // ---------------------------------------------------------------------

    /** 开启新回合；同一时刻至多一个未终态回合。 */
    public CommandResult openTurn(Actor actor, long expectedRevision, String idempotencyKey,
                                  Instant closesAt) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isTurnOperator(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有开启回合的权限", engineRevision, null));
            }
            if (expectedRevision != engineRevision) {
                return remember(idempotencyKey, fail(ErrorCode.REVISION_CONFLICT,
                        "引擎版本不匹配", engineRevision, null));
            }
            if (currentTurn != null && !currentTurn.state.isTerminal()) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_ALREADY_ACTIVE,
                        "已有未终态回合", currentTurn));
            }
            Instant now = clock.instant();
            if (closesAt == null || !closesAt.isAfter(now)) {
                return remember(idempotencyKey, fail(ErrorCode.INVALID_ARGUMENT,
                        "closesAt 必须晚于当前时间", engineRevision, null));
            }
            String turnId = UUID.randomUUID().toString();
            int turnNo = ++lastTurnNo;
            long seq = append(EventType.TURN_OPENED, actor, turnId, idempotencyKey,
                    Map.of("turnNo", Integer.toString(turnNo),
                            "worldVersion", Long.toString(worldVersion),
                            "closesAt", closesAt.toString()));
            MutableTurn turn = new MutableTurn(turnId, turnNo, now, closesAt, worldVersion);
            turns.put(turnId, turn);
            currentTurn = turn;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "回合已开启", turnId,
                    Map.of("turnId", turnId, "turnNo", Integer.toString(turnNo))));
        });
    }

    /** 创建结构化决策草稿；领域层不负责自然语言解析。 */
    public CommandResult createDecisionDraft(Actor player, DecisionDraftInput input,
                                             long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            CommandResult auth = requirePlayer(player);
            if (auth != null) {
                return remember(idempotencyKey, auth);
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.OPEN) {
                return remember(idempotencyKey, failTurn(ErrorCode.LATE_DRAFT,
                        "回合已冻结，不能再创建草稿", turn));
            }
            if (!input.expiresAt().isAfter(clock.instant())) {
                return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                        "草稿过期时间必须晚于当前时间", turn));
            }
            String draftId = UUID.randomUUID().toString();
            String previewHash = previewHash(turn.turnId, player.actorId(), input);
            long seq = append(EventType.DRAFT_CREATED, player, turn.turnId, idempotencyKey,
                    Map.of("draftId", draftId, "playerId", player.actorId(),
                            "previewHash", previewHash));
            DecisionDraft draft = new DecisionDraft(draftId, turn.turnId, player.actorId(),
                    input.intent(), input.actions(), input.resources(), input.target(),
                    input.contingency(), previewHash, DraftStatus.DRAFT, 1, input.expiresAt());
            turn.drafts.put(draftId, draft);
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "草稿已创建", draftId,
                    Map.of("draftId", draftId, "previewHash", previewHash,
                            "turnId", turn.turnId)));
        });
    }

    /** 更新自己的未确认草稿；确认后不可再改。 */
    public CommandResult updateDecisionDraft(Actor player, String draftId, DecisionDraftInput input,
                                             long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(input, "input");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            CommandResult auth = requirePlayer(player);
            if (auth != null) {
                return remember(idempotencyKey, auth);
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.OPEN) {
                return remember(idempotencyKey, failTurn(ErrorCode.LATE_DRAFT,
                        "回合已冻结，不能更新草稿", turn));
            }
            DecisionDraft existing = turn.drafts.get(draftId);
            if (existing == null) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_FOUND,
                        "草稿不存在", turn));
            }
            if (!existing.playerId().equals(player.actorId())) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_OWNED,
                        "不能修改他人草稿", turn));
            }
            if (existing.status() != DraftStatus.DRAFT) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_DRAFT,
                        "草稿已确认或失效", turn));
            }
            if (!existing.expiresAt().isAfter(clock.instant())) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_EXPIRED,
                        "草稿已过期", turn));
            }
            if (!input.expiresAt().isAfter(clock.instant())) {
                return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                        "草稿过期时间必须晚于当前时间", turn));
            }
            String previewHash = previewHash(turn.turnId, player.actorId(), input);
            long seq = append(EventType.DRAFT_UPDATED, player, turn.turnId, idempotencyKey,
                    Map.of("draftId", draftId, "previewHash", previewHash));
            DecisionDraft updated = new DecisionDraft(draftId, turn.turnId, player.actorId(),
                    input.intent(), input.actions(), input.resources(), input.target(),
                    input.contingency(), previewHash, DraftStatus.DRAFT,
                    existing.revision() + 1, input.expiresAt());
            turn.drafts.put(draftId, updated);
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "草稿已更新", draftId,
                    Map.of("draftId", draftId, "previewHash", previewHash,
                            "turnId", turn.turnId)));
        });
    }

    /**
     * 玩家在同一私聊中硬确认草稿。
     *
     * <p>只有本人、OPEN 回合、未过期草稿且 previewHash 完全匹配时才生成 Decision。
     * 确认会让同玩家其他未确认草稿失效；同一玩家一个回合最多一条有效 Decision。</p>
     */
    public CommandResult confirmDecision(Actor player, String draftId, String previewHash,
                                         long expectedTurnRevision, String idempotencyKey) {
        Objects.requireNonNull(player, "player");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            CommandResult auth = requirePlayer(player);
            if (auth != null) {
                return remember(idempotencyKey, auth);
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedTurnRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.OPEN) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_OPEN,
                        "回合已冻结，不能确认决策", turn));
            }
            DecisionDraft draft = turn.drafts.get(draftId);
            if (draft == null) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_FOUND,
                        "草稿不存在", turn));
            }
            if (!draft.playerId().equals(player.actorId())) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_OWNED,
                        "不能确认他人草稿", turn));
            }
            if (draft.status() == DraftStatus.CONFIRMED) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_DRAFT,
                        "草稿已经确认", turn));
            }
            if (draft.status() != DraftStatus.DRAFT) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_NOT_DRAFT,
                        "草稿已失效", turn));
            }
            if (!draft.expiresAt().isAfter(clock.instant())) {
                return remember(idempotencyKey, failTurn(ErrorCode.DRAFT_EXPIRED,
                        "草稿已过期", turn));
            }
            if (!draft.previewHash().equals(previewHash)) {
                return remember(idempotencyKey, failTurn(ErrorCode.PREVIEW_HASH_MISMATCH,
                        "预览 hash 不匹配", turn));
            }
            if (turn.decisionsByPlayer.containsKey(player.actorId())) {
                return remember(idempotencyKey, failTurn(ErrorCode.DECISION_ALREADY_CONFIRMED,
                        "本回合已经确认过决策", turn));
            }

            Instant confirmedAt = clock.instant();
            String decisionId = UUID.randomUUID().toString();
            List<String> superseded = turn.drafts.values().stream()
                    .filter(d -> d.playerId().equals(player.actorId()))
                    .filter(d -> d.status() == DraftStatus.DRAFT)
                    .filter(d -> !d.draftId().equals(draftId))
                    .map(DecisionDraft::draftId)
                    .sorted()
                    .toList();
            String contentHash = Hashes.sha256("draft=" + draft.previewHash()
                    + "|decision=" + decisionId + "|player=" + player.actorId()
                    + "|confirmedAt=" + confirmedAt);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("intent", draft.intent());
            fields.put("actions", draft.actions());
            fields.put("resources", draft.resources());
            fields.put("target", draft.target());
            fields.put("contingency", draft.contingency());
            fields.put("previewHash", draft.previewHash());
            Decision decision = new Decision(decisionId, draftId, turn.turnId, player.actorId(),
                    contentHash, player.actorId(), confirmedAt, fields);
            long seq = append(EventType.DECISION_CONFIRMED, player, turn.turnId, idempotencyKey,
                    Map.of("draftId", draftId, "decisionId", decisionId,
                            "playerId", player.actorId(), "contentHash", contentHash,
                            "supersededDraftIds", String.join(",", superseded)));

            turn.drafts.put(draftId, new DecisionDraft(draft.draftId(), draft.turnId(),
                    draft.playerId(), draft.intent(), draft.actions(), draft.resources(),
                    draft.target(), draft.contingency(), draft.previewHash(),
                    DraftStatus.CONFIRMED, draft.revision() + 1, draft.expiresAt()));
            for (String supersededDraftId : superseded) {
                DecisionDraft old = turn.drafts.get(supersededDraftId);
                turn.drafts.put(supersededDraftId, new DecisionDraft(old.draftId(), old.turnId(),
                        old.playerId(), old.intent(), old.actions(), old.resources(), old.target(),
                        old.contingency(), old.previewHash(), DraftStatus.SUPERSEDED,
                        old.revision() + 1, old.expiresAt()));
            }
            turn.decisionsByPlayer.put(player.actorId(), decision);
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "决策已确认", decisionId,
                    Map.of("decisionId", decisionId, "draftId", draftId,
                            "contentHash", contentHash, "turnId", turn.turnId)));
        });
    }

    /** 冻结回合并生成不可变 TurnSnapshot。 */
    public CommandResult lockTurn(Actor actor, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isTurnOperator(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有冻结回合的权限", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.OPEN) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_OPEN,
                        "只有 OPEN 回合可以冻结", turn));
            }
            Instant lockedAt = clock.instant();
            List<Decision> decisions = turn.decisionsByPlayer.values().stream()
                    .sorted(Comparator.comparing(Decision::playerId))
                    .toList();
            String snapshotHash = Hashes.sha256(TurnSnapshot.canonicalText(turn.turnId,
                    turn.turnNo, worldVersion, promptVersion, decisions, lockedAt));
            TurnSnapshot snapshot = new TurnSnapshot(turn.turnId, turn.turnNo, worldVersion,
                    promptVersion, decisions, lockedAt, snapshotHash);
            long seq = append(EventType.TURN_LOCKED, actor, turn.turnId, idempotencyKey,
                    Map.of("snapshotHash", snapshotHash,
                            "decisionCount", Integer.toString(decisions.size()),
                            "worldVersion", Long.toString(worldVersion)));
            turn.state = TurnState.LOCKED;
            turn.lockedAt = lockedAt;
            turn.snapshot = snapshot;
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "回合已冻结", turn.turnId,
                    Map.of("turnId", turn.turnId, "snapshotHash", snapshotHash)));
        });
    }

    public CommandResult startAdjudication(Actor actor, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isTurnOperator(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有启动推演的权限", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.LOCKED || turn.snapshot == null) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_LOCKED,
                        "只有 LOCKED 回合可以启动推演", turn));
            }
            long seq = append(EventType.ADJUDICATION_STARTED, actor, turn.turnId,
                    idempotencyKey, Map.of("snapshotHash", turn.snapshot.contentHash()));
            turn.state = TurnState.ADJUDICATING;
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "推演已启动", turn.turnId,
                    Map.of("turnId", turn.turnId, "snapshotHash", turn.snapshot.contentHash())));
        });
    }

    /**
     * 原子提交结构化推演提案。
     *
     * <p>所有校验都在追加事件与修改世界版本之前完成；非法提案零落盘。成功提交只做
     * 一次 worldVersion + 1，并把回合推进到 COMMITTED。</p>
     */
    public CommandResult commitAdjudication(Actor actor, long expectedTurnRevision,
                                            String idempotencyKey, AdjudicationProposal proposal) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (actor.role() != ActorRole.GM && actor.role() != ActorRole.DIRECTOR
                    && actor.role() != ActorRole.SUPERVISOR) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有提交推演提案的权限", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (turn.state == TurnState.COMMITTED || turn.state == TurnState.PUBLISHED) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_ALREADY_COMMITTED,
                        "回合已经提交，不能重复提交", turn));
            }
            if (expectedTurnRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.ADJUDICATING) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_ADJUDICATING,
                        "只有 ADJUDICATING 回合可以提交提案", turn));
            }
            if (proposal == null) {
                return remember(idempotencyKey, failTurn(ErrorCode.PROPOSAL_INVALID,
                        "提案不能为空", turn));
            }
            if (proposal.schemaVersion() != AdjudicationProposal.CURRENT_SCHEMA_VERSION) {
                return remember(idempotencyKey, failTurn(ErrorCode.SCHEMA_UNSUPPORTED,
                        "不支持的提案 schemaVersion", turn));
            }
            if (!turn.turnId.equals(proposal.turnId())) {
                return remember(idempotencyKey, failTurn(ErrorCode.PROPOSAL_INVALID,
                        "提案 turnId 与当前回合不一致", turn));
            }
            if (!proposal.hasValidHash()) {
                return remember(idempotencyKey, failTurn(ErrorCode.PROPOSAL_HASH_MISMATCH,
                        "proposalHash 不匹配", turn));
            }
            if (proposal.expectedWorldVersion() != worldVersion) {
                return remember(idempotencyKey, failTurn(ErrorCode.WORLD_VERSION_MISMATCH,
                        "提案基于过期世界版本", turn));
            }
            CommandResult validation = validateProposal(turn, proposal);
            if (validation != null) {
                return remember(idempotencyKey, validation);
            }

            long seq = append(EventType.ADJUDICATION_COMMITTED, actor, turn.turnId,
                    idempotencyKey, Map.of(
                            "proposalHash", proposal.proposalHash(),
                            "worldVersionBefore", Long.toString(worldVersion),
                            "worldVersionAfter", Long.toString(worldVersion + 1),
                            "outcomeCount", Integer.toString(proposal.playerOutcomes().size()),
                            "deltaCount", Integer.toString(proposal.worldDeltas().size())));
            turn.committedProposal = proposal;
            turn.state = TurnState.COMMITTED;
            worldVersion++;
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "推演提案已原子提交", turn.turnId,
                    Map.of("turnId", turn.turnId, "proposalHash", proposal.proposalHash(),
                            "worldVersion", Long.toString(worldVersion))));
        });
    }

    public CommandResult publishTurn(Actor actor, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isTurnOperator(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有发布回合的权限", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.COMMITTED) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_COMMITTED,
                        "只有 COMMITTED 回合可以发布", turn));
            }
            long seq = append(EventType.TURN_PUBLISHED, actor, turn.turnId, idempotencyKey,
                    Map.of("snapshotHash", turn.snapshot == null ? "" : turn.snapshot.contentHash(),
                            "proposalHash", turn.committedProposal == null
                                    ? "" : turn.committedProposal.proposalHash()));
            turn.state = TurnState.PUBLISHED;
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "回合已发布", turn.turnId, Map.of("turnId", turn.turnId)));
        });
    }

    /** 推演失败进入可重试态；世界版本和已冻结快照都不变。 */
    public CommandResult failAdjudication(Actor actor, String reason, long expectedRevision,
                                          String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isTurnOperator(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有标记推演失败的权限", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.ADJUDICATING) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_ADJUDICATING,
                        "只有 ADJUDICATING 回合可以标记失败", turn));
            }
            String safeReason = reason == null ? "" : reason.substring(0, Math.min(reason.length(), 1_024));
            long seq = append(EventType.ADJUDICATION_FAILED, actor, turn.turnId, idempotencyKey,
                    Map.of("reason", safeReason));
            turn.state = TurnState.FAILED_RETRYABLE;
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "推演失败，可重试", turn.turnId, Map.of("turnId", turn.turnId)));
        });
    }

    /** 从 FAILED_RETRYABLE 重新进入 ADJUDICATING；不修改冻结快照。 */
    public CommandResult retryAdjudication(Actor actor, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(actor, "actor");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!isTurnOperator(actor)) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "没有重试推演的权限", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.FAILED_RETRYABLE) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_RETRYABLE,
                        "只有 FAILED_RETRYABLE 回合可以重试", turn));
            }
            long seq = append(EventType.ADJUDICATION_RETRY_STARTED, actor, turn.turnId,
                    idempotencyKey, Map.of("snapshotHash", turn.snapshot.contentHash()));
            turn.state = TurnState.ADJUDICATING;
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "推演已重试", turn.turnId, Map.of("turnId", turn.turnId)));
        });
    }

    // ---------------------------------------------------------------------
    // Brief 与知识过滤
    // ---------------------------------------------------------------------

    /** 玩家请求 GM 过滤后的信息；请求本身不直接暴露世界事实。 */
    public CommandResult requestBrief(Actor player, String question, long expectedRevision,
                                      String idempotencyKey) {
        Objects.requireNonNull(player, "player");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            CommandResult auth = requirePlayer(player);
            if (auth != null) {
                return remember(idempotencyKey, auth);
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            if (turn.state != TurnState.OPEN) {
                return remember(idempotencyKey, failTurn(ErrorCode.TURN_NOT_OPEN,
                        "只有 OPEN 回合可以请求 brief", turn));
            }
            if (question == null || question.isBlank() || question.length() > MAX_QUESTION_CHARS) {
                return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                        "问题为空或过大", turn));
            }
            String requestId = UUID.randomUUID().toString();
            long seq = append(EventType.BRIEF_REQUESTED, player, turn.turnId, idempotencyKey,
                    Map.of("requestId", requestId, "playerId", player.actorId()));
            InfoRequest request = new InfoRequest(requestId, player.actorId(), turn.turnId,
                    question, clock.instant(), InfoRequestStatus.REQUESTED);
            infoRequests.put(requestId, request);
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "brief 请求已记录", requestId,
                    Map.of("requestId", requestId, "turnId", turn.turnId)));
        });
    }

    /**
     * GM 签发 brief；每个 sourceFactId 必须对请求玩家可见。
     *
     * <p>该检查发生在签发事件之前，任何越权来源都会返回 BRIEF_SOURCE_NOT_VISIBLE 且
     * 不产生 brief、不推进版本。</p>
     */
    public CommandResult issueBrief(Actor gm, String requestId, String content,
                                    Collection<String> sourceFactIds, Instant expiresAt,
                                    long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(gm, "gm");
        return withWrite(idempotencyKey, () -> {
            CommandResult duplicate = duplicate(idempotencyKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (gm.role() != ActorRole.GM && gm.role() != ActorRole.DIRECTOR
                    && gm.role() != ActorRole.SUPERVISOR) {
                return remember(idempotencyKey, fail(ErrorCode.FORBIDDEN,
                        "只有 GM/DIRECTOR/SUPERVISOR 可以签发 brief", engineRevision, null));
            }
            MutableTurn turn = currentTurn;
            if (turn == null) {
                return remember(idempotencyKey, fail(ErrorCode.NO_ACTIVE_TURN,
                        "没有进行中的回合", engineRevision, null));
            }
            if (expectedRevision != turn.revision) {
                return remember(idempotencyKey, failTurn(ErrorCode.REVISION_CONFLICT,
                        "回合版本不匹配", turn));
            }
            InfoRequest request = infoRequests.get(requestId);
            if (request == null) {
                return remember(idempotencyKey, failTurn(ErrorCode.BRIEF_REQUEST_NOT_FOUND,
                        "brief 请求不存在", turn));
            }
            if (request.status() != InfoRequestStatus.REQUESTED) {
                return remember(idempotencyKey, failTurn(ErrorCode.BRIEF_ALREADY_ISSUED,
                        "brief 请求已经处理", turn));
            }
            if (!request.turnId().equals(turn.turnId)) {
                return remember(idempotencyKey, failTurn(ErrorCode.BRIEF_REQUEST_NOT_FOUND,
                        "请求不属于当前回合", turn));
            }
            if (content == null || content.isBlank() || content.length() > MAX_BRIEF_CONTENT_CHARS) {
                return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                        "brief 内容为空或过大", turn));
            }
            if (sourceFactIds == null || sourceFactIds.isEmpty()) {
                return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                        "brief 必须引用来源事实", turn));
            }
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            for (String sourceFactId : sourceFactIds) {
                if (sourceFactId == null || sourceFactId.isBlank()) {
                    return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                            "来源 factId 不能为空", turn));
                }
                WorldFact fact = worldFacts.get(sourceFactId);
                if (fact == null || !fact.visibility().canSeeAsPlayer(request.playerId())) {
                    return remember(idempotencyKey, failTurn(ErrorCode.BRIEF_SOURCE_NOT_VISIBLE,
                            "来源事实对玩家不可见", turn));
                }
                sources.add(sourceFactId);
            }
            Instant now = clock.instant();
            if (expiresAt == null || !expiresAt.isAfter(now)) {
                return remember(idempotencyKey, failTurn(ErrorCode.INVALID_ARGUMENT,
                        "brief 过期时间必须晚于当前时间", turn));
            }
            String contentHash = Hashes.sha256("player=" + request.playerId()
                    + "|turn=" + request.turnId() + "|content=" + content
                    + "|sources=" + String.join("\u001f", sources)
                    + "|expiresAt=" + expiresAt);
            long seq = append(EventType.BRIEF_ISSUED, gm, turn.turnId, idempotencyKey,
                    Map.of("requestId", requestId, "playerId", request.playerId(),
                            "contentHash", contentHash,
                            "sourceCount", Integer.toString(sources.size())));
            InfoBrief brief = new InfoBrief(sources, content, request.playerId(), request.turnId(),
                    expiresAt, contentHash);
            infoRequests.put(requestId, new InfoRequest(request.requestId(), request.playerId(),
                    request.turnId(), request.question(), request.createdAt(), InfoRequestStatus.ISSUED));
            briefsByRequest.put(requestId, brief);
            turn.revision++;
            engineRevision++;
            return remember(idempotencyKey, success(turn.revision, engineRevision, seq,
                    turn.state, "brief 已签发", requestId,
                    Map.of("requestId", requestId, "playerId", request.playerId(),
                            "contentHash", contentHash)));
        });
    }

    /**
     * 返回秘书可见知识：只包含该玩家未过期 brief 和 PUBLIC 最新事实。
     *
     * <p>不会返回其他玩家私密事实；这是进入模型前的硬过滤，而不是提示词约束。</p>
     */
    public SecretaryKnowledge knowledgeForSecretary(String playerId) {
        mutex.lock();
        try {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不能为空");
            }
            Instant now = clock.instant();
            List<InfoBrief> briefs = briefsByRequest.values().stream()
                    .filter(brief -> brief.playerId().equals(playerId))
                    .filter(brief -> brief.expiresAt().isAfter(now))
                    .sorted(Comparator.comparing(InfoBrief::turnId)
                            .thenComparing(InfoBrief::contentHash))
                    .toList();
            List<WorldFact> publicFacts = worldFacts.values().stream()
                    .filter(fact -> fact.visibility().canSeeAsPlayer(playerId)
                            && fact.visibility() == Visibility.PUBLIC)
                    .sorted(Comparator.comparing(WorldFact::factId))
                    .toList();
            return new SecretaryKnowledge(playerId, briefs, publicFacts);
        } finally {
            mutex.unlock();
        }
    }

    /** 只返回 PUBLIC 或显式对该玩家可见的最新世界事实。 */
    public List<WorldFact> visibleFactsFor(String playerId) {
        mutex.lock();
        try {
            if (playerId == null || playerId.isBlank()) {
                return List.of();
            }
            return worldFacts.values().stream()
                    .filter(fact -> fact.visibility().canSeeAsPlayer(playerId))
                    .sorted(Comparator.comparing(WorldFact::factId))
                    .toList();
        } finally {
            mutex.unlock();
        }
    }

    /** 全部世界事实的稳定只读副本（先按 worldVersion，再按 factId 排序）。 */
    public List<WorldFact> allWorldFacts() {
        mutex.lock();
        try {
            return worldFacts.values().stream()
                    .sorted(Comparator.comparingLong(WorldFact::worldVersion)
                            .thenComparing(WorldFact::factId))
                    .toList();
        } finally {
            mutex.unlock();
        }
    }

    /** 已注册玩家资料的稳定只读副本（按 playerId 排序）。 */
    public List<PlayerProfile> playerProfiles() {
        mutex.lock();
        try {
            return players.values().stream()
                    .sorted(Comparator.comparing(PlayerProfile::playerId))
                    .toList();
        } finally {
            mutex.unlock();
        }
    }

    /** 已注册推演资料的稳定只读副本（按 playerId 排序）。 */
    public List<PlayerProfile> directorProfiles() {
        mutex.lock();
        try {
            return directors.values().stream()
                    .sorted(Comparator.comparing(PlayerProfile::playerId))
                    .toList();
        } finally {
            mutex.unlock();
        }
    }

    // ---------------------------------------------------------------------
    // 只读视图
    // ---------------------------------------------------------------------

    public Optional<TurnView> turnView() {
        mutex.lock();
        try {
            return Optional.ofNullable(currentTurn == null ? null : toView(currentTurn));
        } finally {
            mutex.unlock();
        }
    }

    public Optional<TurnView> turnView(String turnId) {
        mutex.lock();
        try {
            MutableTurn turn = turns.get(turnId);
            return Optional.ofNullable(turn == null ? null : toView(turn));
        } finally {
            mutex.unlock();
        }
    }

    public Optional<TurnSnapshot> snapshot() {
        mutex.lock();
        try {
            return currentTurn == null ? Optional.empty() : Optional.ofNullable(currentTurn.snapshot);
        } finally {
            mutex.unlock();
        }
    }

    public Optional<DecisionDraft> draft(String draftId) {
        mutex.lock();
        try {
            if (currentTurn == null || draftId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(currentTurn.drafts.get(draftId));
        } finally {
            mutex.unlock();
        }
    }

    public Optional<InfoRequest> infoRequest(String requestId) {
        mutex.lock();
        try {
            return Optional.ofNullable(infoRequests.get(requestId));
        } finally {
            mutex.unlock();
        }
    }

    public List<InfoBrief> briefsForPlayer(String playerId) {
        mutex.lock();
        try {
            return briefsByRequest.values().stream()
                    .filter(brief -> brief.playerId().equals(playerId))
                    .toList();
        } finally {
            mutex.unlock();
        }
    }

    public EngineStatus status() {
        mutex.lock();
        try {
            return new EngineStatus(engineRevision, worldVersion, players.size(), directors.size(),
                    Math.toIntExact(eventCount), currentTurn == null ? null : toView(currentTurn));
        } finally {
            mutex.unlock();
        }
    }

    // ---------------------------------------------------------------------
    // 内部校验与工具
    // ---------------------------------------------------------------------

    private TurnStateSnapshot toTurnStateSnapshot(MutableTurn turn) {
        // 保留原回合的插入顺序，使 turnView() 在 export -> restore 后仍逐字段等价。
        return new TurnStateSnapshot(turn.turnId, turn.turnNo, turn.state, turn.revision,
                turn.opensAt, turn.closesAt, turn.openedWorldVersion, turn.lockedAt,
                turn.drafts, turn.decisionsByPlayer, turn.snapshot, turn.committedProposal);
    }

    private void restoreState(TaskflowStateSnapshot snapshot) {
        mutex.lock();
        try {
            require(snapshot.schemaVersion() == TaskflowStateSnapshot.CURRENT_SCHEMA_VERSION,
                    "不支持的快照 schemaVersion: " + snapshot.schemaVersion());
            require(snapshot.promptVersion().equals(promptVersion), "快照 promptVersion 不一致");
            require(snapshot.engineRevision() >= 0 && snapshot.worldVersion() >= 0
                            && snapshot.eventCount() >= 0 && snapshot.lastEventSeq() >= 0
                            && snapshot.lastTurnNo() >= 0,
                    "快照版本/计数不能为负");

            Map<String, Party> restoredParties = new LinkedHashMap<>();
            Map<String, PlayerProfile> restoredPlayers = new LinkedHashMap<>();
            Map<String, PlayerProfile> restoredDirectors = new LinkedHashMap<>();
            Set<String> restoredQqIds = new LinkedHashSet<>();
            for (PartySnapshot partySnapshot : snapshot.parties()) {
                require(partySnapshot != null, "parties 不能含空值");
                PlayerProfile profile = partySnapshot.profile();
                ActorRole role = partySnapshot.role();
                require(role == ActorRole.PLAYER || role == ActorRole.DIRECTOR,
                        "party 角色只能是 PLAYER/DIRECTOR");
                require(restoredParties.putIfAbsent(profile.playerId(),
                        new Party(profile, role)) == null,
                        "parties 含重复 playerId: " + profile.playerId());
                require(restoredQqIds.add(profile.qqId()),
                        "parties 含重复 qqId: " + profile.qqId());
                if (role == ActorRole.PLAYER) {
                    restoredPlayers.put(profile.playerId(), profile);
                } else {
                    restoredDirectors.put(profile.playerId(), profile);
                }
            }

            Map<String, WorldFact> restoredFacts = new LinkedHashMap<>();
            for (Map.Entry<String, FactSnapshot> entry : snapshot.worldFacts().entrySet()) {
                String key = entry.getKey();
                FactSnapshot factSnapshot = entry.getValue();
                require(key != null && !key.isBlank() && factSnapshot != null,
                        "worldFacts 含空 key/value");
                require(key.equals(factSnapshot.factId()),
                        "worldFacts key 与 factId 不一致: " + key);
                WorldFact fact = factSnapshot.toDomain();
                require(fact.worldVersion() <= snapshot.worldVersion(),
                        "worldFact 版本超过快照 worldVersion: " + fact.factId());
                require(restoredFacts.putIfAbsent(fact.factId(), fact) == null,
                        "worldFacts 含重复 factId: " + fact.factId());
            }

            Map<String, SettingProposal> restoredSettings = new LinkedHashMap<>();
            for (Map.Entry<String, SettingProposalSnapshot> entry
                    : snapshot.settingProposals().entrySet()) {
                String key = entry.getKey();
                SettingProposalSnapshot proposalSnapshot = entry.getValue();
                require(key != null && !key.isBlank() && proposalSnapshot != null,
                        "settingProposals 含空 key/value");
                require(key.equals(proposalSnapshot.proposalId()),
                        "settingProposals key 与 proposalId 不一致: " + key);
                SettingProposal proposal = proposalSnapshot.toDomain();
                validateSettingAuthor(proposal, restoredPlayers, restoredDirectors);
                if (proposal.status() == SettingProposalStatus.APPROVED) {
                    WorldFact fact = restoredFacts.get(proposal.worldFactId());
                    require(fact != null, "批准提案缺少对应 worldFact: " + proposal.proposalId());
                    require(("setting-" + proposal.proposalId()).equals(proposal.worldFactId()),
                            "批准提案 worldFactId 不稳定: " + proposal.proposalId());
                    require(fact.content().equals(proposal.content())
                                    && fact.visibility().equals(proposal.visibility()),
                            "批准提案与 worldFact 内容/可见性不一致: " + proposal.proposalId());
                    require(fact.worldVersion() <= snapshot.worldVersion(),
                            "批准提案 worldFact 版本超过快照 worldVersion");
                } else {
                    require(proposal.worldFactId() == null,
                            "未批准提案不能关联 worldFact: " + proposal.proposalId());
                }
                require(restoredSettings.putIfAbsent(proposal.proposalId(), proposal) == null,
                        "settingProposals 含重复 proposalId: " + proposal.proposalId());
            }

            Map<String, InfoRequest> restoredRequests = new LinkedHashMap<>();
            for (Map.Entry<String, InfoRequest> entry : snapshot.infoRequests().entrySet()) {
                String key = entry.getKey();
                InfoRequest request = entry.getValue();
                require(key != null && !key.isBlank() && request != null,
                        "infoRequests 含空 key/value");
                require(key.equals(request.requestId()),
                        "infoRequests key 与 requestId 不一致: " + key);
                require(restoredPlayers.containsKey(request.playerId()),
                        "brief 请求玩家未注册: " + request.playerId());
                require(snapshot.turns().containsKey(request.turnId()),
                        "brief 请求回合不存在: " + request.turnId());
                require(restoredRequests.putIfAbsent(request.requestId(), request) == null,
                        "infoRequests 含重复 requestId: " + request.requestId());
            }

            Map<String, InfoBrief> restoredBriefs = new LinkedHashMap<>();
            for (Map.Entry<String, InfoBrief> entry : snapshot.briefsByRequest().entrySet()) {
                String requestId = entry.getKey();
                InfoBrief brief = entry.getValue();
                require(requestId != null && !requestId.isBlank() && brief != null,
                        "briefsByRequest 含空 key/value");
                InfoRequest request = restoredRequests.get(requestId);
                require(request != null, "brief 缺少对应请求: " + requestId);
                require(request.status() == InfoRequestStatus.ISSUED,
                        "brief 对应请求状态不是 ISSUED: " + requestId);
                require(brief.playerId().equals(request.playerId())
                                && brief.turnId().equals(request.turnId()),
                        "brief 与请求玩家/回合不一致: " + requestId);
                for (String sourceFactId : brief.sourceFactIds()) {
                    WorldFact source = restoredFacts.get(sourceFactId);
                    require(source != null, "brief 引用不存在的事实: " + sourceFactId);
                    require(source.visibility().canSeeAsPlayer(request.playerId()),
                            "brief 引用对玩家不可见的事实: " + sourceFactId);
                }
                require(restoredBriefs.putIfAbsent(requestId, brief) == null,
                        "briefsByRequest 含重复 requestId: " + requestId);
            }
            for (InfoRequest request : restoredRequests.values()) {
                if (request.status() == InfoRequestStatus.ISSUED) {
                    require(restoredBriefs.containsKey(request.requestId()),
                            "ISSUED 请求缺少 brief: " + request.requestId());
                } else {
                    require(!restoredBriefs.containsKey(request.requestId()),
                            "未 ISSUED 请求不能关联 brief: " + request.requestId());
                }
            }

            Map<String, MutableTurn> restoredTurns = new LinkedHashMap<>();
            Set<Integer> turnNumbers = new HashSet<>();
            long maxTurnNo = 0;
            for (Map.Entry<String, TurnStateSnapshot> entry : snapshot.turns().entrySet()) {
                String key = entry.getKey();
                TurnStateSnapshot turnSnapshot = entry.getValue();
                require(key != null && !key.isBlank() && turnSnapshot != null,
                        "turns 含空 key/value");
                require(key.equals(turnSnapshot.turnId()),
                        "turns key 与 turnId 不一致: " + key);
                require(turnNumbers.add(turnSnapshot.turnNo()),
                        "turns 含重复 turnNo: " + turnSnapshot.turnNo());
                require(turnSnapshot.openedWorldVersion() <= snapshot.worldVersion(),
                        "turn openedWorldVersion 超过快照 worldVersion: " + key);
                require(turnSnapshot.closesAt().isAfter(turnSnapshot.opensAt()),
                        "turn closesAt 必须晚于 opensAt: " + key);
                MutableTurn restored = new MutableTurn(turnSnapshot.turnId(),
                        turnSnapshot.turnNo(), turnSnapshot.opensAt(),
                        turnSnapshot.closesAt(), turnSnapshot.openedWorldVersion());
                restored.state = turnSnapshot.state();
                restored.revision = turnSnapshot.revision();
                restored.lockedAt = turnSnapshot.lockedAt();
                restored.snapshot = turnSnapshot.snapshot();
                restored.committedProposal = turnSnapshot.committedProposal();
                restored.drafts.putAll(turnSnapshot.drafts());
                restored.decisionsByPlayer.putAll(turnSnapshot.decisionsByPlayer());
                validateRestoredTurn(restored, snapshot, restoredPlayers);
                require(restoredTurns.putIfAbsent(restored.turnId, restored) == null,
                        "turns 含重复 turnId: " + restored.turnId);
                maxTurnNo = Math.max(maxTurnNo, restored.turnNo);
            }

            require(snapshot.lastTurnNo() == maxTurnNo,
                    "lastTurnNo 与 turns 最大 turnNo 不一致");
            if (restoredTurns.isEmpty()) {
                require(snapshot.currentTurnId() == null,
                        "没有回合时 currentTurnId 必须为空");
            } else {
                require(snapshot.currentTurnId() != null
                                && restoredTurns.containsKey(snapshot.currentTurnId()),
                        "currentTurnId 不存在于 turns: " + snapshot.currentTurnId());
                MutableTurn current = restoredTurns.get(snapshot.currentTurnId());
                require(current.turnNo == maxTurnNo,
                        "currentTurnId 必须指向最新回合");
                for (MutableTurn turn : restoredTurns.values()) {
                    if (!turn.turnId.equals(snapshot.currentTurnId())) {
                        require(turn.state.isTerminal(),
                                "非当前回合必须已终态: " + turn.turnId);
                    }
                }
            }

            Map<String, CommandResult> restoredIdempotency = new LinkedHashMap<>();
            for (Map.Entry<String, CommandResult> entry : snapshot.idempotencyResults().entrySet()) {
                String key = entry.getKey();
                CommandResult result = entry.getValue();
                require(key != null && !key.isBlank() && result != null,
                        "idempotencyResults 含空 key/value");
                require(result.engineRevision() <= snapshot.engineRevision(),
                        "幂等回执 engineRevision 超过快照: " + key);
                if (result.ok()) {
                    require(result.eventSeq() >= 0 && result.eventSeq() <= snapshot.lastEventSeq(),
                            "成功幂等回执 eventSeq 非法: " + key);
                } else {
                    require(result.eventSeq() == -1, "失败幂等回执 eventSeq 必须为 -1: " + key);
                }
                require(restoredIdempotency.putIfAbsent(key, result) == null,
                        "idempotencyResults 含重复 key: " + key);
            }

            parties.putAll(restoredParties);
            players.putAll(restoredPlayers);
            directors.putAll(restoredDirectors);
            worldFacts.putAll(restoredFacts);
            settingProposals.putAll(restoredSettings);
            infoRequests.putAll(restoredRequests);
            briefsByRequest.putAll(restoredBriefs);
            turns.putAll(restoredTurns);
            idempotencyResults.putAll(restoredIdempotency);
            engineRevision = snapshot.engineRevision();
            worldVersion = snapshot.worldVersion();
            eventCount = snapshot.eventCount();
            lastEventSeq = snapshot.lastEventSeq();
            lastTurnNo = snapshot.lastTurnNo();
            currentTurn = snapshot.currentTurnId() == null
                    ? null : restoredTurns.get(snapshot.currentTurnId());
        } finally {
            mutex.unlock();
        }
    }

    private static void validateSettingAuthor(SettingProposal proposal,
                                               Map<String, PlayerProfile> players,
                                               Map<String, PlayerProfile> directors) {
        switch (proposal.authorRole()) {
            case PLAYER -> require(players.containsKey(proposal.authorActorId()),
                    "设定提案作者玩家未注册: " + proposal.proposalId());
            case DIRECTOR -> require(directors.containsKey(proposal.authorActorId()),
                    "设定提案作者推演未注册: " + proposal.proposalId());
            case GM, SUPERVISOR -> {
                // GM/SUPERVISOR 由宿主认证，当前领域没有独立注册表。
            }
            case SECRETARY -> throw new IllegalArgumentException(
                    "SECRETARY 不能作为设定提案作者: " + proposal.proposalId());
        }
    }

    private void validateRestoredTurn(MutableTurn turn, TaskflowStateSnapshot root,
                                      Map<String, PlayerProfile> restoredPlayers) {
        if (turn.state == TurnState.OPEN) {
            require(turn.lockedAt == null && turn.snapshot == null
                            && turn.committedProposal == null,
                    "OPEN 回合不能带冻结快照/提交提案: " + turn.turnId);
        }
        if (turn.state == TurnState.LOCKED || turn.state == TurnState.ADJUDICATING
                || turn.state == TurnState.COMMITTED || turn.state == TurnState.PUBLISHED
                || turn.state == TurnState.FAILED_RETRYABLE) {
            require(turn.snapshot != null && turn.lockedAt != null,
                    "非 OPEN 回合必须带冻结快照和 lockedAt: " + turn.turnId);
        }
        if (turn.state == TurnState.COMMITTED || turn.state == TurnState.PUBLISHED) {
            require(turn.committedProposal != null,
                    "COMMITTED/PUBLISHED 回合必须有 committedProposal: " + turn.turnId);
        } else {
            require(turn.committedProposal == null,
                    "未提交回合不能有 committedProposal: " + turn.turnId);
        }

        Set<String> decisionDraftIds = new HashSet<>();
        for (Map.Entry<String, DecisionDraft> entry : turn.drafts.entrySet()) {
            String draftId = entry.getKey();
            DecisionDraft draft = entry.getValue();
            require(draft != null && draftId != null && draftId.equals(draft.draftId()),
                    "draft map key 与 draftId 不一致: " + turn.turnId);
            require(draft.turnId().equals(turn.turnId),
                    "draft turnId 与回合不一致: " + draftId);
            require(restoredPlayers.containsKey(draft.playerId()),
                    "draft 玩家未注册: " + draft.playerId());
        }
        for (Map.Entry<String, Decision> entry : turn.decisionsByPlayer.entrySet()) {
            String playerId = entry.getKey();
            Decision decision = entry.getValue();
            require(decision != null && playerId != null && playerId.equals(decision.playerId()),
                    "decision map key 与 playerId 不一致: " + turn.turnId);
            require(decision.turnId().equals(turn.turnId),
                    "decision turnId 与回合不一致: " + decision.decisionId());
            require(restoredPlayers.containsKey(decision.playerId()),
                    "decision 玩家未注册: " + decision.playerId());
            DecisionDraft draft = turn.drafts.get(decision.draftId());
            require(draft != null && draft.status() == DraftStatus.CONFIRMED,
                    "decision 缺少已确认 draft: " + decision.decisionId());
            require(decisionDraftIds.add(decision.draftId()),
                    "同一 draft 不能对应多个 decision: " + decision.draftId());
        }

        if (turn.snapshot != null) {
            TurnSnapshot frozen = turn.snapshot;
            require(frozen.turnId().equals(turn.turnId)
                            && frozen.turnNo() == turn.turnNo,
                    "冻结快照 turnId/turnNo 不一致: " + turn.turnId);
            require(frozen.promptVersion().equals(promptVersion),
                    "冻结快照 promptVersion 不一致: " + turn.turnId);
            require(frozen.worldVersion() >= turn.openedWorldVersion
                            && frozen.worldVersion() <= root.worldVersion(),
                    "冻结快照 worldVersion 非法: " + turn.turnId);
            require(frozen.contentHash().equals(Hashes.sha256(TurnSnapshot.canonicalText(
                            frozen.turnId(), frozen.turnNo(), frozen.worldVersion(),
                            frozen.promptVersion(), frozen.decisions(), frozen.confirmedAt()))),
                    "冻结快照 contentHash 不匹配: " + turn.turnId);
            List<Decision> expectedDecisions = turn.decisionsByPlayer.values().stream()
                    .sorted(Comparator.comparing(Decision::playerId))
                    .toList();
            require(frozen.decisions().equals(expectedDecisions),
                    "冻结快照 decisions 与回合决策不一致: " + turn.turnId);
            require(frozen.confirmedAt().equals(turn.lockedAt),
                    "冻结快照 confirmedAt 与 lockedAt 不一致: " + turn.turnId);
        }

        if (turn.committedProposal != null) {
            validateCommittedProposal(turn, turn.committedProposal, root, restoredPlayers);
        }
    }

    private void validateCommittedProposal(MutableTurn turn, AdjudicationProposal proposal,
                                           TaskflowStateSnapshot root,
                                           Map<String, PlayerProfile> restoredPlayers) {
        require(proposal.schemaVersion() == AdjudicationProposal.CURRENT_SCHEMA_VERSION
                        && proposal.turnId().equals(turn.turnId)
                        && proposal.hasValidHash(),
                "committedProposal schema/turn/hash 非法: " + turn.turnId);
        require(proposal.expectedWorldVersion() >= 0
                        && proposal.expectedWorldVersion() <= root.worldVersion(),
                "committedProposal expectedWorldVersion 非法: " + turn.turnId);
        require(turn.snapshot != null
                        && proposal.expectedWorldVersion() >= turn.snapshot.worldVersion(),
                "committedProposal 世界版本早于冻结快照: " + turn.turnId);
        require(proposal.publicNarrative().length() <= MAX_NARRATIVE_CHARS,
                "committedProposal 公开叙述过大");
        require(proposal.worldDeltas().size() <= maxWorldDeltas,
                "committedProposal worldDelta 数量超过上限");
        require(proposal.nextHooks().size() <= MAX_HOOKS,
                "committedProposal nextHooks 数量超过上限");

        Set<String> decisionPlayers = new HashSet<>();
        if (turn.snapshot != null) {
            turn.snapshot.decisions().forEach(d -> decisionPlayers.add(d.playerId()));
        }
        int totalChars = proposal.publicNarrative().length();
        for (Map.Entry<String, String> entry : proposal.playerOutcomes().entrySet()) {
            String playerId = entry.getKey();
            String outcome = entry.getValue();
            require(playerId != null && !playerId.isBlank() && outcome != null
                            && !outcome.isBlank() && outcome.length() <= maxOutcomeChars,
                    "committedProposal outcome 非法");
            require(restoredPlayers.containsKey(playerId) && decisionPlayers.contains(playerId),
                    "committedProposal outcome 玩家非法: " + playerId);
            totalChars += playerId.length() + outcome.length();
        }
        for (WorldDelta delta : proposal.worldDeltas()) {
            require(delta != null && allowedDeltaTypes.contains(delta.type()),
                    "committedProposal worldDelta 类型不在白名单");
            require(delta.targetId().length() <= 512
                            && delta.value().length() <= maxOutcomeChars,
                    "committedProposal worldDelta 字段过大");
            totalChars += delta.type().length() + delta.targetId().length()
                    + delta.value().length();
        }
        for (String hook : proposal.nextHooks()) {
            require(hook != null && !hook.isBlank() && hook.length() <= MAX_HOOK_CHARS,
                    "committedProposal nextHook 非法");
            totalChars += hook.length();
        }
        require(totalChars <= maxProposalChars, "committedProposal 总大小超过上限");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private CommandResult validateProposal(MutableTurn turn, AdjudicationProposal proposal) {
        if (proposal.publicNarrative().length() > MAX_NARRATIVE_CHARS) {
            return tooLarge(turn, "公开叙述过大");
        }
        if (proposal.playerOutcomes().size() > players.size()) {
            return tooLarge(turn, "outcome 玩家数量过大");
        }
        Set<String> decisionPlayers = new LinkedHashSet<>();
        turn.snapshot.decisions().forEach(decision -> decisionPlayers.add(decision.playerId()));
        int totalChars = proposal.publicNarrative().length();
        for (Map.Entry<String, String> entry : proposal.playerOutcomes().entrySet()) {
            String playerId = entry.getKey();
            String outcome = entry.getValue();
            if (playerId == null || playerId.isBlank() || outcome == null || outcome.isBlank()) {
                return failTurn(ErrorCode.PROPOSAL_INVALID, "outcome 玩家或内容为空", turn);
            }
            if (!players.containsKey(playerId) || !decisionPlayers.contains(playerId)) {
                return failTurn(ErrorCode.UNKNOWN_PLAYER, "提案包含非本回合玩家", turn);
            }
            if (outcome.length() > maxOutcomeChars) {
                return tooLarge(turn, "单个 outcome 过大");
            }
            totalChars += playerId.length() + outcome.length();
        }
        if (proposal.worldDeltas().size() > maxWorldDeltas) {
            return tooLarge(turn, "worldDelta 数量超过上限");
        }
        for (WorldDelta delta : proposal.worldDeltas()) {
            if (delta == null) {
                return failTurn(ErrorCode.PROPOSAL_INVALID, "worldDelta 不能为空", turn);
            }
            if (!allowedDeltaTypes.contains(delta.type())) {
                return failTurn(ErrorCode.WORLD_DELTA_NOT_ALLOWED,
                        "worldDelta 类型不在白名单", turn);
            }
            if (delta.targetId().length() > 512 || delta.value().length() > maxOutcomeChars) {
                return tooLarge(turn, "worldDelta 字段过大");
            }
            totalChars += delta.type().length() + delta.targetId().length() + delta.value().length();
        }
        if (proposal.nextHooks().size() > MAX_HOOKS) {
            return tooLarge(turn, "nextHooks 数量超过上限");
        }
        for (String hook : proposal.nextHooks()) {
            if (hook == null || hook.isBlank()) {
                return failTurn(ErrorCode.PROPOSAL_INVALID, "nextHook 不能为空", turn);
            }
            if (hook.length() > MAX_HOOK_CHARS) {
                return tooLarge(turn, "nextHook 过大");
            }
            totalChars += hook.length();
        }
        if (totalChars > maxProposalChars) {
            return tooLarge(turn, "提案总大小超过上限");
        }
        return null;
    }

    private CommandResult tooLarge(MutableTurn turn, String message) {
        return failTurn(ErrorCode.PROPOSAL_TOO_LARGE, message, turn);
    }

    private boolean isRegisteredSettingAuthor(Actor author) {
        return switch (author.role()) {
            case PLAYER -> players.containsKey(author.actorId());
            case DIRECTOR -> directors.containsKey(author.actorId());
            // GM/SUPERVISOR 只由宿主构造为已认证操作者快照，当前没有独立注册命令。
            case GM, SUPERVISOR -> true;
            case SECRETARY -> false;
        };
    }

    private String visibilitySummary(Visibility visibility) {
        if (visibility instanceof Visibility.PublicVisibility) {
            return "PUBLIC";
        }
        if (visibility instanceof Visibility.DirectorOnlyVisibility) {
            return "DIRECTOR_ONLY";
        }
        if (visibility instanceof Visibility.PlayerSetVisibility playersVisibility) {
            return "PLAYERS:" + String.join(",", playersVisibility.playerIds().stream()
                    .sorted().toList());
        }
        return visibility.getClass().getSimpleName();
    }

    private CommandResult requirePlayer(Actor player) {
        if (player.role() != ActorRole.PLAYER) {
            return fail(ErrorCode.FORBIDDEN, "只有 PLAYER 可以执行该命令", engineRevision, null);
        }
        if (!players.containsKey(player.actorId())) {
            return fail(ErrorCode.UNKNOWN_PLAYER, "玩家未注册", engineRevision, null);
        }
        return null;
    }

    private boolean isAuthorizer(Actor actor) {
        return actor.role() == ActorRole.DIRECTOR || actor.role() == ActorRole.GM
                || actor.role() == ActorRole.SUPERVISOR;
    }

    private boolean isTurnOperator(Actor actor) {
        return actor.role() == ActorRole.DIRECTOR || actor.role() == ActorRole.GM
                || actor.role() == ActorRole.SUPERVISOR;
    }

    private boolean qqExists(String qqId) {
        return parties.values().stream().anyMatch(party -> party.profile.qqId().equals(qqId));
    }

    private String previewHash(String turnId, String playerId, DecisionDraftInput input) {
        return Hashes.sha256("turn=" + turnId + "|player=" + playerId + "|" + input.canonicalText());
    }

    private CommandResult duplicate(String idempotencyKey) {
        return idempotencyResults.get(idempotencyKey);
    }

    private CommandResult remember(String idempotencyKey, CommandResult result) {
        idempotencyResults.put(idempotencyKey, result);
        return result;
    }

    private CommandResult withWrite(String idempotencyKey, WriteAction action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return fail(ErrorCode.INVALID_ARGUMENT, "idempotencyKey 不能为空", engineRevision, null);
        }
        mutex.lock();
        try {
            CommandResult result = action.run();
            StateCommitListener listener = stateCommitListener;
            if (listener != null && result.ok()) {
                // 仍在锁内；监听器异常必须向上传播，不能吞掉。
                listener.afterCommit(this, result);
            }
            return result;
        } finally {
            mutex.unlock();
        }
    }

    private long append(EventType type, Actor actor, String turnId, String idempotencyKey,
                        Map<String, String> payload) {
        long seq = eventSink.append(new TaskflowEvent(0, clock.instant(), type, actor.role(),
                actor.actorId(), turnId, idempotencyKey, payload));
        eventCount++;
        lastEventSeq = seq;
        return seq;
    }

    private CommandResult success(long revision, long engineRevision, long eventSeq,
                                  TurnState state, String message, String referenceId,
                                  Map<String, String> details) {
        Map<String, String> safeDetails = new LinkedHashMap<>(details == null ? Map.of() : details);
        if (referenceId != null) {
            safeDetails.put("referenceId", referenceId);
        }
        return new CommandResult(ErrorCode.OK, revision, engineRevision, eventSeq, state,
                message, safeDetails);
    }

    private CommandResult fail(ErrorCode code, String message, long revision, TurnState state) {
        return new CommandResult(code, revision, engineRevision, -1, state, message, Map.of());
    }

    private CommandResult failTurn(ErrorCode code, String message, MutableTurn turn) {
        return fail(code, message, turn.revision, turn.state);
    }

    private TurnView toView(MutableTurn turn) {
        return new TurnView(turn.turnId, turn.turnNo, turn.state, turn.revision, worldVersion,
                promptVersion, turn.opensAt, turn.closesAt, turn.lockedAt,
                new ArrayList<>(turn.drafts.values()),
                new ArrayList<>(turn.decisionsByPlayer.values()),
                turn.snapshot, turn.committedProposal);
    }

    @FunctionalInterface
    private interface WriteAction {
        CommandResult run();
    }

    private record Party(PlayerProfile profile, ActorRole role) {
    }

    private static final class MutableTurn {
        private final String turnId;
        private final int turnNo;
        private final Instant opensAt;
        private final Instant closesAt;
        private final long openedWorldVersion;
        private final Map<String, DecisionDraft> drafts = new LinkedHashMap<>();
        private final Map<String, Decision> decisionsByPlayer = new LinkedHashMap<>();
        private TurnState state = TurnState.OPEN;
        private long revision = 1;
        private Instant lockedAt;
        private TurnSnapshot snapshot;
        private AdjudicationProposal committedProposal;

        private MutableTurn(String turnId, int turnNo, Instant opensAt, Instant closesAt,
                            long openedWorldVersion) {
            this.turnId = turnId;
            this.turnNo = turnNo;
            this.opensAt = opensAt;
            this.closesAt = closesAt;
            this.openedWorldVersion = openedWorldVersion;
        }
    }
}
