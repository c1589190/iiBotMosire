package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.DecisionDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 秘书工具共用的参数解析、错误映射与安全预览格式。 */
final class SecretaryToolSupport {

    static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String CONFIRM_HINT = "只把“玩家确认码”对应的 /确认 命令给玩家看一次；不要展示内部ID，已确认后不得再次索要确认。";

    private SecretaryToolSupport() {
    }

    static String idempotencyKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    static ToolResult invalid(String message) {
        return ToolResult.error(INVALID_ARGUMENTS, message);
    }

    static ToolResult internal() {
        return ToolResult.error(INTERNAL_ERROR, "工具执行失败（内部错误已脱敏）");
    }

    static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return text;
    }

    static String optionalString(Map<String, Object> args, String key, String fallback) {
        if (!args.containsKey(key)) {
            return fallback;
        }
        Object value = args.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " 必须是字符串");
        }
        return text;
    }

    static List<String> stringList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(key + " 不能包含空值");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    static List<String> optionalStringList(Map<String, Object> args, String key,
                                           List<String> fallback) {
        return args.containsKey(key) ? stringList(args, key) : List.copyOf(fallback);
    }

    static Map<String, String> stringMap(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(key + " 必须是字符串对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String mapKey) || mapKey.isBlank()
                    || !(entry.getValue() instanceof String mapValue) || mapValue.isBlank()) {
                throw new IllegalArgumentException(key + " 的键和值必须是非空字符串");
            }
            result.put(mapKey, mapValue);
        }
        return Map.copyOf(result);
    }

    static Map<String, String> optionalStringMap(Map<String, Object> args, String key,
                                                 Map<String, String> fallback) {
        return args.containsKey(key) ? stringMap(args, key) : Map.copyOf(fallback);
    }

    static String preview(DecisionDraft draft) {
        String code = io.mosire.iibot.taskflow.ConfirmationCodes
                .forDecisionDraft(draft.draftId());
        return "决策草稿预览\n"
                + "意图: " + draft.intent() + "\n"
                + "行动: " + String.join("；", draft.actions()) + "\n"
                + "资源: " + draft.resources() + "\n"
                + "目标: " + draft.target() + "\n"
                + "应急方案: " + draft.contingency() + "\n"
                + "玩家确认码: " + code + "\n"
                + "玩家可见指令: /确认 " + code + "\n"
                + "内部ID（不得原样发给玩家）: draftId=" + draft.draftId()
                + " turnId=" + draft.turnId() + " previewHash=" + draft.previewHash() + "\n"
                + CONFIRM_HINT;
    }
}
