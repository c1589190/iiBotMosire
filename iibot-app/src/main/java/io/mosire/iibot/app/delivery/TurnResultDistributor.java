package io.mosire.iibot.app.delivery;

import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已发布回合的结果分发器。
 *
 * <p>当回合进入 {@link TurnState#PUBLISHED} 且存在 committed proposal 时，把
 * {@code publicNarrative} 发到公共群，把每个 {@code playerOutcomes} 发到对应玩家私聊。
 * 公开消息与每个玩家消息使用稳定幂等键，进程内同一回合只分发一次。发送失败/UNKNOWN 只记录
 * 并标记已尝试；UNKNOWN 不自动重发。</p>
 *
 * <p>这是宿主主动消息，不属于入站回复；taskflow/agent 仍不直接调用 MessagingAdapter。</p>
 */
public final class TurnResultDistributor {

    private static final Logger LOG = LoggerFactory.getLogger(TurnResultDistributor.class);

    private final TaskflowEngine engine;
    private final OutboxSender outbox;
    private final String publicGroupId;
    private final Map<String, String> playerQqById;
    private final Set<String> attemptedTurnIds = ConcurrentHashMap.newKeySet();

    public TurnResultDistributor(TaskflowEngine engine, OutboxSender outbox, String publicGroupId,
                                 Map<String, String> playerQqById) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        if (publicGroupId == null || publicGroupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        this.publicGroupId = publicGroupId;
        this.playerQqById = Map.copyOf(Objects.requireNonNull(playerQqById, "playerQqById"));
    }

    /**
     * 若当前回合是已发布且尚未尝试分发，则发送公开叙事与每个玩家私密结果。
     *
     * <p>方法加锁保证多个用户 mailbox 同时观察到 PUBLISHED 时也只分发一次。发送结果是
     * 尽力而为：任何失败都不回滚已提交的回合，也不会自动重发 UNKNOWN。</p>
     */
    public synchronized List<SendOutcome> distributeIfReady() {
        EngineStatus status = engine.status();
        TurnView turn = status.turn();
        if (turn == null || turn.state() != TurnState.PUBLISHED
                || turn.committedProposal() == null) {
            return List.of();
        }
        if (!attemptedTurnIds.add(turn.turnId())) {
            return List.of();
        }

        AdjudicationProposal proposal = turn.committedProposal();
        List<SendOutcome> outcomes = new ArrayList<>();
        String correlation = "turn:" + turn.turnId();

        if (proposal.publicNarrative() != null && !proposal.publicNarrative().isBlank()) {
            outcomes.addAll(outbox.broadcastToPublicGroup(publicGroupId,
                    List.of(proposal.publicNarrative()), correlation + ":public"));
        }

        Map<String, String> sorted = new LinkedHashMap<>();
        proposal.playerOutcomes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String playerId = entry.getKey();
            String qqId = playerQqById.get(playerId);
            if (qqId == null) {
                LOG.warn("回合结果缺少玩家 QQ 映射 turnId={} playerId={}", turn.turnId(), playerId);
                continue;
            }
            outcomes.addAll(outbox.sendPrivateToUser(qqId, List.of(entry.getValue()),
                    correlation + ":player:" + playerId));
        }
        outcomes.stream().filter(SendOutcome::unknown).forEach(outcome ->
                LOG.error("回合结果进入 UNKNOWN，需人工处置 turnId={} key={}",
                        turn.turnId(), outcome.idempotencyKey()));
        LOG.info("回合结果已分发 turnId={} messages={} unknown={}", turn.turnId(),
                outcomes.size(), outcomes.stream().filter(SendOutcome::unknown).count());
        return List.copyOf(outcomes);
    }
}
