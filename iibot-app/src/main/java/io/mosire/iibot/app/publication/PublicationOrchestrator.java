package io.mosire.iibot.app.publication;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.iibot.app.event.content.EventContentStore;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchStatus;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.event.DomainEvent;
import io.mosire.iibot.taskflow.event.DomainEventJournal;
import io.mosire.iibot.taskflow.event.DomainEventTypes;
import io.mosire.iibot.taskflow.event.EventContentType;
import io.mosire.iibot.taskflow.event.EventVisibility;
import io.mosire.iibot.taskflow.event.StoredEvent;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostCommandResult;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 已 COMMITTED ActionBatch 的发布编排。
 *
 * <p>流程严格为：读取批次 → 中央 LLM 生成有界严格 JSON → 创建 PENDING_APPROVAL
 * 推文草稿 → 追加 PUBLICATION_GENERATED 映射事件。本类没有任何发送依赖，GM 未执行
 * {@code tf_post_publish} 前不会触碰 QQ 外发。事件 content 保存完整
 * {@link PublicationRecord}，summary 只保存 ID/计数/hash。</p>
 *
 * <p>重试语义：批次已生成时直接返回内存/事件重建记录；posts 使用确定性幂等键
 * {@code publication:<batchId>:public} 与 {@code publication:<batchId>:private:<playerId>}。
 * 若 posts 已创建但映射事件追加失败，下一次重试复用现有 posts，绝不重复创建。</p>
 */
public final class PublicationOrchestrator {

    private static final ObjectMapper JSON = createMapper();
    private static final Set<ActorRole> GM_ROLES =
            Set.of(ActorRole.DIRECTOR, ActorRole.GM, ActorRole.SUPERVISOR);

    static final int MAX_IDEMPOTENCY_KEY_CHARS = 200;
    static final int MAX_BATCH_ID_CHARS = 200;
    static final int MAX_PROMPT_CHARS = 48_000;
    static final int MAX_FACT_CHARS = 2_000;
    static final int MAX_FACTS_CHARS = 20_000;
    static final int MAX_RESULT_CHARS = 16_000;
    static final int MAX_ACTION_CHARS = 12_000;
    static final int MAX_TOTAL_PRIVATE_OUTPUT_CHARS = 40_000;
    static final int MAX_OUTPUT_TOKENS = 4_096;

    private final PostService postService;
    private final ActionPackageService packageService;
    private final LlmClient adjudicatorLlm;
    private final TaskflowEngine engine;
    private final EventContentStore contentStore;
    private final DomainEventJournal journal;
    private final Clock clock;
    private final Map<String, PublicationRecord> recordsByPublication = new LinkedHashMap<>();
    private final Map<String, PublicationRecord> recordsByBatch = new LinkedHashMap<>();

    public PublicationOrchestrator(PostService postService,
                                    ActionPackageService packageService,
                                    LlmClient adjudicatorLlm,
                                    EventContentStore contentStore,
                                    DomainEventJournal journal,
                                    Clock clock) {
        this(postService, packageService, adjudicatorLlm, null, contentStore, journal, clock);
    }

    /** 依赖顺序兼容别名：journal 在 contentStore 之前。 */
    public PublicationOrchestrator(PostService postService,
                                    ActionPackageService packageService,
                                    LlmClient adjudicatorLlm,
                                    DomainEventJournal journal,
                                    EventContentStore contentStore,
                                    Clock clock) {
        this(postService, packageService, adjudicatorLlm, null, contentStore, journal, clock);
    }

    /** 带 clock 前置的兼容装配别名。 */
    public PublicationOrchestrator(PostService postService,
                                    ActionPackageService packageService,
                                    LlmClient adjudicatorLlm,
                                    Clock clock,
                                    EventContentStore contentStore,
                                    DomainEventJournal journal) {
        this(postService, packageService, adjudicatorLlm, null, contentStore, journal, clock);
    }

    public PublicationOrchestrator(PostService postService,
                                    ActionPackageService packageService,
                                    LlmClient adjudicatorLlm,
                                    TaskflowEngine engine,
                                    EventContentStore contentStore,
                                    DomainEventJournal journal,
                                    Clock clock) {
        this.postService = Objects.requireNonNull(postService, "postService");
        this.packageService = Objects.requireNonNull(packageService, "packageService");
        this.adjudicatorLlm = Objects.requireNonNull(adjudicatorLlm, "adjudicatorLlm");
        this.engine = engine;
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.clock = Objects.requireNonNull(clock, "clock");
        reloadRecordsFromEvents();
    }

    /**
     * 生成一个批次的发布草稿并记录映射。
     *
     * @throws PublicationException 稳定失败码；失败不发送、不创建半条映射事件
     */
    public synchronized PublicationRecord generate(Actor gm, String batchId,
                                                    String idempotencyKey) {
        requireGm(gm);
        requireText(batchId, "batchId", MAX_BATCH_ID_CHARS);
        requireText(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_CHARS);

        // 同一 batch 的 canonical 事实在 events.db。每次 generate 先重读，覆盖上一个
        // 进程/线程在 append 已提交但调用方收到异常时留下的内存盲区。
        reloadRecordsFromEvents();
        PublicationRecord existing = recordsByBatch.get(batchId);
        if (existing != null) {
            return existing;
        }

        ActionBatch batch = packageService.batches().stream()
                .filter(candidate -> candidate.batchId().equals(batchId))
                .findFirst()
                .orElseThrow(() -> new PublicationException(
                        PublicationException.BATCH_NOT_FOUND,
                        "行动批次不存在: " + batchId));
        if (batch.status() != ActionBatchStatus.COMMITTED) {
            throw new PublicationException(PublicationException.BATCH_NOT_COMMITTED,
                    "只有 COMMITTED 行动批次可以生成发布草稿");
        }
        if (batch.playerIds().isEmpty()) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "行动批次没有可发布玩家");
        }

        String publicTitle = publicDraftTitle(batchId);
        String publicKey = publicKey(batchId);
        Post existingPublic = postService.findPublicationDraftForReuse(
                gm, publicKey, PostService.SYSTEM_PUBLICATION_AUTHOR,
                PostVisibility.PUBLIC, publicTitle).orElse(null);
        Map<String, Post> existingPrivate = new LinkedHashMap<>();
        for (String playerId : new LinkedHashSet<>(batch.playerIds())) {
            Post existingPost = postService.findPublicationDraftForReuse(
                    gm, privateKey(batchId, playerId), playerId,
                    PostVisibility.PRIVATE, privateDraftTitle(batchId, playerId))
                    .orElse(null);
            if (existingPost != null) {
                existingPrivate.put(playerId, existingPost);
            }
        }

        String prompt = buildPrompt(batch);
        if (prompt == null) {
            throw new PublicationException(PublicationException.PUBLICATION_INPUT_TOO_LARGE,
                    "批次输入超过发布生成上下文上限，未调用模型");
        }
        PublicationOutput output = callModel(prompt);
        validateOutput(output, batch);

        Post publicPost = resolvePublicationPost(gm, existingPublic, publicKey,
                PostService.SYSTEM_PUBLICATION_AUTHOR, PostVisibility.PUBLIC,
                publicTitle, output.publicNarrative());

        Map<String, String> privatePostIds = new LinkedHashMap<>();
        Map<String, String> privateOutcomes = new LinkedHashMap<>();
        for (String playerId : new LinkedHashSet<>(batch.playerIds())) {
            String outcome = output.privateOutcomes().get(playerId);
            Post privatePost = resolvePublicationPost(gm, existingPrivate.get(playerId),
                    privateKey(batchId, playerId), playerId, PostVisibility.PRIVATE,
                    privateDraftTitle(batchId, playerId), outcome);
            if (privatePost != null) {
                privatePostIds.put(playerId, privatePost.postId());
                // 只用已持久化 Post.body；本次 LLM 文本不覆盖既有草稿。
                privateOutcomes.put(playerId, privatePost.body());
            }
        }

        if (publicPost == null && privatePostIds.isEmpty()) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "公共与私密输出均为空，未创建发布记录");
        }

        Instant now = clock.instant();
        PublicationRecord record = new PublicationRecord(
                publicationIdForBatch(batchId), batchId, batch.turnId(),
                publicPost == null ? null : publicPost.postId(),
                privatePostIds,
                publicPost == null ? "" : publicPost.body(),
                privateOutcomes, now);

        DomainEvent event = new DomainEvent(now, DomainEventTypes.PUBLICATION_GENERATED,
                gm.role(), gm.actorId(), record.publicationId(), batch.turnId(),
                EventVisibility.GM, record, summary(record));
        try {
            long seq = journal.append(event);
            if (seq <= 0) {
                throw new IllegalStateException("journal 未返回有效 seq");
            }
        } catch (RuntimeException appendFailure) {
            // append 可能已在事件库提交后才抛异常。先回读 canonical 事件；只有确认
            // 没有该 batch 的 PUBLICATION_GENERATED 才报告稳定不可用。
            try {
                reloadRecordsFromEvents();
            } catch (RuntimeException reloadFailure) {
                throw new PublicationException(
                        PublicationException.PUBLICATION_RECORD_CORRUPT,
                        "发布记录事件 append 异常后无法回查 canonical 状态",
                        reloadFailure);
            }
            PublicationRecord persisted = recordsByBatch.get(batchId);
            if (persisted != null) {
                return persisted;
            }
            throw new PublicationException(PublicationException.PUBLICATION_EVENT_UNAVAILABLE,
                    "发布记录事件暂不可用；推文草稿已幂等保留，可用同一 batchId 重试",
                    appendFailure);
        }

        remember(record);
        return record;
    }

    /** 已完成发布的只读记录（按生成顺序）。 */
    public synchronized List<PublicationRecord> records() {
        return List.copyOf(recordsByPublication.values());
    }

    public synchronized Optional<PublicationRecord> recordForBatch(String batchId) {
        return Optional.ofNullable(batchId == null ? null : recordsByBatch.get(batchId));
    }

    public synchronized Optional<PublicationRecord> record(String publicationId) {
        return Optional.ofNullable(
                publicationId == null ? null : recordsByPublication.get(publicationId));
    }

    public synchronized Optional<PublicationRecord> findByBatchId(String batchId) {
        return recordForBatch(batchId);
    }

    public synchronized Optional<PublicationRecord> findByPublicationId(String publicationId) {
        return record(publicationId);
    }

    static String publicKey(String batchId) {
        return "publication:" + batchId + ":public";
    }

    static String privateKey(String batchId, String playerId) {
        return "publication:" + batchId + ":private:" + playerId;
    }

    static String publicDraftTitle(String batchId) {
        return "批次 " + batchId + " public 公共叙事";
    }

    static String privateDraftTitle(String batchId, String playerId) {
        return "批次 " + batchId + " private " + playerId + " 私密结果";
    }

    /** publicationId 由 batchId 唯一确定，重试/回读不会产生第二个 ID。 */
    public static String publicationIdForBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        return "pub-" + Hashes.sha256(batchId);
    }

    private Post resolvePublicationPost(Actor gm, Post existing, String key,
                                        String authorPlayerId, PostVisibility visibility,
                                        String title, String newText) {
        if (existing != null) {
            if (existing.visibility() != visibility
                    || !existing.authorPlayerId().equals(authorPlayerId)
                    || !existing.title().equals(title)) {
                throw new PublicationException(
                        PublicationException.PUBLICATION_POST_UNAVAILABLE,
                        "既有 publication draft 与确定性 author/visibility/title 不一致");
            }
            return existing;
        }
        if (newText == null || newText.isBlank()) {
            return null;
        }
        // 新草稿仍唯一经过 createPublicationDraft；key 已被其他命令占用时返回冲突，
        // 这里 fail-closed，不生成 PublicationRecord。
        PostCommandResult result = postService.createPublicationDraft(
                gm, authorPlayerId, visibility, title, newText, key);
        if (result == null || !result.ok() || result.postId() == null) {
            throw new PublicationException(PublicationException.PUBLICATION_POST_UNAVAILABLE,
                    "创建发布草稿失败 code=" + (result == null ? "NULL_RESULT" : result.code()));
        }
        Post post = postService.post(result.postId()).orElseThrow(() ->
                new PublicationException(PublicationException.PUBLICATION_POST_UNAVAILABLE,
                        "发布草稿创建回执指向不存在的推文"));
        if (post.visibility() != visibility
                || !post.authorPlayerId().equals(authorPlayerId)
                || !post.title().equals(title)
                || !post.body().equals(newText)) {
            throw new PublicationException(PublicationException.PUBLICATION_POST_UNAVAILABLE,
                    "发布草稿回执与预期 author/visibility/title/body 不一致");
        }
        return post;
    }

    private PublicationOutput callModel(String prompt) {
        try {
            LlmRequest request = new LlmRequest(List.of(
                    LlmMessage.system(systemPrompt()),
                    LlmMessage.user(prompt)))
                    .withMaxTokens(MAX_OUTPUT_TOKENS)
                    .withTemperature(0.1);
            LlmResponse response = adjudicatorLlm.chat(request);
            String text = response == null ? "" : response.textPart().orElse("").trim();
            if (text.isBlank()) {
                throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                        "发布模型返回空输出");
            }
            return parseStrictOutput(text);
        } catch (PublicationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 不记录/回显异常消息或模型正文；供应商异常可能带上文片段。
            throw new PublicationException(PublicationException.PUBLICATION_LLM_FAILED,
                    "发布模型调用失败（内部原因已脱敏）", failure);
        }
    }

    private static PublicationOutput parseStrictOutput(String text) {
        if (text.startsWith("```") || text.endsWith("```")) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "发布模型输出不得使用 Markdown code fence");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException failure) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "发布模型输出不是合法严格 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "发布模型输出必须是 JSON object");
        }
        Set<String> fields = new LinkedHashSet<>();
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            fields.add(names.next());
        }
        if (!fields.equals(Set.of("publicNarrative", "privateOutcomes"))) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "发布模型输出字段必须且只能是 publicNarrative/privateOutcomes");
        }
        JsonNode publicNode = root.get("publicNarrative");
        JsonNode privateNode = root.get("privateOutcomes");
        if (publicNode == null || !publicNode.isTextual()
                || privateNode == null || !privateNode.isObject()) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "发布模型输出字段类型非法");
        }
        String publicNarrative = publicNode.textValue();
        Map<String, String> privateOutcomes = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> privateFields = privateNode.fields();
        while (privateFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = privateFields.next();
            String playerId = entry.getKey();
            if (playerId == null || playerId.isBlank() || !entry.getValue().isTextual()) {
                throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                        "privateOutcomes 必须是非空玩家键到字符串的映射");
            }
            if (privateOutcomes.put(playerId, entry.getValue().textValue()) != null) {
                throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                        "privateOutcomes 含重复玩家键");
            }
        }
        return new PublicationOutput(publicNarrative, privateOutcomes);
    }

    private static void validateOutput(PublicationOutput output, ActionBatch batch) {
        Objects.requireNonNull(output, "output");
        if (output.publicNarrative() == null || output.privateOutcomes() == null) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "发布模型输出字段缺失");
        }
        if (output.publicNarrative().length() > PostService.MAX_BODY_LENGTH) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "publicNarrative 超过长度上限");
        }
        Set<String> allowedPlayers = new LinkedHashSet<>(batch.playerIds());
        int totalPrivateChars = 0;
        for (Map.Entry<String, String> entry : output.privateOutcomes().entrySet()) {
            if (!allowedPlayers.contains(entry.getKey())) {
                throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                        "privateOutcomes 含批次外玩家键");
            }
            String value = entry.getValue();
            if (value == null) {
                throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                        "privateOutcomes 含 null 文本");
            }
            if (value.length() > PostService.MAX_BODY_LENGTH) {
                throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                        "私密 outcome 超过长度上限");
            }
            totalPrivateChars = Math.addExact(totalPrivateChars, value.length());
        }
        if (totalPrivateChars > MAX_TOTAL_PRIVATE_OUTPUT_CHARS) {
            throw new PublicationException(PublicationException.PUBLICATION_OUTPUT_INVALID,
                    "私密 outcome 总长度超过上限");
        }
    }

    private String buildPrompt(ActionBatch batch) {
        StringBuilder prompt = new StringBuilder(8_192);
        prompt.append("你是 iiBotMosire 的发布编排者。只输出一个严格 JSON object，"
                + "不得输出 Markdown code fence、解释、前后缀或额外字段。\n")
                .append("JSON schema: {\"publicNarrative\":\"...\","
                        + "\"privateOutcomes\":{\"<batch内playerId>\":\"...\"}}\n")
                .append("硬性规则：\n")
                .append("1) publicNarrative 只能描述可在公共群公开的世界级结果，"
                        + "禁止出现任何 playerId、玩家私密行动原文、私密推理或私密结果。\n")
                .append("2) privateOutcomes 的 key 只能来自本批次 playerIds 子集；"
                        + "每个 key 的文本只能写该玩家自己的可见结果，禁止混入其他玩家私密内容。\n")
                .append("3) 没有公共内容时 publicNarrative 输出空字符串；"
                        + "没有某玩家私密内容时不要包含该 key。\n")
                .append("4) 不得编造未提供的事实，不得创建新玩家、新行动或新世界规则。\n")
                .append("5) 公共与私密文本各自不超过 ")
                .append(PostService.MAX_BODY_LENGTH).append(" 字符。\n");
        prompt.append("\n批次 ID: ").append(batch.batchId())
                .append("\n包 ID: ").append(batch.packageId())
                .append("\n回合 ID: ").append(batch.turnId() == null ? "" : batch.turnId())
                .append("\n批次状态: ").append(batch.status()).append('\n');

        if (engine != null) {
            prompt.append("\n世界版本: ").append(engine.status().worldVersion())
                    .append("\n引擎 revision: ").append(engine.status().engineRevision())
                    .append('\n');
            StringBuilder facts = new StringBuilder();
            int included = 0;
            for (WorldFact fact : engine.allWorldFacts()) {
                if (facts.length() >= MAX_FACTS_CHARS) {
                    break;
                }
                String content = fact.content();
                if (content.length() > MAX_FACT_CHARS) {
                    content = content.substring(0, MAX_FACT_CHARS) + "…[事实已截断]";
                }
                String line = "- [" + visibilityLabel(fact.visibility()) + "] "
                        + fact.factId() + ": " + content + "\n";
                int remaining = MAX_FACTS_CHARS - facts.length();
                if (line.length() > remaining) {
                    facts.append(line, 0, remaining);
                    break;
                }
                facts.append(line);
                included++;
            }
            prompt.append("\n世界事实（共 ").append(included).append(" 条进入上下文）:\n")
                    .append(facts);
        }

        String batchResult = batch.result() == null ? "" : batch.result().trim();
        if (batchResult.length() > MAX_RESULT_CHARS) {
            batchResult = batchResult.substring(0, MAX_RESULT_CHARS) + "\n…[批次结果已截断]";
        }
        prompt.append("\n已提交批次结果（GM 内部裁定，生成公共文本时必须去除私密细节）:\n")
                .append(batchResult).append("\n\n本批次行动（每项仅属于其 playerId，"
                        + "不得在 publicNarrative 中复述）:\n");
        for (int i = 0; i < batch.actionIds().size(); i++) {
            String actionText = batch.texts().get(i);
            if (actionText.length() > MAX_ACTION_CHARS) {
                actionText = actionText.substring(0, MAX_ACTION_CHARS) + "…[行动已截断]";
            }
            prompt.append("#").append(i)
                    .append(" actionId=").append(batch.actionIds().get(i))
                    .append(" playerId=").append(batch.playerIds().get(i))
                    .append("\n行动正文：").append(actionText).append("\n\n");
        }
        prompt.append("\n再次强调：只输出严格 JSON；publicNarrative 不得泄露任何玩家私密内容。");
        return prompt.length() <= MAX_PROMPT_CHARS ? prompt.toString() : null;
    }

    private static String systemPrompt() {
        return "你是 iiBotMosire 的发布编排者。你只根据给定批次、世界事实和裁定结果，"
                + "生成公共叙事与各玩家私密结果。公共与私密边界必须硬隔离；你没有工具，"
                + "不发送消息，不修改世界。只输出 schema 指定的严格 JSON。";
    }

    private static String visibilityLabel(Visibility visibility) {
        if (visibility instanceof Visibility.PublicVisibility) {
            return "PUBLIC";
        }
        if (visibility instanceof Visibility.DirectorOnlyVisibility) {
            return "DIRECTOR_ONLY";
        }
        return "PLAYER_SET";
    }

    private void reloadRecordsFromEvents() {
        List<StoredEvent> events;
        try {
            events = contentStore.readByTypePrefix(DomainEventTypes.PUBLICATION_PREFIX);
        } catch (RuntimeException failure) {
            throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                    "发布记录事件重建读取失败", failure);
        }
        Map<String, PublicationRecord> byPublication = new LinkedHashMap<>();
        Map<String, PublicationRecord> byBatch = new LinkedHashMap<>();
        for (StoredEvent event : events) {
            if (!DomainEventTypes.PUBLICATION_GENERATED.equals(event.eventType())) {
                continue;
            }
            if (event.contentMissing() || event.content() == null
                    || event.content().type() != EventContentType.JSON) {
                throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                        "发布记录事件缺少 JSON 完整正文 seq=" + event.seq());
            }
            PublicationRecord record;
            try {
                record = JSON.readValue(event.content().json(), PublicationRecord.class);
            } catch (JsonProcessingException | IllegalArgumentException failure) {
                throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                        "发布记录事件正文无法严格解析 seq=" + event.seq(), failure);
            }
            PublicationRecord priorByPublication = byPublication.get(record.publicationId());
            if (priorByPublication != null && !priorByPublication.equals(record)) {
                throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                        "重复 publicationId 对应不同发布记录");
            }
            PublicationRecord priorByBatch = byBatch.get(record.batchId());
            if (priorByBatch != null && !priorByBatch.equals(record)) {
                throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                        "同一 batchId 对应不同发布记录");
            }
            byPublication.putIfAbsent(record.publicationId(), record);
            byBatch.putIfAbsent(record.batchId(), record);
        }
        recordsByPublication.clear();
        recordsByPublication.putAll(byPublication);
        recordsByBatch.clear();
        recordsByBatch.putAll(byBatch);
    }

    private void remember(PublicationRecord record) {
        PublicationRecord byPublication = recordsByPublication.get(record.publicationId());
        if (byPublication != null && !byPublication.equals(record)) {
            throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                    "重复 publicationId 对应不同发布记录");
        }
        PublicationRecord byBatch = recordsByBatch.get(record.batchId());
        if (byBatch != null && !byBatch.equals(record)) {
            throw new PublicationException(PublicationException.PUBLICATION_RECORD_CORRUPT,
                    "同一 batchId 对应不同发布记录");
        }
        // deterministic publicationId + 完整 record 相等时是安全去重，不追加/不报错。
        recordsByPublication.putIfAbsent(record.publicationId(), record);
        recordsByBatch.putIfAbsent(record.batchId(), record);
    }

    private static Map<String, Object> summary(PublicationRecord record) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("publicationId", record.publicationId());
        summary.put("batchId", record.batchId());
        summary.put("turnId", record.turnId());
        summary.put("publicPostId", record.publicPostId());
        summary.put("privatePostIds", record.privatePostIds());
        summary.put("publicPostCount", record.publicPostId() == null ? 0 : 1);
        summary.put("privatePostCount", record.privatePostIds().size());
        summary.put("publicNarrativeHash", record.publicNarrativeHash());
        summary.put("privateOutcomeHashes", record.privateOutcomeHashes());
        summary.put("contentHash", record.contentHash());
        return summary;
    }

    private static void requireGm(Actor gm) {
        if (gm == null || !GM_ROLES.contains(gm.role())) {
            throw new PublicationException(PublicationException.FORBIDDEN,
                    "只有 GM/DIRECTOR/SUPERVISOR 可以生成发布草稿");
        }
    }

    private static void requireText(String value, String name, int maxChars) {
        if (value == null || value.isBlank()) {
            throw new PublicationException(PublicationException.INVALID_ARGUMENTS,
                    name + " 不能为空白");
        }
        if (value.length() > maxChars) {
            throw new PublicationException(PublicationException.INVALID_ARGUMENTS,
                    name + " 超过长度上限");
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return mapper;
    }

    private record PublicationOutput(String publicNarrative,
                                     Map<String, String> privateOutcomes) {
    }
}
