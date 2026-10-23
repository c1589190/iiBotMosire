package io.mosire.iibot.taskflow;

import java.util.Set;

/**
 * 推演提案中允许宿主审查的结构化世界变化。
 *
 * <p>GM 只能提交白名单内的变化类型，宿主在原子提交前校验类型和大小。这里不直接
 * 修改 WorldFact，而是把可落盘变化封装成显式结构，避免任意文本变成写操作。</p>
 */
public record WorldDelta(String type, String targetId, String value) {

    /** 默认白名单；引擎构造时可收窄或替换。 */
    public static final Set<String> DEFAULT_ALLOWED_TYPES = Set.of(
            "SET_FLAG",
            "ADD_RESOURCE",
            "MOVE_ENTITY",
            "ADD_FACT",
            "REMOVE_FACT",
            "SET_WORLD_FACT");

    public WorldDelta {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("worldDelta.type 不能为空");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("worldDelta.targetId 不能为空");
        }
        value = value == null ? "" : value;
    }

    public String canonicalText() {
        return type + ":" + targetId + ":" + value;
    }
}
