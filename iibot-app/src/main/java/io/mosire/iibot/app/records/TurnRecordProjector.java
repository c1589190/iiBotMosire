package io.mosire.iibot.app.records;

import io.mosire.iibot.app.publication.PublicationKind;
import io.mosire.iibot.app.publication.PublicationOrchestrator;
import io.mosire.iibot.app.publication.PublicationRecord;
import io.mosire.iibot.taskflow.Hashes;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageItemView;
import io.mosire.iibot.taskflow.decision.PackageView;
import io.mosire.iibot.taskflow.post.Post;
import io.mosire.iibot.taskflow.post.PostService;
import io.mosire.iibot.taskflow.post.PostVisibility;
import io.mosire.iibot.taskflow.snapshot.TaskflowStateSnapshot;
import io.mosire.iibot.taskflow.snapshot.TurnStateSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 将 engine/领域快照幂等投影为 {@code dataDir/records/turns/<turnNo>-<turnId>/} Markdown。
 *
 * <p>Markdown 是派生投影，不是第二事实源。公共叙事只取 publication 的 public 映射；
 * {@code players/<playerId>.md} 只取该玩家的私密 outcome。{@code kind=OPENING} 的独立
 * 开场推文投影到 {@code records/openings/<publicationId>/}，不伪装成 turn。所有逻辑 ID
 * 在拼接路径前做白名单校验，目标目录拒绝软链/越界；写文件使用同目录临时文件 + 原子
 * move + 600 best-effort 权限。投影异常不会回滚任何领域事件。</p>
 */
public final class TurnRecordProjector {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");
    private static final List<String> MANAGED_DIRECTORIES =
            List.of("players", "actions", "packages", "batches", "posts");
    private static final List<String> OPENING_MANAGED_DIRECTORIES = List.of("posts");

    private final Path dataDir;
    private final TaskflowEngine engine;
    private final ActionPackageService packageService;
    private final PostService postService;
    private final PublicationOrchestrator publications;
    private List<TurnRecordSummary> summaries = List.of();
    private List<RecordSummary> recordSummaries = List.of();
    private List<RecordSummary> openingRecordSummaries = List.of();
    private Map<String, Map<String, String>> manifestsByTurn = Map.of();
    private Map<String, Map<String, String>> manifestsByOpening = Map.of();
    private boolean projectionPublished;

    public TurnRecordProjector(Path dataDir, TaskflowEngine engine,
                               ActionPackageService packageService,
                               PostService postService,
                               PublicationOrchestrator publications) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize();
        this.engine = Objects.requireNonNull(engine, "engine");
        this.packageService = Objects.requireNonNull(packageService, "packageService");
        this.postService = Objects.requireNonNull(postService, "postService");
        this.publications = Objects.requireNonNull(publications, "publications");
    }

    /**
     * 全量重建所有 engine 回合的记录目录。任何不安全 ID/路径、软链或 IO 失败都会
     * fail-closed 抛出 {@link RecordProjectionException}，但已提交领域事件不受影响。
     */
    public synchronized void rebuildAll() {
        Path turnsRoot = ensureTurnsRoot();
        Path openingsRoot = ensureOpeningsRoot();
        TaskflowStateSnapshot state = engine.exportState();
        List<TurnStateSnapshot> turns = state.turns().values().stream()
                .sorted(Comparator.comparingInt(TurnStateSnapshot::turnNo)
                        .thenComparing(TurnStateSnapshot::turnId))
                .toList();

        List<TurnRecordSummary> nextSummaries = new ArrayList<>();
        List<RecordSummary> nextRecords = new ArrayList<>();
        Map<String, Map<String, String>> nextManifests = new LinkedHashMap<>();
        for (TurnStateSnapshot turn : turns) {
            RenderedTurn rendered = renderTurn(turn);
            Path target = turnsRoot.resolve(turnDirName(turn));
            writeTurnAtomically(turnsRoot, target, rendered);
            nextSummaries.add(rendered.summary());
            nextRecords.addAll(rendered.records());
            Map<String, String> fileHashes = new LinkedHashMap<>();
            rendered.files().forEach((file, content) ->
                    fileHashes.put(file, hash(content)));
            nextManifests.put(rendered.summary().turnId(),
                    Collections.unmodifiableMap(fileHashes));
        }

        List<RecordSummary> nextOpeningRecords = new ArrayList<>();
        Map<String, Map<String, String>> nextOpeningManifests = new LinkedHashMap<>();
        List<PublicationRecord> openings = publications.records().stream()
                .filter(record -> record.kind() == PublicationKind.OPENING)
                .sorted(Comparator.comparing(PublicationRecord::createdAt)
                        .thenComparing(PublicationRecord::publicationId))
                .toList();
        for (PublicationRecord record : openings) {
            RenderedOpening rendered = renderOpening(record);
            Path target = openingsRoot.resolve(
                    safeLogicalId(record.publicationId(), "publicationId"));
            writeRecordAtomically(openingsRoot, target, rendered.files(),
                    OPENING_MANAGED_DIRECTORIES);
            nextOpeningRecords.add(rendered.summary());
            Map<String, String> fileHashes = new LinkedHashMap<>();
            rendered.files().forEach((file, content) ->
                    fileHashes.put(file, hash(content)));
            nextOpeningManifests.put(record.publicationId(),
                    Collections.unmodifiableMap(fileHashes));
        }

        // 只有所有 turn/opening 都完整写完并安全替换后才发布新索引视图；失败保留旧视图。
        summaries = List.copyOf(nextSummaries);
        recordSummaries = List.copyOf(nextRecords);
        openingRecordSummaries = List.copyOf(nextOpeningRecords);
        manifestsByTurn = Collections.unmodifiableMap(nextManifests);
        manifestsByOpening = Collections.unmodifiableMap(nextOpeningManifests);
        projectionPublished = true;
    }

    /** 最近一次成功重建的回合摘要（无正文/物理路径）。 */
    public synchronized List<TurnRecordSummary> summaries() {
        return summaries;
    }

    /** 最近一次成功重建的扁平回合记录摘要（无正文/物理路径）。 */
    public synchronized List<RecordSummary> recordSummaries() {
        return recordSummaries;
    }

    /** 最近一次成功重建的独立开场记录摘要（无正文/物理路径）。 */
    public synchronized List<RecordSummary> openingRecordSummaries() {
        return openingRecordSummaries;
    }

    /** 是否至少完成过一次全量投影；false 时查询只能退回 FS-only 已验证扫描。 */
    public synchronized boolean hasSuccessfulProjection() {
        return projectionPublished;
    }

    /** 最近一次成功投影的 turn 文件 hash 清单（含 index.md）；用于查询一致性校验。 */
    public synchronized Map<String, String> manifestForTurn(String turnId) {
        Map<String, String> manifest = manifestsByTurn.get(turnId);
        return manifest == null ? Map.of() : manifest;
    }

    /** 最近一次成功投影的 opening 文件 hash 清单（含 index.md）。 */
    public synchronized Map<String, String> manifestForOpening(String publicationId) {
        Map<String, String> manifest = manifestsByOpening.get(publicationId);
        return manifest == null ? Map.of() : manifest;
    }

    /** 投影根目录仅用于同包查询服务与诊断；不得暴露给 MCP。 */
    Path turnsRoot() {
        return ensureTurnsRoot();
    }

    /** 开场投影根目录仅用于同包查询服务与诊断；不得暴露给 MCP。 */
    Path openingsRoot() {
        return ensureOpeningsRoot();
    }

    private RenderedTurn renderTurn(TurnStateSnapshot turn) {
        String turnId = safeLogicalId(turn.turnId(), "turnId");
        List<ActionBatch> batches = packageService.batches().stream()
                .filter(batch -> turnId.equals(batch.turnId()))
                .sorted(Comparator.comparing(ActionBatch::createdAt)
                        .thenComparing(ActionBatch::batchId))
                .toList();
        List<PublicationRecord> publicationRecords = publications.records().stream()
                .filter(record -> record.kind() == PublicationKind.BATCH)
                .filter(record -> turnId.equals(record.turnId()))
                .sorted(Comparator.comparing(PublicationRecord::createdAt)
                        .thenComparing(PublicationRecord::publicationId))
                .toList();

        LinkedHashSet<String> packageIds = new LinkedHashSet<>();
        LinkedHashSet<String> postIds = new LinkedHashSet<>();
        LinkedHashSet<String> publicationIds = new LinkedHashSet<>();
        LinkedHashSet<String> batchIds = new LinkedHashSet<>();
        for (ActionBatch batch : batches) {
            batchIds.add(safeLogicalId(batch.batchId(), "batchId"));
            packageIds.add(safeLogicalId(batch.packageId(), "packageId"));
        }
        for (PublicationRecord record : publicationRecords) {
            publicationIds.add(safeLogicalId(record.publicationId(), "publicationId"));
            if (record.publicPostId() != null) {
                String publicPostId = safeLogicalId(record.publicPostId(), "postId");
                Post publicPost = postService.post(publicPostId).orElseThrow(() ->
                        new RecordProjectionException(
                                RecordProjectionException.PROJECTION_FAILED,
                                "发布记录引用的公共推文不存在"));
                if (publicPost.visibility() != PostVisibility.PUBLIC
                        || !publicPost.body().equals(record.publicNarrative())) {
                    throw new RecordProjectionException(
                            RecordProjectionException.PROJECTION_FAILED,
                            "公共叙事与公共推文可见性/正文不一致");
                }
                postIds.add(publicPostId);
            }
            for (Map.Entry<String, String> privateEntry : record.privatePostIds().entrySet()) {
                String playerId = safeLogicalId(privateEntry.getKey(), "playerId");
                String privatePostId = safeLogicalId(privateEntry.getValue(), "postId");
                Post privatePost = postService.post(privatePostId).orElseThrow(() ->
                        new RecordProjectionException(
                                RecordProjectionException.PROJECTION_FAILED,
                                "发布记录引用的私密推文不存在"));
                String privateOutcome = record.privateOutcomes().get(playerId);
                if (privatePost.visibility() != PostVisibility.PRIVATE
                        || !privatePost.authorPlayerId().equals(playerId)
                        || privateOutcome == null
                        || !privatePost.body().equals(privateOutcome)) {
                    throw new RecordProjectionException(
                            RecordProjectionException.PROJECTION_FAILED,
                            "私密 outcome 与私密推文可见性/作者/正文不一致");
                }
                postIds.add(privatePostId);
            }
        }

        Map<String, String> files = new LinkedHashMap<>();
        List<RecordSummary> records = new ArrayList<>();
        files.put("public-narrative.md",
                renderPublicNarrative(turn, publicationRecords));

        Map<String, List<String>> privateByPlayer = new LinkedHashMap<>();
        for (PublicationRecord record : publicationRecords) {
            for (Map.Entry<String, String> entry : record.privateOutcomes().entrySet()) {
                String playerId = safeLogicalId(entry.getKey(), "playerId");
                privateByPlayer.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                        .add("## 批次 " + record.batchId() + " / publication "
                                + record.publicationId() + "\n\n" + entry.getValue());
            }
        }
        for (Map.Entry<String, List<String>> entry : privateByPlayer.entrySet()) {
            String relative = "players/" + entry.getKey() + ".md";
            files.put(relative, renderPlayerFile(turnId, entry.getKey(), entry.getValue()));
        }

        for (ActionBatch batch : batches) {
            if (batch.actionIds().size() != batch.texts().size()) {
                throw new RecordProjectionException(RecordProjectionException.PROJECTION_FAILED,
                        "批次 actionIds/texts 数量不一致");
            }
            for (int i = 0; i < batch.actionIds().size(); i++) {
                String actionId = safeLogicalId(batch.actionIds().get(i), "actionId");
                String playerId = safeLogicalId(batch.playerIds().get(i), "playerId");
                String relative = "actions/" + actionId + ".md";
                files.put(relative, renderActionFile(batch, actionId, playerId,
                        batch.texts().get(i)));
                records.add(new RecordSummary("action", turnId, playerId, actionId,
                        batch.packageId(), batch.batchId(), null, relative,
                        hash(files.get(relative)), "action projection"));
            }
        }

        for (String packageId : packageIds) {
            Optional<PackageView> full = packageService.get(packageId, true);
            String relative = "packages/" + packageId + ".md";
            files.put(relative, renderPackageFile(turnId, packageId, full));
            String batchId = batches.stream()
                    .filter(batch -> packageId.equals(batch.packageId()))
                    .map(ActionBatch::batchId)
                    .findFirst().orElse(null);
            records.add(new RecordSummary("package", turnId, null, null,
                    packageId, batchId, null, relative, hash(files.get(relative)),
                    full.isPresent() ? "package state=" + full.get().state() : "package unavailable"));
        }

        for (ActionBatch batch : batches) {
            String batchId = safeLogicalId(batch.batchId(), "batchId");
            String relative = "batches/" + batchId + ".md";
            files.put(relative, renderBatchFile(turnId, batch));
            records.add(new RecordSummary("batch", turnId, null, null,
                    batch.packageId(), batchId, null, relative, hash(files.get(relative)),
                    "actionCount=" + batch.actionIds().size() + ",status=" + batch.status()));
        }

        for (String postId : postIds) {
            Post post = postService.post(postId).orElseThrow(() ->
                    new RecordProjectionException(RecordProjectionException.PROJECTION_FAILED,
                            "发布记录引用的推文不存在: " + postId));
            String visibility = post.visibility().name();
            String relative = "posts/" + postId + "-" + visibility + ".md";
            files.put(relative, renderPostFile(turnId, post));
            records.add(new RecordSummary("post", turnId, post.authorPlayerId(), null,
                    null, batchIdForPost(publicationRecords, postId), postId, relative,
                    hash(files.get(relative)), "visibility=" + post.visibility()
                    + ",status=" + post.status()));
        }

        for (PublicationRecord record : publicationRecords) {
            if (record.publicPostId() != null) {
                records.add(new RecordSummary("public-narrative", turnId, null, null,
                        null, record.batchId(), record.publicPostId(), "public-narrative.md",
                        hash(files.get("public-narrative.md")), "publicNarrativeHash="
                        + record.publicNarrativeHash()));
            }
        }
        for (Map.Entry<String, List<String>> entry : privateByPlayer.entrySet()) {
            String relative = "players/" + entry.getKey() + ".md";
            records.add(new RecordSummary("player", turnId, entry.getKey(), null,
                    null, batchIdForPlayer(publicationRecords, entry.getKey()), null, relative,
                    hash(files.get(relative)), "privateOutcomeCount=" + entry.getValue().size()));
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        files.forEach((file, content) -> hashes.put(file, hash(content)));
        String index = renderIndex(turn, batchIds, packageIds, postIds, publicationIds, hashes);
        files.put("index.md", index);
        records.add(new RecordSummary("turn", turnId, null, null, null, null, null,
                "index.md", hash(index), "turnNo=" + turn.turnNo() + ",status=" + turn.state()));

        TurnRecordSummary summary = new TurnRecordSummary(turn.turnNo(), turnId,
                turn.state().name(), turn.openedWorldVersion(), turn.opensAt(), turn.closesAt(),
                List.copyOf(batchIds), List.copyOf(packageIds), List.copyOf(postIds),
                List.copyOf(publicationIds));
        return new RenderedTurn(summary, files, records);
    }

    private RenderedOpening renderOpening(PublicationRecord record) {
        if (record.kind() != PublicationKind.OPENING) {
            throw new RecordProjectionException(RecordProjectionException.INVALID_ARGUMENTS,
                    "只允许投影 OPENING 发布记录到 records/openings");
        }
        String publicationId = safeLogicalId(record.publicationId(), "publicationId");
        String publicPostId = safeLogicalId(record.publicPostId(), "postId");
        Post publicPost = postService.post(publicPostId).orElseThrow(() ->
                new RecordProjectionException(RecordProjectionException.PROJECTION_FAILED,
                        "开场发布记录引用的公共推文不存在"));
        if (publicPost.visibility() != PostVisibility.PUBLIC
                || !PostService.SYSTEM_PUBLICATION_AUTHOR.equals(publicPost.authorPlayerId())
                || !publicPost.body().equals(record.publicNarrative())) {
            throw new RecordProjectionException(RecordProjectionException.PROJECTION_FAILED,
                    "开场公共叙事与公共推文作者/可见性/正文不一致");
        }

        Map<String, String> files = new LinkedHashMap<>();
        files.put("public-narrative.md", renderOpeningPublicNarrative(record));
        String postFile = "posts/" + publicPostId + "-PUBLIC.md";
        files.put(postFile, renderOpeningPostFile(publicationId, publicPost));

        Map<String, String> hashes = new LinkedHashMap<>();
        files.forEach((file, content) -> hashes.put(file, hash(content)));
        String index = renderOpeningIndex(record, publicPost, hashes);
        files.put("index.md", index);
        RecordSummary summary = new RecordSummary(
                "opening", null, null, null, null, null, publicPostId,
                "index.md", hash(index),
                "publicationContentHash=" + record.contentHash()
                        + ",publicNarrativeHash=" + record.publicNarrativeHash()
                        + ",status=" + publicPost.status(),
                publicationId);
        return new RenderedOpening(files, summary);
    }

    private static String renderOpeningPublicNarrative(PublicationRecord record) {
        return "# 开场推文 公共叙事\n\n"
                + "- publicationId: `" + record.publicationId() + "`\n"
                + "- publicPostId: `" + record.publicPostId() + "`\n\n"
                + "## 正文\n\n" + record.publicNarrative() + "\n";
    }

    private static String renderOpeningPostFile(String publicationId, Post post) {
        return "# 推文 " + post.postId() + "\n\n"
                + "- kind: `OPENING`\n"
                + "- publicationId: `" + publicationId + "`\n"
                + "- postId: `" + post.postId() + "`\n"
                + "- authorPlayerId: `" + post.authorPlayerId() + "`\n"
                + "- visibility: `" + post.visibility() + "`\n"
                + "- status: `" + post.status() + "`\n"
                + "- revision: " + post.revision() + "\n"
                + "- createdAt: " + post.createdAt() + "\n"
                + "- updatedAt: " + post.updatedAt() + "\n"
                + "- approvedBy: " + post.approvedBy() + "\n"
                + "- publishedBy: " + post.publishedBy() + "\n"
                + "- deliveryState: " + post.deliveryState() + "\n\n"
                + "## 标题\n\n" + post.title() + "\n\n"
                + "## 正文\n\n" + post.body() + "\n";
    }

    private static String renderOpeningIndex(PublicationRecord record, Post post,
                                             Map<String, String> hashes) {
        StringBuilder out = new StringBuilder();
        out.append("# 开场推文记录索引\n\n")
                .append("- kind: OPENING\n")
                .append("- publicationId: `").append(record.publicationId()).append("`\n")
                .append("- publicPostId: `").append(record.publicPostId()).append("`\n")
                .append("- createdAt: ").append(record.createdAt()).append('\n')
                .append("- contentHash: ").append(record.contentHash()).append('\n')
                .append("- publicNarrativeHash: ")
                .append(record.publicNarrativeHash()).append('\n')
                .append("- status: ").append(post.status()).append('\n')
                .append("- visibility: ").append(post.visibility()).append("\n\n")
                .append("## Content hashes\n\n");
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            out.append("- `").append(entry.getKey()).append("`: `")
                    .append(entry.getValue()).append("`\n");
        }
        return out.toString();
    }

    private static String renderPublicNarrative(TurnStateSnapshot turn,
                                                List<PublicationRecord> records) {
        StringBuilder out = new StringBuilder();
        out.append("# 回合 ").append(turn.turnNo()).append(" 公共叙事\n\n");
        boolean any = false;
        for (PublicationRecord record : records) {
            if (record.publicPostId() == null || record.publicNarrative().isBlank()) {
                continue;
            }
            any = true;
            out.append("## 批次 ").append(record.batchId())
                    .append(" / publication ").append(record.publicationId()).append("\n\n")
                    .append(record.publicNarrative()).append("\n\n");
        }
        if (!any) {
            out.append("_（本回合无公共叙事）_\n");
        }
        return out.toString();
    }

    private static String renderPlayerFile(String turnId, String playerId, List<String> sections) {
        StringBuilder out = new StringBuilder();
        out.append("# 玩家 ").append(playerId).append(" 私密结果\n\n")
                .append("- turnId: `").append(turnId).append("`\n")
                .append("- playerId: `").append(playerId).append("`\n\n");
        for (String section : sections) {
            out.append(section).append("\n\n");
        }
        return out.toString();
    }

    private static String renderActionFile(ActionBatch batch, String actionId,
                                           String playerId, String text) {
        return "# 行动 " + actionId + "\n\n"
                + "- actionId: `" + actionId + "`\n"
                + "- turnId: `" + (batch.turnId() == null ? "" : batch.turnId()) + "`\n"
                + "- batchId: `" + batch.batchId() + "`\n"
                + "- packageId: `" + batch.packageId() + "`\n"
                + "- playerId: `" + playerId + "`\n"
                + "- batchStatus: `" + batch.status() + "`\n\n"
                + "## 行动正文\n\n" + text + "\n";
    }

    private static String renderPackageFile(String turnId, String packageId,
                                            Optional<PackageView> full) {
        StringBuilder out = new StringBuilder();
        out.append("# 裁定包 ").append(packageId).append("\n\n")
                .append("- packageId: `").append(packageId).append("`\n")
                .append("- turnId: `").append(turnId).append("`\n");
        if (full.isEmpty()) {
            out.append("- projectionNote: package detail unavailable\n");
            return out.toString();
        }
        PackageView pkg = full.orElseThrow();
        out.append("- title: ").append(pkg.title()).append('\n')
                .append("- proposerKey: `").append(pkg.proposerKey()).append("`\n")
                .append("- state: `").append(pkg.state()).append("`\n")
                .append("- createdAtEpochMs: ").append(pkg.createdAtEpochMs()).append('\n')
                .append("- deadlineEpochMs: ").append(pkg.deadlineEpochMs()).append('\n')
                .append("- itemCount: ").append(pkg.items().size()).append("\n\n");
        if (pkg.decision() != null) {
            out.append("- approvedIndexes: ").append(pkg.decision().approvedIndexes()).append('\n');
        }
        for (PackageItemView item : pkg.items()) {
            out.append("\n## Item ").append(item.index()).append("\n\n")
                    .append("- toolName: ").append(item.toolName()).append('\n')
                    .append("- argsDigest: `").append(item.argsDigest()).append("`\n")
                    .append("- summary: ").append(item.summary()).append('\n');
            if (item.args() != null) {
                out.append("- args:\n");
                item.args().forEach((key, value) ->
                        out.append("  - ").append(key).append(": ").append(value).append('\n'));
            }
        }
        return out.toString();
    }

    private static String renderBatchFile(String turnId, ActionBatch batch) {
        StringBuilder out = new StringBuilder();
        out.append("# 行动批次 ").append(batch.batchId()).append("\n\n")
                .append("- batchId: `").append(batch.batchId()).append("`\n")
                .append("- packageId: `").append(batch.packageId()).append("`\n")
                .append("- turnId: `").append(turnId).append("`\n")
                .append("- status: `").append(batch.status()).append("`\n")
                .append("- createdAt: ").append(batch.createdAt()).append('\n')
                .append("- playerIds: ").append(batch.playerIds()).append('\n')
                .append("- actionIds: ").append(batch.actionIds()).append("\n\n");
        if (batch.result() != null && !batch.result().isBlank()) {
            out.append("## 批次结果\n\n").append(batch.result()).append('\n');
        }
        return out.toString();
    }

    private static String renderPostFile(String turnId, Post post) {
        return "# 推文 " + post.postId() + "\n\n"
                + "- postId: `" + post.postId() + "`\n"
                + "- turnId: `" + turnId + "`\n"
                + "- authorPlayerId: `" + post.authorPlayerId() + "`\n"
                + "- visibility: `" + post.visibility() + "`\n"
                + "- status: `" + post.status() + "`\n"
                + "- revision: " + post.revision() + "\n"
                + "- createdAt: " + post.createdAt() + "\n"
                + "- updatedAt: " + post.updatedAt() + "\n"
                + "- approvedBy: " + post.approvedBy() + "\n"
                + "- publishedBy: " + post.publishedBy() + "\n"
                + "- deliveryState: " + post.deliveryState() + "\n\n"
                + "## 标题\n\n" + post.title() + "\n\n"
                + "## 正文\n\n" + post.body() + "\n";
    }

    private static String renderIndex(TurnStateSnapshot turn, Set<String> batchIds,
                                      Set<String> packageIds, Set<String> postIds,
                                      Set<String> publicationIds, Map<String, String> hashes) {
        StringBuilder out = new StringBuilder();
        out.append("# 回合 ").append(turn.turnNo()).append(" 记录索引\n\n")
                .append("- turnId: `").append(turn.turnId()).append("`\n")
                .append("- turnNo: ").append(turn.turnNo()).append('\n')
                .append("- worldVersion: ").append(turn.openedWorldVersion()).append('\n')
                .append("- status: ").append(turn.state()).append('\n')
                .append("- revision: ").append(turn.revision()).append('\n')
                .append("- opensAt: ").append(turn.opensAt()).append('\n')
                .append("- closesAt: ").append(turn.closesAt()).append('\n')
                .append("- batchIds: ").append(batchIds).append('\n')
                .append("- packageIds: ").append(packageIds).append('\n')
                .append("- postIds: ").append(postIds).append('\n')
                .append("- publicationIds: ").append(publicationIds).append("\n\n")
                .append("## Content hashes\n\n");
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            out.append("- `").append(entry.getKey()).append("`: `")
                    .append(entry.getValue()).append("`\n");
        }
        return out.toString();
    }

    private static void writeRendered(Path stagingDir, Map<String, String> files) {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if ("index.md".equals(entry.getKey())) {
                continue;
            }
            writeAtomic(stagingDir.resolve(relativePath(entry.getKey())), entry.getValue());
        }
        writeAtomic(stagingDir.resolve("index.md"), files.get("index.md"));
    }

    private static void writeTurnAtomically(Path turnsRoot, Path target,
                                            RenderedTurn rendered) {
        writeRecordAtomically(turnsRoot, target, rendered.files(), MANAGED_DIRECTORIES);
    }

    /**
     * 同父目录 staging 写全后再替换 target。替换采用“旧目录先备份、新目录 rename、
     * 失败恢复旧目录”的 fail-closed 流程；不会把半写文件直接暴露在 target。
     */
    private static void writeRecordAtomically(Path root, Path target,
                                              Map<String, String> files,
                                              List<String> managedDirectories) {
        Path staging = null;
        try {
            // rebuildAll 取到 root 后，路径仍可能被替换成软链；写入前再次 fail-closed。
            requireRegularRoot(root);
            staging = Files.createTempDirectory(root, ".record-staging-");
            rejectSymbolic(staging, "staging");
            if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                        "staging 不是普通目录");
            }
            for (String managed : managedDirectories) {
                Files.createDirectory(staging.resolve(managed));
            }
            writeRendered(staging, files);
            fsyncDirectory(staging);
            requireRegularRoot(root);
            replaceDirectory(root, target, staging, managedDirectories);
            staging = null;
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "原子投影记录目录失败", failure);
        } finally {
            if (staging != null) {
                deleteRecursivelyQuietly(staging);
            }
        }
    }

    private static void requireRegularRoot(Path root) {
        try {
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "records/turns 根路径不得被替换为软链或非目录");
            }
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "检查 records/turns 根路径失败", failure);
        }
    }

    private static void replaceDirectory(Path parent, Path target, Path staging,
                                         List<String> managedDirectories)
            throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "记录 target 不得是软链");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            moveDirectory(staging, target);
            fsyncDirectory(parent);
            return;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "记录 target 不是普通目录");
        }
        validateExistingTargetForSymlinks(target, managedDirectories);
        Path backup = uniqueSibling(parent, ".record-backup-");
        boolean oldMoved = false;
        boolean newMoved = false;
        try {
            moveDirectory(target, backup);
            oldMoved = true;
            moveDirectory(staging, target);
            newMoved = true;
            fsyncDirectory(parent);
        } catch (IOException | RuntimeException failure) {
            if (oldMoved && !newMoved) {
                try {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        deleteRecursivelyQuietly(target);
                    }
                    moveDirectory(backup, target);
                    fsyncDirectory(parent);
                } catch (IOException | RuntimeException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                    throw new RecordProjectionException(
                            RecordProjectionException.IO_FAILED,
                            "新记录替换失败且旧 target 恢复失败", failure);
                }
            }
            throw failure;
        }
        if (newMoved && oldMoved) {
            // 新目录已安全上线；旧备份清理是 best-effort，不影响可见投影。
            deleteRecursivelyQuietly(backup);
        }
    }

    private static void validateExistingTargetForSymlinks(Path target,
                                                          List<String> managedDirectories) {
        try {
            for (String managed : managedDirectories) {
                Path dir = target.resolve(managed);
                if (Files.isSymbolicLink(dir)) {
                    throw new RecordProjectionException(
                            RecordProjectionException.UNSAFE_PATH,
                            "已有记录子目录不得是软链: " + managed);
                }
                if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    try (var stream = Files.list(dir)) {
                        for (Path child : stream.toList()) {
                            if (Files.isSymbolicLink(child)) {
                                throw new RecordProjectionException(
                                        RecordProjectionException.UNSAFE_PATH,
                                        "已有记录文件不得是软链");
                            }
                        }
                    }
                }
            }
            try (var stream = Files.list(target)) {
                for (Path child : stream.toList()) {
                    if (Files.isSymbolicLink(child)) {
                        throw new RecordProjectionException(
                                RecordProjectionException.UNSAFE_PATH,
                                "已有记录路径不得是软链");
                    }
                }
            }
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "检查已有记录软链失败", failure);
        }
    }

    private static Path uniqueSibling(Path parent, String prefix) throws IOException {
        for (int i = 0; i < 100; i++) {
            Path candidate = parent.resolve(prefix + UUID.randomUUID());
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("无法分配唯一记录备份路径");
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 备份/staging 清理失败不覆盖主投影结果。
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // 同上。
        }
    }

    private static String batchIdForPost(List<PublicationRecord> records, String postId) {
        for (PublicationRecord record : records) {
            if (postId.equals(record.publicPostId())
                    || record.privatePostIds().containsValue(postId)) {
                return record.batchId();
            }
        }
        return null;
    }

    private static String batchIdForPlayer(List<PublicationRecord> records, String playerId) {
        for (PublicationRecord record : records) {
            if (record.privateOutcomes().containsKey(playerId)) {
                return record.batchId();
            }
        }
        return null;
    }

    private Path ensureTurnsRoot() {
        return ensureRecordsRoot("turns");
    }

    private Path ensureOpeningsRoot() {
        return ensureRecordsRoot("openings");
    }

    private Path ensureRecordsRoot(String child) {
        String label = "records/" + child;
        try {
            Path records = dataDir.resolve("records");
            Files.createDirectories(records);
            rejectSymbolic(records, "records");
            if (!Files.isDirectory(records, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "records 不是普通目录");
            }
            Path root = records.resolve(child);
            Files.createDirectories(root);
            rejectSymbolic(root, label);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        label + " 不是普通目录");
            }
            return root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "创建 " + label + " 根目录失败", failure);
        }
    }

    private static void writeAtomic(Path target, String content) {
        Path parent = target.getParent();
        if (parent == null) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "目标文件缺少父目录");
        }
        Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymbolic(target, target.getFileName().toString());
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                            "目标记录路径不是普通文件");
                }
            }
            temporary = Files.createTempFile(parent, ".record-", ".tmp");
            harden(temporary);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            harden(target);
            fsyncDirectory(parent);
        } catch (RecordProjectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RecordProjectionException(RecordProjectionException.IO_FAILED,
                    "原子写入回合记录失败 file=" + target.getFileName(), failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 保留原始失败；临时文件不会替换正式文件。
                }
            }
        }
    }

    static Path relativePath(String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/")
                || relative.contains("\\") || relative.contains("..")
                || relative.chars().anyMatch(Character::isISOControl)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "非法记录相对路径");
        }
        Path path = Path.of(relative).normalize();
        if (path.isAbsolute() || path.getNameCount() == 0 || path.getNameCount() > 2) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "记录相对路径层级非法");
        }
        for (Path segment : path) {
            String name = segment.toString();
            if (name.isBlank() || ".".equals(name) || "..".equals(name)
                    || name.contains("/") || name.contains("\\")) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        "记录相对路径包含非法段");
            }
        }
        return path;
    }

    static String safeLogicalId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new RecordProjectionException(RecordProjectionException.INVALID_ARGUMENTS,
                    field + " 不能为空");
        }
        if (value.length() > 200 || ".".equals(value) || "..".equals(value)
                || value.contains("/") || value.contains("\\")) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    field + " 含不安全路径字符");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                        field + " 含控制或空白字符");
            }
        }
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@-]*")) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    field + " 含未允许字符");
        }
        return value;
    }

    private static String turnDirName(TurnStateSnapshot turn) {
        String turnId = safeLogicalId(turn.turnId(), "turnId");
        return turn.turnNo() + "-" + turnId;
    }

    private static void rejectSymbolic(Path path, String label) {
        if (Files.isSymbolicLink(path)) {
            throw new RecordProjectionException(RecordProjectionException.UNSAFE_PATH,
                    "记录路径不得是软链: " + label);
        }
    }

    private static void harden(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统无法表达 600；原子写与目录边界仍是主安全保证。
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // best-effort；文件内容已 fsync 且已经原子 move。
        }
    }

    private static String hash(String content) {
        return Hashes.sha256(content);
    }

    private record RenderedTurn(TurnRecordSummary summary,
                                Map<String, String> files,
                                List<RecordSummary> records) {
        private RenderedTurn {
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
            records = List.copyOf(records);
        }
    }

    private record RenderedOpening(Map<String, String> files,
                                   RecordSummary summary) {
        private RenderedOpening {
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }
}
