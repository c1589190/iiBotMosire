package io.mosire.iibot.taskflow.ticket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 工单完整不可变快照；幂等成功回执随快照持久化。 */
public record TicketSnapshot(
        List<Ticket> tickets,
        Map<String, TicketCommandResult> idempotentResults,
        Map<String, String> idempotencyFingerprints) {

    public TicketSnapshot {
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
        idempotentResults = immutableMap(idempotentResults);
        // 旧 TicketSnapshot JSON 没有该字段时，Jackson 会传 null；统一视为空映射。
        idempotencyFingerprints = immutableStringMap(idempotencyFingerprints);
        Set<String> ids = new LinkedHashSet<>();
        for (Ticket ticket : tickets) {
            Objects.requireNonNull(ticket, "ticket");
            if (!ids.add(ticket.ticketId())) {
                throw new IllegalArgumentException("重复 ticketId: " + ticket.ticketId());
            }
        }
        for (Map.Entry<String, TicketCommandResult> entry : idempotentResults.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("非法 idempotentResults 条目");
            }
        }
        for (Map.Entry<String, String> entry : idempotencyFingerprints.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("非法 idempotencyFingerprints 条目");
            }
        }
    }

    /** 兼容没有 fingerprint 的旧调用点；新写命令应使用带 fingerprint 的重载。 */
    public TicketSnapshot(List<Ticket> tickets,
                          Map<String, TicketCommandResult> idempotentResults) {
        this(tickets, idempotentResults, Map.of());
    }

    public static TicketSnapshot empty() {
        return new TicketSnapshot(List.of(), Map.of(), Map.of());
    }

    public int size() {
        return tickets.size();
    }

    public Optional<Ticket> find(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return Optional.empty();
        }
        return tickets.stream().filter(t -> t.ticketId().equals(ticketId)).findFirst();
    }

    public TicketSnapshot withTicket(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        List<Ticket> next = new ArrayList<>(tickets.size() + 1);
        boolean replaced = false;
        for (Ticket existing : tickets) {
            if (existing.ticketId().equals(ticket.ticketId())) {
                next.add(ticket);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(ticket);
        }
        return new TicketSnapshot(next, idempotentResults, idempotencyFingerprints);
    }

    /** 兼容旧调用点：写入回执但不写 fingerprint；生产服务使用带 fingerprint 的重载。 */
    public TicketSnapshot withTickets(List<Ticket> replacements, String idempotencyKey,
                                      TicketCommandResult result) {
        return withTicketsInternal(replacements, idempotencyKey, null, result);
    }

    public TicketSnapshot withTickets(List<Ticket> replacements, String idempotencyKey,
                                      String fingerprint, TicketCommandResult result) {
        return withTicketsInternal(replacements, idempotencyKey, fingerprint, result);
    }

    private TicketSnapshot withTicketsInternal(List<Ticket> replacements,
                                               String idempotencyKey, String fingerprint,
                                               TicketCommandResult result) {
        Objects.requireNonNull(replacements, "replacements");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        Objects.requireNonNull(result, "result");
        Map<String, Ticket> byId = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            byId.put(ticket.ticketId(), ticket);
        }
        for (Ticket ticket : replacements) {
            Objects.requireNonNull(ticket, "ticket");
            byId.put(ticket.ticketId(), ticket);
        }
        if (fingerprint != null && fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint 提供时不能为空白");
        }
        Map<String, TicketCommandResult> nextResults = new LinkedHashMap<>(idempotentResults);
        nextResults.put(idempotencyKey, result);
        Map<String, String> nextFingerprints = new LinkedHashMap<>(idempotencyFingerprints);
        if (fingerprint != null) {
            nextFingerprints.put(idempotencyKey, fingerprint);
        }
        return new TicketSnapshot(new ArrayList<>(byId.values()), nextResults, nextFingerprints);
    }

    /** 兼容旧调用点：写入回执但不写 fingerprint；新服务写路径必须携带 fingerprint。 */
    public TicketSnapshot withIdempotentResult(String idempotencyKey,
                                               TicketCommandResult result) {
        return withIdempotentResultInternal(idempotencyKey, null, result);
    }

    public TicketSnapshot withIdempotentResult(String idempotencyKey, String fingerprint,
                                               TicketCommandResult result) {
        return withIdempotentResultInternal(idempotencyKey, fingerprint, result);
    }

    private TicketSnapshot withIdempotentResultInternal(String idempotencyKey,
                                                        String fingerprint,
                                                        TicketCommandResult result) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        if (fingerprint != null && fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint 提供时不能为空白");
        }
        Objects.requireNonNull(result, "result");
        Map<String, TicketCommandResult> next = new LinkedHashMap<>(idempotentResults);
        next.put(idempotencyKey, result);
        Map<String, String> nextFingerprints = new LinkedHashMap<>(idempotencyFingerprints);
        if (fingerprint != null) {
            nextFingerprints.put(idempotencyKey, fingerprint);
        }
        return new TicketSnapshot(tickets, next, nextFingerprints);
    }

    private static Map<String, TicketCommandResult> immutableMap(
            Map<String, TicketCommandResult> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static Map<String, String> immutableStringMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
