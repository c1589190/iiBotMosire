package io.mosire.iibot.taskflow.ticket;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.action.ActionPoolException;
import io.mosire.iibot.taskflow.action.ActionPoolService;
import io.mosire.iibot.taskflow.action.PlayerAction;

import java.util.Objects;
import java.util.Optional;

/**
 * ACTION_DRAFT ticket → ActionPool 晋升编排。
 *
 * <p>先以确定性 {@code ticket-promote:<ticketId>} 创建/复用 action，再调用
 * {@link TicketService#attachAction} 把工单关联到 action。若 action 已创建但 attach 失败，
 * 返回可重试的 {@link #PROMOTION_PARTIAL}；重试会按 source 找回同一 action 后重新 attach。</p>
 */
public final class TicketActionPromotionService {

    public static final String OK = "OK";
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String TICKET_NOT_FOUND = "TICKET_NOT_FOUND";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";
    public static final String PROMOTION_PARTIAL = "PROMOTION_PARTIAL";
    public static final String ACTION_UNAVAILABLE = "ACTION_UNAVAILABLE";

    private final TicketService ticketService;
    private final ActionPoolService actionPoolService;

    public TicketActionPromotionService(TicketService ticketService,
                                        ActionPoolService actionPoolService) {
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
        this.actionPoolService = Objects.requireNonNull(actionPoolService, "actionPoolService");
    }

    public synchronized PromotionResult promote(Actor gm, String ticketId,
                                                 long expectedRevision,
                                                 String idempotencyKey) {
        if (!isGmSide(gm)) {
            return PromotionResult.failure(FORBIDDEN, ticketId, null, "", 0);
        }
        if (ticketId == null || ticketId.isBlank()) {
            return PromotionResult.failure(INVALID_ARGUMENTS, null, null, "", 0);
        }
        if (expectedRevision < 0) {
            return PromotionResult.failure(INVALID_ARGUMENTS, ticketId, null, "", 0);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return PromotionResult.failure(INVALID_ARGUMENTS, ticketId, null, "", 0);
        }

        Ticket ticket = ticketService.getForGm(ticketId).orElse(null);
        if (ticket == null) {
            return PromotionResult.failure(TICKET_NOT_FOUND, ticketId, null, "", 0);
        }
        if (ticket.type() != TicketType.ACTION_DRAFT) {
            return PromotionResult.failure(INVALID_STATUS, ticketId, null,
                    ticket.status().name(), ticket.revision());
        }
        if (ticket.relatedActionId() != null) {
            Optional<PlayerAction> existing = actionPoolService.getForGm(
                    ticket.relatedActionId());
            if (existing.isPresent()) {
                return PromotionResult.success(ticketId, existing.get().actionId(),
                        ticket.status().name(), ticket.revision());
            }
            return PromotionResult.failure(PROMOTION_PARTIAL, ticketId,
                    ticket.relatedActionId(), ticket.status().name(), ticket.revision());
        }
        if (ticket.status() != TicketStatus.OPEN
                && ticket.status() != TicketStatus.CLAIMED) {
            return PromotionResult.failure(INVALID_STATUS, ticketId, null,
                    ticket.status().name(), ticket.revision());
        }
        if (ticket.revision() != expectedRevision) {
            return PromotionResult.failure(REVISION_CONFLICT, ticketId, null,
                    ticket.status().name(), ticket.revision());
        }

        PlayerAction action = actionPoolService.findByTicketId(ticketId).orElse(null);
        if (action == null) {
            try {
                action = actionPoolService.submitFromTicket(ticket.playerId(), ticketId,
                        ticket.body(), "ticket-promote:" + ticketId);
            } catch (ActionPoolException failure) {
                return PromotionResult.failure(failure.code(), ticketId, null,
                        ticket.status().name(), ticket.revision());
            } catch (RuntimeException failure) {
                return PromotionResult.failure(ACTION_UNAVAILABLE, ticketId, null,
                        ticket.status().name(), ticket.revision());
            }
        }

        TicketCommandResult attached = ticketService.attachAction(gm, ticketId,
                expectedRevision, action.actionId(), idempotencyKey);
        if (!attached.ok()) {
            // action 已成功创建；不得伪装成功。重试会按 ticket source 找回同一 action。
            Ticket latest = ticketService.getForGm(ticketId).orElse(ticket);
            return PromotionResult.failure(PROMOTION_PARTIAL, ticketId,
                    action.actionId(), latest.status().name(), latest.revision());
        }
        Ticket latest = ticketService.getForGm(ticketId).orElse(null);
        String status = latest == null ? TicketStatus.CLAIMED.name()
                : latest.status().name();
        long revision = latest == null ? attached.revision() : latest.revision();
        return PromotionResult.success(ticketId, action.actionId(), status, revision);
    }

    private static boolean isGmSide(Actor actor) {
        return actor != null && (actor.role() == ActorRole.DIRECTOR
                || actor.role() == ActorRole.GM
                || actor.role() == ActorRole.SUPERVISOR);
    }

    /** 晋升摘要；不含工单或行动正文。 */
    public record PromotionResult(boolean ok, String code, String ticketId,
                                  String actionId, String status, long revision) {
        public PromotionResult {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code 不能为空");
            }
            if (revision < 0) {
                throw new IllegalArgumentException("revision 不能为负");
            }
        }

        public static PromotionResult success(String ticketId, String actionId,
                                              String status, long revision) {
            return new PromotionResult(true, OK, ticketId, actionId, status, revision);
        }

        public static PromotionResult failure(String code, String ticketId, String actionId,
                                              String status, long revision) {
            return new PromotionResult(false, code, ticketId, actionId,
                    status == null ? "" : status, Math.max(0L, revision));
        }
    }
}
