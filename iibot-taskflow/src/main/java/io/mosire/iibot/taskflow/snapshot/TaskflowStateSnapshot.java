package io.mosire.iibot.taskflow.snapshot;

import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.InfoBrief;
import io.mosire.iibot.taskflow.InfoRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TaskflowEngine 的完整纯数据状态快照。
 *
 * <p>该 record 只包含可被 JSON 序列化的 record/enum/string/map/list/set/Instant，
 * 不包含 EventSink、Clock、LLM、QQ 或任何其他运行时对象。所有集合在构造时做防御性
 * 复制；调用方无法通过修改快照集合反向修改引擎。</p>
 *
 * <p>跨字段一致性（turn map key/currentTurnId、worldFacts key/version、parties 与身份
 * 映射、settingProposal 与 worldFact 关系等）由
 * {@code TaskflowEngine.restore(...)} 在恢复时严格校验。</p>
 */
public record TaskflowStateSnapshot(
        int schemaVersion,
        String promptVersion,
        long engineRevision,
        long worldVersion,
        long eventCount,
        long lastEventSeq,
        int lastTurnNo,
        String currentTurnId,
        List<PartySnapshot> parties,
        Map<String, FactSnapshot> worldFacts,
        Map<String, InfoRequest> infoRequests,
        Map<String, InfoBrief> briefsByRequest,
        Map<String, SettingProposalSnapshot> settingProposals,
        Map<String, TurnStateSnapshot> turns,
        Map<String, CommandResult> idempotencyResults) {

    /** 当前快照 schema；schema 不兼容时恢复必须拒绝而不是静默跳过。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** {@link #CURRENT_SCHEMA_VERSION} 的短别名。 */
    public static final int SCHEMA_VERSION = CURRENT_SCHEMA_VERSION;

    public TaskflowStateSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion 必须为正");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion 不能为空");
        }
        if (engineRevision < 0) {
            throw new IllegalArgumentException("engineRevision 不能为负");
        }
        if (worldVersion < 0) {
            throw new IllegalArgumentException("worldVersion 不能为负");
        }
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount 不能为负");
        }
        if (lastEventSeq < 0) {
            throw new IllegalArgumentException("lastEventSeq 不能为负");
        }
        if (lastTurnNo < 0) {
            throw new IllegalArgumentException("lastTurnNo 不能为负");
        }
        if (currentTurnId != null && currentTurnId.isBlank()) {
            throw new IllegalArgumentException("currentTurnId 不能为空字符串");
        }
        parties = immutableList(parties, "parties");
        worldFacts = immutableMap(worldFacts, "worldFacts");
        infoRequests = immutableMap(infoRequests, "infoRequests");
        briefsByRequest = immutableMap(briefsByRequest, "briefsByRequest");
        settingProposals = immutableMap(settingProposals, "settingProposals");
        turns = immutableMap(turns, "turns");
        idempotencyResults = immutableMap(idempotencyResults, "idempotencyResults");
    }

    private static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        return List.copyOf(values);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values, String name) {
        Objects.requireNonNull(values, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
