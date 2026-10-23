package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 控制面工具的统一 JSON 结果与稳定错误码映射。 */
final class ControlResults {

    private ControlResults() {
    }

    static ToolResult success(String tool, Map<String, Object> data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("code", "OK");
        output.put("tool", tool);
        output.put("data", data == null ? Map.of() : data);
        return ToolResult.ok(ControlJson.write(output));
    }

    static ToolResult failure(String tool, String code, String message) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("tool", tool);
        output.put("message", message == null ? "" : message);
        output.put("data", Map.of());
        return ToolResult.error(code, ControlJson.write(output));
    }

    static ToolResult fromCommand(String tool, CommandResult result) {
        if (result == null) {
            return failure(tool, "INTERNAL_ERROR", "领域引擎返回了空回执");
        }
        if (result.ok()) {
            return success(tool, commandData(result));
        }
        String externalCode = mapErrorCode(result.code());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", externalCode);
        output.put("tool", tool);
        output.put("message", result.message());
        output.put("domainCode", result.code().name());
        output.put("revision", result.revision());
        output.put("engineRevision", result.engineRevision());
        output.put("eventSeq", result.eventSeq());
        output.put("turnState", result.turnState() == null ? null : result.turnState().name());
        output.put("details", safeDetails(result.details()));
        output.put("data", Map.of());
        return ToolResult.error(externalCode, ControlJson.write(output));
    }

    static ToolResult internalError(String tool, RuntimeException failure) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", "INTERNAL_ERROR");
        output.put("tool", tool);
        output.put("message", "工具执行失败（内部错误）");
        output.put("errorType", failure.getClass().getSimpleName());
        output.put("data", Map.of());
        return ToolResult.error("INTERNAL_ERROR", ControlJson.write(output));
    }

    static String mapErrorCode(ErrorCode code) {
        return switch (code) {
            case OK -> "OK";
            case INVALID_ARGUMENT, PROPOSAL_INVALID, PROPOSAL_HASH_MISMATCH,
                    SCHEMA_UNSUPPORTED, WORLD_DELTA_NOT_ALLOWED, PROPOSAL_TOO_LARGE,
                    PREVIEW_HASH_MISMATCH, UNSUPPORTED_OPERATION -> "INVALID_ARGUMENTS";
            case FORBIDDEN, DRAFT_NOT_OWNED -> "FORBIDDEN";
            case REVISION_CONFLICT, WORLD_VERSION_MISMATCH -> "REVISION_CONFLICT";
            case NOT_FOUND, UNKNOWN_PLAYER, DRAFT_NOT_FOUND, BRIEF_REQUEST_NOT_FOUND -> "NOT_FOUND";
            case PLAYER_ALREADY_REGISTERED, QQ_ALREADY_REGISTERED, BRIEF_ALREADY_ISSUED -> "CONFLICT";
            case TURN_ALREADY_ACTIVE, NO_ACTIVE_TURN, TURN_NOT_OPEN, TURN_NOT_LOCKED,
                    TURN_NOT_ADJUDICATING, TURN_NOT_COMMITTED, TURN_NOT_RETRYABLE,
                    TURN_ALREADY_COMMITTED, DRAFT_EXPIRED, DRAFT_NOT_DRAFT,
                    DECISION_ALREADY_CONFIRMED, LATE_DRAFT, BRIEF_EXPIRED -> "WRONG_STATE";
            case BRIEF_SOURCE_NOT_VISIBLE -> "FORBIDDEN";
        };
    }

    private static Map<String, Object> commandData(CommandResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domainCode", result.code().name());
        data.put("revision", result.revision());
        data.put("engineRevision", result.engineRevision());
        data.put("eventSeq", result.eventSeq());
        data.put("turnState", result.turnState() == null ? null : result.turnState().name());
        data.put("message", result.message());
        data.put("details", safeDetails(result.details()));
        result.referenceId().ifPresent(referenceId -> data.put("referenceId", referenceId));
        return data;
    }

    private static Map<String, String> safeDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (key != null && value != null) {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }
}
