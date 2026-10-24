package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionBatchExecutor;
import io.mosire.iibot.taskflow.decision.ActionPackageService;
import io.mosire.iibot.taskflow.decision.PackageItemView;
import io.mosire.iibot.taskflow.decision.PackageView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** GM 裁定包九个 supervisor MCP 工具。 */
public final class GmDecisionPackageMcpTools {

    public static final String CREATE = "tf_package_create";
    public static final String LIST = "tf_package_list";
    public static final String GET = "tf_package_get";
    public static final String APPEND = "tf_package_append";
    public static final String REPLACE_ITEM = "tf_package_replace_item";
    public static final String REMOVE_ITEM = "tf_package_remove_item";
    public static final String DECIDE = "tf_package_decide";
    public static final String DECIDE_ITEM = "tf_package_decide_item";
    public static final String COMMIT = "tf_package_commit";

    public static final List<String> NAMES = List.of(CREATE, LIST, GET, APPEND,
            REPLACE_ITEM, REMOVE_ITEM, DECIDE, DECIDE_ITEM, COMMIT);

    private static final int MAX_RESULT_SUMMARY_CHARS = 600;

    private GmDecisionPackageMcpTools() {
    }

    /** 九个工具，顺序稳定；executor 可为 null，此时 commit 返回稳定不可用错误。 */
    public static List<AgentTool> all(ActionPackageService packageService,
                                      ActionBatchExecutor executor,
                                      Actor supervisor) {
        Objects.requireNonNull(packageService, "packageService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                create(packageService, supervisor),
                list(packageService),
                get(packageService),
                append(packageService, supervisor),
                replaceItem(packageService, supervisor),
                removeItem(packageService, supervisor),
                decide(packageService, supervisor),
                decideItem(packageService, supervisor),
                commit(packageService, executor, supervisor));
    }

    public static AgentTool create(ActionPackageService packageService, Actor supervisor) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "用选中的 QUEUED 行动建一个 PROPOSED 裁定包。",
                "properties", Map.of(
                        "title", Map.of("type", "string", "minLength", 1,
                                "description", "包标题"),
                        "actionIds", Map.of("type", "array", "minItems", 1,
                                "items", Map.of("type", "string", "minLength", 1),
                                "description", "选中的行动 ID；同一玩家可多条"),
                        "deadlineSeconds", Map.of("type", "integer", "minimum", 1,
                                "description", "裁决截止秒数"),
                        "idempotencyKey", Map.of("type", "string", "minLength", 1,
                                "description", "建包幂等键")),
                "required", List.of("title", "actionIds", "deadlineSeconds",
                        "idempotencyKey"),
                "additionalProperties", false);
        return GmDecisionToolSupport.writeTool(CREATE,
                "创建裁定包；行动进入 SELECTED，首次逐条裁决前可追加/替换/删除。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String title = GmDecisionToolSupport.requiredText(context.arguments(),
                            "title", GmDecisionToolSupport.MAX_TITLE_CHARS);
                    List<String> actionIds = GmDecisionToolSupport.requiredStringList(
                            context.arguments(), "actionIds");
                    long deadlineSeconds = GmDecisionToolSupport.requiredPositiveLong(
                            context.arguments(), "deadlineSeconds");
                    String key = GmDecisionToolSupport.requiredText(context.arguments(),
                            "idempotencyKey", GmDecisionToolSupport.MAX_KEY_CHARS);
                    PackageView created = packageService.create(supervisor, title, actionIds,
                            deadlineSeconds, key);
                    return GmDecisionToolSupport.success(CREATE, packageView(created, false));
                });
    }

    public static AgentTool list(ActionPackageService packageService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "列出裁定包摘要；默认不含条目 args 正文。",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false);
        return GmDecisionToolSupport.readTool(LIST,
                "列出全部裁定包摘要（状态、条目数、裁决索引），不含行动正文。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    List<PackageView> packages = packageService.list();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", packages.size());
                    data.put("packages", packages.stream()
                            .map(pkg -> packageView(pkg, false)).toList());
                    return GmDecisionToolSupport.success(LIST, data);
                });
    }

    public static AgentTool get(ActionPackageService packageService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "读取裁定包；full=false 默认只返回摘要，full=true 才含条目 args 正文。",
                "properties", Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID"),
                        "full", Map.of("type", "boolean",
                                "description", "是否返回条目 args；默认 false")),
                "required", List.of("packageId"),
                "additionalProperties", false);
        return GmDecisionToolSupport.readTool(GET,
                "读取裁定包。默认摘要；只有显式 full=true 才返回条目明文 args。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    boolean full = GmDecisionToolSupport.optionalBoolean(
                            context.arguments(), "full");
                    PackageView view = packageService.get(packageId, full)
                            .orElseThrow(() -> new io.mosire.iibot.taskflow.decision
                                    .ActionPackageException(
                                    io.mosire.iibot.taskflow.decision.ActionPackageException.NOT_FOUND,
                                    "裁定包不存在: " + packageId));
                    return GmDecisionToolSupport.success(GET, packageView(view, full));
                });
    }

    public static AgentTool append(ActionPackageService packageService, Actor supervisor) {
        Map<String, Object> schema = packageMutationSchema(
                "追加 actionIds；仅 PROPOSED 且首次逐条裁决前。",
                Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID"),
                        "actionIds", Map.of("type", "array", "minItems", 1,
                                "items", Map.of("type", "string", "minLength", 1),
                                "description", "追加的行动 ID")),
                List.of("packageId", "actionIds"));
        return GmDecisionToolSupport.writeTool(APPEND,
                "向 PROPOSED 裁定包追加 QUEUED 行动；首次 itemDecided 后由 AgentLib 冻结。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    List<String> actionIds = GmDecisionToolSupport.requiredStringList(
                            context.arguments(), "actionIds");
                    return GmDecisionToolSupport.success(APPEND, packageView(
                            packageService.append(supervisor, packageId, actionIds), false));
                });
    }

    public static AgentTool replaceItem(ActionPackageService packageService, Actor supervisor) {
        Map<String, Object> schema = packageMutationSchema(
                "替换裁定包里的一个条目；仅 PROPOSED 且首次逐条裁决前。",
                Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID"),
                        "index", Map.of("type", "integer", "minimum", 0,
                                "description", "条目 index"),
                        "actionId", Map.of("type", "string", "minLength", 1,
                                "description", "新的行动 ID")),
                List.of("packageId", "index", "actionId"));
        return GmDecisionToolSupport.writeTool(REPLACE_ITEM,
                "原位替换 PROPOSED 裁定包条目；旧行动回 QUEUED，新行动进入 SELECTED。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    int index = GmDecisionToolSupport.requiredNonNegativeInt(
                            context.arguments(), "index");
                    String actionId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "actionId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    return GmDecisionToolSupport.success(REPLACE_ITEM, packageView(
                            packageService.replaceItem(supervisor, packageId, index, actionId),
                            false));
                });
    }

    public static AgentTool removeItem(ActionPackageService packageService, Actor supervisor) {
        Map<String, Object> schema = packageMutationSchema(
                "删除裁定包里的一个条目；仅 PROPOSED 且首次逐条裁决前。",
                Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID"),
                        "index", Map.of("type", "integer", "minimum", 0,
                                "description", "条目 index")),
                List.of("packageId", "index"));
        return GmDecisionToolSupport.writeTool(REMOVE_ITEM,
                "删除 PROPOSED 裁定包条目；被删行动回 QUEUED。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    int index = GmDecisionToolSupport.requiredNonNegativeInt(
                            context.arguments(), "index");
                    return GmDecisionToolSupport.success(REMOVE_ITEM, packageView(
                            packageService.removeItem(supervisor, packageId, index), false));
                });
    }

    public static AgentTool decide(ActionPackageService packageService, Actor supervisor) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "整包裁决：approvedIndexes 之外的条目视为驳回。",
                "properties", Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID"),
                        "approvedIndexes", Map.of("type", "array",
                                "items", Map.of("type", "integer", "minimum", 0),
                                "description", "批准条目 index；空数组=全驳"),
                        "note", Map.of("type", "string",
                                "description", "可选裁决说明")),
                "required", List.of("packageId", "approvedIndexes"),
                "additionalProperties", false);
        return GmDecisionToolSupport.writeTool(DECIDE,
                "整包裁决 PROPOSED 包；未列出的条目按驳回处理，仅批准子集可 commit。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    Set<Integer> approved = GmDecisionToolSupport.optionalIndexSet(
                            context.arguments(), "approvedIndexes");
                    String note = GmDecisionToolSupport.optionalText(context.arguments(),
                            "note", GmDecisionToolSupport.MAX_NOTE_CHARS);
                    return GmDecisionToolSupport.success(DECIDE, packageView(
                            packageService.decide(supervisor, packageId, approved, note),
                            false));
                });
    }

    public static AgentTool decideItem(ActionPackageService packageService, Actor supervisor) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "逐条裁决一个条目；首次表态后包内容冻结。",
                "properties", Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID"),
                        "index", Map.of("type", "integer", "minimum", 0,
                                "description", "条目 index"),
                        "verdict", Map.of("type", "string",
                                "enum", List.of("approve", "reject"),
                                "description", "approve 或 reject"),
                        "note", Map.of("type", "string",
                                "description", "可选裁决说明")),
                "required", List.of("packageId", "index", "verdict"),
                "additionalProperties", false);
        return GmDecisionToolSupport.writeTool(DECIDE_ITEM,
                "逐条批准/驳回条目；首次表态后 append/replace/remove 由 AgentLib 冻结。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    int index = GmDecisionToolSupport.requiredNonNegativeInt(
                            context.arguments(), "index");
                    boolean approve = GmDecisionToolSupport.parseVerdict(
                            context.arguments()).equals("approve");
                    String note = GmDecisionToolSupport.optionalText(context.arguments(),
                            "note", GmDecisionToolSupport.MAX_NOTE_CHARS);
                    return GmDecisionToolSupport.success(DECIDE_ITEM, packageView(
                            packageService.decideItem(supervisor, packageId, index,
                                    approve, note), false));
                });
    }

    public static AgentTool commit(ActionPackageService packageService,
                                   ActionBatchExecutor executor, Actor supervisor) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "执行裁定包已批子集并形成一个推演批次；重复 commit 返回首次回执。",
                "properties", Map.of(
                        "packageId", Map.of("type", "string", "minLength", 1,
                                "description", "裁定包 ID")),
                "required", List.of("packageId"),
                "additionalProperties", false);
        return GmDecisionToolSupport.writeTool(COMMIT,
                "提交批准子集给 ActionBatchExecutor；未批行动回 QUEUED，重复提交不重复执行/LLM。",
                schema, GmDecisionToolSupport.DECISION_PACKAGE_NAMESPACE, context -> {
                    if (executor == null) {
                        return GmDecisionToolSupport.failure(COMMIT,
                                "BATCH_EXECUTOR_UNAVAILABLE",
                                "未装配行动批次执行器，不能 commit", Map.of());
                    }
                    String packageId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "packageId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    CommitReceipt receipt = packageService.commit(supervisor, packageId, executor);
                    ActionBatch batch = findBatch(packageService, packageId, receipt);
                    Map<String, Object> receiptRow = new LinkedHashMap<>();
                    receiptRow.put("status", receipt.status().name());
                    receiptRow.put("revisionRef", receipt.revisionRef());
                    receiptRow.put("reason", receipt.reason());
                    receiptRow.put("outcomes", receipt.outcomes().stream()
                            .map(Enum::name).toList());

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("receipt", receiptRow);
                    data.put("receiptStatus", receipt.status().name());
                    data.put("revisionRef", receipt.revisionRef());
                    data.put("reason", receipt.reason());
                    data.put("outcomes", receiptRow.get("outcomes"));
                    if (batch != null) {
                        String resultSummary = summarize(batch.result());
                        data.put("batchId", batch.batchId());
                        data.put("batchStatus", batch.status().name());
                        data.put("actionCount", batch.actionIds().size());
                        data.put("result", resultSummary);
                        data.put("resultSummary", resultSummary);
                    } else {
                        data.put("batchId", null);
                        data.put("batchStatus", null);
                        data.put("actionCount", 0);
                        data.put("result", "");
                        data.put("resultSummary", "");
                    }
                    return receipt.status() == CommitReceipt.Status.COMMITTED
                            ? GmDecisionToolSupport.success(COMMIT, data)
                            : GmDecisionToolSupport.failure(COMMIT,
                                    receipt.status().name(), receipt.reason(), data);
                });
    }

    private static Map<String, Object> packageMutationSchema(String description,
                                                             Map<String, Object> properties,
                                                             List<String> required) {
        return Map.of(
                "type", "object",
                "description", description,
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    private static ActionBatch findBatch(ActionPackageService packageService,
                                         String packageId, CommitReceipt receipt) {
        String batchId = receipt.revisionRef();
        List<ActionBatch> batches = packageService.batches();
        for (int i = batches.size() - 1; i >= 0; i--) {
            ActionBatch batch = batches.get(i);
            if (batchId != null ? batch.batchId().equals(batchId)
                    : batch.packageId().equals(packageId)) {
                return batch;
            }
        }
        return null;
    }

    private static Map<String, Object> packageView(PackageView view, boolean full) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("packageId", view.packageId());
        row.put("title", view.title());
        row.put("proposerKey", view.proposerKey());
        row.put("state", view.state().name());
        row.put("createdAtEpochMs", view.createdAtEpochMs());
        row.put("deadlineEpochMs", view.deadlineEpochMs());
        row.put("itemCount", view.items().size());
        row.put("items", view.items().stream()
                .map(item -> itemView(item, full)).toList());
        Decision decision = view.decision();
        if (decision == null) {
            row.put("decision", null);
        } else {
            List<Integer> approved = new ArrayList<>(decision.approvedIndexes());
            approved.sort(Integer::compareTo);
            Map<String, Object> decisionRow = new LinkedHashMap<>();
            decisionRow.put("approvedIndexes", approved);
            decisionRow.put("by", decision.by());
            decisionRow.put("note", decision.note());
            row.put("decision", decisionRow);
        }
        return row;
    }

    private static Map<String, Object> itemView(PackageItemView item, boolean full) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", item.index());
        row.put("toolName", item.toolName());
        row.put("argsDigest", item.argsDigest());
        row.put("summary", item.summary());
        if (full) {
            row.put("args", item.args());
        }
        return row;
    }

    private static String summarize(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        String trimmed = result.trim();
        if (trimmed.length() <= MAX_RESULT_SUMMARY_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_RESULT_SUMMARY_CHARS) + "…[摘要截断]";
    }
}
