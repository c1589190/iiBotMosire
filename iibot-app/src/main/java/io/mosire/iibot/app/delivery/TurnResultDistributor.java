package io.mosire.iibot.app.delivery;

import io.mosire.iibot.taskflow.AdjudicationProposal;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 * 每条消息使用独立的稳定投递 key：公开消息为 {@code turn:<turnId>:public}，玩家消息为
 * {@code turn:<turnId>:player:<playerId>}。发送前必须先通过 {@link DeliveryLedger} 声明，
 * 声明失败则明确抛出，声明已存在则跳过；UNKNOWN 也保留声明，不自动重试。</p>
 *
 * <p>这是宿主主动消息，不属于入站回复；taskflow/agent 仍不直接调用 MessagingAdapter。</p>
 */
public final class TurnResultDistributor {

    private static final Logger LOG = LoggerFactory.getLogger(TurnResultDistributor.class);

    private final TaskflowEngine engine;
    private final OutboxSender outbox;
    private final String publicGroupId;
    private final Map<String, String> playerQqById;
    private final DeliveryLedger ledger;

    /**
     * 兼容构造器：仅使用进程内内存 ledger，适用于测试或无持久化的旧装配。
     * 生产组合根应使用带 {@link DeliveryLedger} 的构造器。
     */
    public TurnResultDistributor(TaskflowEngine engine, OutboxSender outbox, String publicGroupId,
                                 Map<String, String> playerQqById) {
        this(engine, outbox, publicGroupId, playerQqById, new InMemoryDeliveryLedger());
    }

    /** 使用持久化投递声明的构造器。 */
    public TurnResultDistributor(TaskflowEngine engine, OutboxSender outbox, String publicGroupId,
                                 Map<String, String> playerQqById, DeliveryLedger ledger) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        if (publicGroupId == null || publicGroupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        this.publicGroupId = publicGroupId;
        this.playerQqById = Map.copyOf(Objects.requireNonNull(playerQqById, "playerQqById"));
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** 便于按“先声明端口后映射表”的顺序装配的兼容重载。 */
    public TurnResultDistributor(TaskflowEngine engine, OutboxSender outbox, String publicGroupId,
                                 DeliveryLedger ledger, Map<String, String> playerQqById) {
        this(engine, outbox, publicGroupId, playerQqById, ledger);
    }

    /**
     * 若当前回合是已发布且仍有未声明结果，则发送公开叙事与每个玩家私密结果。
     *
     * <p>方法加锁保证同一实例多个用户 mailbox 同时观察到 PUBLISHED 时也只声明一次。
     * 每个 key 的声明在发送前完成；声明异常会向调用方明确抛出，且不会继续发送该条消息。
     * 发送结果是尽力而为：任何失败都不回滚已提交的回合，也不会自动重发 UNKNOWN。</p>
     */
    public synchronized List<SendOutcome> distributeIfReady() {
        EngineStatus status = engine.status();
        TurnView turn = status.turn();
        if (turn == null || turn.state() != TurnState.PUBLISHED
                || turn.committedProposal() == null) {
            return List.of();
        }

        AdjudicationProposal proposal = turn.committedProposal();
        List<SendOutcome> outcomes = new ArrayList<>();
        String correlation = "turn:" + turn.turnId();

        if (proposal.publicNarrative() != null && !proposal.publicNarrative().isBlank()) {
            String key = correlation + ":public";
            if (claim(key, turn.turnId(), null)) {
                outcomes.addAll(outbox.broadcastToPublicGroup(publicGroupId,
                        List.of(proposal.publicNarrative()), key));
            }
        }

        if (proposal.playerOutcomes() == null) {
            logUnknowns(turn.turnId(), outcomes);
            LOG.info("回合结果分发完成 turnId={} messages={} unknown={}", turn.turnId(),
                    outcomes.size(), countUnknown(outcomes));
            return List.copyOf(outcomes);
        }

        Map<String, String> sorted = new LinkedHashMap<>();
        proposal.playerOutcomes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String playerId = entry.getKey();
            String qqId = playerQqById.get(playerId);
            if (qqId == null || qqId.isBlank()) {
                LOG.warn("回合结果缺少玩家 QQ 映射 turnId={} playerId={}",
                        turn.turnId(), playerId);
                continue;
            }
            String key = correlation + ":player:" + playerId;
            if (claim(key, turn.turnId(), playerId)) {
                outcomes.addAll(outbox.sendPrivateToUser(qqId, List.of(entry.getValue()), key));
            }
        }
        logUnknowns(turn.turnId(), outcomes);
        LOG.info("回合结果分发完成 turnId={} messages={} unknown={}", turn.turnId(),
                outcomes.size(), countUnknown(outcomes));
        return List.copyOf(outcomes);
    }

    private boolean claim(String key, String turnId, String playerId) {
        try {
            boolean claimed = ledger.claim(key);
            if (!claimed) {
                LOG.info("回合结果投递声明已存在，跳过 turnId={} playerId={} key={}",
                        turnId, playerId, key);
            }
            return claimed;
        } catch (RuntimeException failure) {
            // 只记录稳定标识和异常类型；不打印异常消息，避免任何正文/内部内容进入日志。
            LOG.error("回合结果投递声明失败，禁止发送 turnId={} playerId={} key={} type={}",
                    turnId, playerId, key, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static void logUnknowns(String turnId, List<SendOutcome> outcomes) {
        outcomes.stream().filter(SendOutcome::unknown).forEach(outcome ->
                LOG.error("回合结果进入 UNKNOWN，需人工处置 turnId={} key={}",
                        turnId, outcome.idempotencyKey()));
    }

    private static long countUnknown(List<SendOutcome> outcomes) {
        return outcomes.stream().filter(SendOutcome::unknown).count();
    }

    /** 兼容旧构造器；不提供跨实例/重启持久化。 */
    private static final class InMemoryDeliveryLedger implements DeliveryLedger {
        private final Set<String> keys = ConcurrentHashMap.newKeySet();

        @Override
        public boolean claim(String key) {
            if (key == null || key.isBlank()) {
                throw new DeliveryLedgerException(DeliveryLedgerException.Code.INVALID_KEY,
                        "投递 key 不能为空");
            }
            return keys.add(key);
        }
    }
}
