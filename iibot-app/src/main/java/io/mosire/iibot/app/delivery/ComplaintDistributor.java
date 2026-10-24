package io.mosire.iibot.app.delivery;

import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintDeliveryState;
import io.mosire.iibot.taskflow.complaint.ComplaintService;
import io.mosire.iibot.taskflow.complaint.ComplaintStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 宿主侧投诉回应外发器：只消费 GM 已回应且投递状态待处理的投诉。
 *
 * <p>发送目标严格由投诉领域快照决定：投诉玩家 ID 通过宿主配置映射成 QQ 私聊。玩家秘书/LLM
 * 没有调用本类的工具，MCP 的回应工具也只调用 {@link ComplaintService#respond}，不能指定
 * 发送目标。相关键为 {@code complaint:<id>:response}，实际 outbox 幂等键由
 * {@link OutboxSender#sendPrivateToUser} 派生。</p>
 *
 * <p><b>送达语义</b>：SENT→markDelivered(SENT)、FAILED_BEFORE_SEND→markDelivered(FAILED)、
 * UNKNOWN→markDelivered(UNKNOWN)；UNKNOWN 不自动重发。本进程 attempted 集合确保同一
 * complaintId 只尝试一次；标记异常只记录脱敏日志，绝不回滚已提交的回应。未知 playerId 映射
 * 记录为 FAILED 且不崩溃。</p>
 */
public final class ComplaintDistributor {

    private static final Logger LOG = LoggerFactory.getLogger(ComplaintDistributor.class);

    private final ComplaintService complaintService;
    private final OutboxSender outbox;
    private final Function<String, String> playerQqById;
    private final Set<String> attemptedComplaintIds = new LinkedHashSet<>();

    /** 兼容固定路由构造器。 */
    public ComplaintDistributor(ComplaintService complaintService, OutboxSender outbox,
                                Map<String, String> playerQqById) {
        this(complaintService, outbox, mapLookup(
                Objects.requireNonNull(playerQqById, "playerQqById")));
    }

    /**
     * 动态路由构造器：每次回应外发时读取投诉玩家当前 QQ 映射，使运行期注册玩家
     * 无需重启即可收到回应。
     */
    public ComplaintDistributor(ComplaintService complaintService, OutboxSender outbox,
                                Function<String, String> playerQqById) {
        this.complaintService = Objects.requireNonNull(complaintService, "complaintService");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.playerQqById = Objects.requireNonNull(playerQqById, "playerQqById");
    }

    /**
     * 扫描并发送所有 status=RESPONDED 且 deliveryState=PENDING 的投诉。
     *
     * @return 本次尝试产生的发送结果；没有待发送投诉时为空列表
     */
    public synchronized List<SendOutcome> distributeIfReady() {
        List<Complaint> ready = complaintService.listForGm(null).stream()
                .filter(complaint -> complaint.status() == ComplaintStatus.RESPONDED)
                .filter(complaint -> complaint.deliveryState() == ComplaintDeliveryState.PENDING)
                .filter(complaint -> !attemptedComplaintIds.contains(complaint.complaintId()))
                .toList();
        if (ready.isEmpty()) {
            return List.of();
        }

        List<SendOutcome> outcomes = new ArrayList<>();
        for (Complaint complaint : ready) {
            attemptedComplaintIds.add(complaint.complaintId());
            outcomes.addAll(distributeOne(complaint));
        }
        return List.copyOf(outcomes);
    }

    private List<SendOutcome> distributeOne(Complaint complaint) {
        String qqId = playerQqById.apply(complaint.playerId());
        if (qqId == null || qqId.isBlank()) {
            LOG.warn("投诉回应缺少玩家 QQ 映射 complaintId={} playerId={}",
                    complaint.complaintId(), complaint.playerId());
            mark(complaint.complaintId(), ComplaintDeliveryState.FAILED);
            return List.of(new SendOutcome(0, privateKey(complaint.complaintId()),
                    SendReceipt.State.FAILED_BEFORE_SEND, List.of(),
                    "UNKNOWN_PLAYER", "投诉玩家没有配置 QQ 映射（未发送）"));
        }

        List<SendOutcome> sent;
        try {
            sent = outbox.sendPrivateToUser(qqId, List.of(complaint.responseBody()),
                    "complaint:" + complaint.complaintId() + ":response");
        } catch (RuntimeException failure) {
            // OutboxSender 已把常规发送异常收敛为三态；此处只处理发送调用本身异常。
            // 无法证明消息未发出，按 UNKNOWN 处理，禁止自动重发。
            LOG.warn("投诉回应发送调用异常 complaintId={} type={}",
                    complaint.complaintId(), failure.getClass().getSimpleName());
            mark(complaint.complaintId(), ComplaintDeliveryState.UNKNOWN);
            return List.of(new SendOutcome(0, privateKey(complaint.complaintId()),
                    SendReceipt.State.UNKNOWN, List.of(), "SEND_CALL_FAILED",
                    "发送调用异常，状态不确定（禁止自动重发）"));
        }
        if (sent == null || sent.isEmpty()) {
            LOG.warn("投诉回应发送未产生回执 complaintId={}", complaint.complaintId());
            mark(complaint.complaintId(), ComplaintDeliveryState.UNKNOWN);
            return List.of(new SendOutcome(0, privateKey(complaint.complaintId()),
                    SendReceipt.State.UNKNOWN, List.of(), "EMPTY_SEND_OUTCOME",
                    "发送未返回回执，状态不确定（禁止自动重发）"));
        }
        SendOutcome outcome = sent.get(0);
        ComplaintDeliveryState target = switch (outcome.state()) {
            case SENT -> ComplaintDeliveryState.SENT;
            case FAILED_BEFORE_SEND -> ComplaintDeliveryState.FAILED;
            case UNKNOWN -> ComplaintDeliveryState.UNKNOWN;
        };
        mark(complaint.complaintId(), target);
        if (outcome.unknown()) {
            LOG.error("投诉回应进入 UNKNOWN，需人工处置 complaintId={} key={}",
                    complaint.complaintId(), outcome.idempotencyKey());
        }
        return sent;
    }

    private void mark(String complaintId, ComplaintDeliveryState state) {
        try {
            ComplaintCommandResult result = complaintService.markDelivered(complaintId, state);
            if (!result.ok()) {
                LOG.warn("投诉回应送达状态标记失败 complaintId={} state={} code={}",
                        complaintId, state, result.code());
            }
        } catch (RuntimeException failure) {
            LOG.warn("投诉回应送达状态标记异常 complaintId={} state={} type={}",
                    complaintId, state, failure.getClass().getSimpleName());
        }
    }

    private static Function<String, String> mapLookup(Map<String, String> playerQqById) {
        Map<String, String> copy = Map.copyOf(playerQqById);
        return copy::get;
    }

    private static String privateKey(String complaintId) {
        return "private:complaint:" + complaintId + ":response:reply:0";
    }
}
