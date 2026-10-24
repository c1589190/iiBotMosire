package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.decision.ActionBatch;
import io.mosire.iibot.taskflow.decision.ActionPackageService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GM 行动批次两个 supervisor MCP 工具；列表摘要不含 texts/result。 */
public final class GmActionBatchMcpTools {

    public static final String LIST = "tf_batch_list";
    public static final String GET = "tf_batch_get";

    public static final List<String> NAMES = List.of(LIST, GET);

    private GmActionBatchMcpTools() {
    }

    /** 两个工具，顺序稳定。 */
    public static List<AgentTool> all(ActionPackageService packageService) {
        Objects.requireNonNull(packageService, "packageService");
        return List.of(list(packageService), get(packageService));
    }

    public static AgentTool list(ActionPackageService packageService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "列出行动批次摘要；不返回行动 texts/result。",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false);
        return GmDecisionToolSupport.readTool(LIST,
                "列出 batchId/packageId/actionCount/status/createdAt；摘要不含正文与结果。",
                schema, GmDecisionToolSupport.ACTION_BATCH_NAMESPACE, context -> {
                    List<ActionBatch> batches = packageService.batches();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", batches.size());
                    data.put("batches", batches.stream()
                            .map(GmActionBatchMcpTools::summary).toList());
                    return GmDecisionToolSupport.success(LIST, data);
                });
    }

    public static AgentTool get(ActionPackageService packageService) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "description", "GM 按 batchId 读取批次全文（含行动 texts 与 result）。",
                "properties", Map.of(
                        "batchId", Map.of("type", "string", "minLength", 1,
                                "description", "行动批次 ID")),
                "required", List.of("batchId"),
                "additionalProperties", false);
        return GmDecisionToolSupport.readTool(GET,
                "读取批次全文：actionIds/texts/playerIds/status/result。",
                schema, GmDecisionToolSupport.ACTION_BATCH_NAMESPACE, context -> {
                    String batchId = GmDecisionToolSupport.requiredText(
                            context.arguments(), "batchId",
                            GmDecisionToolSupport.MAX_ID_CHARS);
                    ActionBatch batch = packageService.batches().stream()
                            .filter(candidate -> candidate.batchId().equals(batchId))
                            .findFirst()
                            .orElseThrow(() -> new io.mosire.iibot.taskflow.decision
                                    .ActionPackageException(
                                    io.mosire.iibot.taskflow.decision.ActionPackageException.NOT_FOUND,
                                    "行动批次不存在: " + batchId));
                    return GmDecisionToolSupport.success(GET, full(batch));
                });
    }

    private static Map<String, Object> summary(ActionBatch batch) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", batch.batchId());
        row.put("packageId", batch.packageId());
        row.put("actionCount", batch.actionIds().size());
        row.put("status", batch.status().name());
        row.put("createdAt", batch.createdAt());
        return row;
    }

    private static Map<String, Object> full(ActionBatch batch) {
        Map<String, Object> row = summary(batch);
        row.put("turnId", batch.turnId());
        row.put("actionIds", batch.actionIds());
        row.put("texts", batch.texts());
        row.put("playerIds", batch.playerIds());
        row.put("result", batch.result());
        return row;
    }
}
