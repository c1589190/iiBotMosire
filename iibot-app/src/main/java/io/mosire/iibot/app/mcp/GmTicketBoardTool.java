package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;
import io.mosire.iibot.taskflow.ticket.TicketType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GM 工单板：按类型/状态计数 + 最近 OPEN/CLAIMED 摘要；不返回正文。
 *
 * <p>wait=true 时进入长轮询：若当前已有待处理工单立即返回；否则阻塞到下一次
 * 成功工单变更/新工单到达或 waitSeconds 超时。</p>
 */
public final class GmTicketBoardTool extends GmTicketToolSupport.BaseTool {

    public static final String NAME = "tf_ticket_board";
    public static final int DEFAULT_WAIT_SECONDS = 300;
    public static final int MAX_WAIT_SECONDS = 1_800;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "查看工单板计数与最近 OPEN/CLAIMED 工单摘要；"
                    + "wait=true 时阻塞到第一条新信息或超时。",
            "properties", Map.of(
                    "wait", Map.of("type", "boolean",
                            "description", "true=长轮询等待工单变化；默认 false"),
                    "waitSeconds", Map.of("type", "integer", "minimum", 1,
                            "maximum", MAX_WAIT_SECONDS,
                            "description", "wait=true 时最长等待秒数，默认 "
                                    + DEFAULT_WAIT_SECONDS)),
            "required", List.of(),
            "additionalProperties", false);

    private final TicketService ticketService;

    public GmTicketBoardTool(TicketService ticketService) {
        super(NAME, "GM 工单板；只返回计数与摘要，不返回正文；支持长轮询等待。",
                SCHEMA, Operation.READ, GmTicketToolSupport.readSpec());
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService");
    }

    @Override
    protected ToolResult run(Map<String, Object> arguments) {
        boolean wait = optionalBoolean(arguments, "wait", false);
        int waitSeconds = optionalWaitSeconds(arguments);

        TicketService.StateView initial = ticketService.stateView();
        boolean alreadyPending = hasPending(initial.snapshot());
        boolean wokeByChange = false;
        boolean waitTimedOut = false;

        if (wait && !alreadyPending) {
            long deadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(waitSeconds).toNanos();
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    waitTimedOut = true;
                    break;
                }
                boolean changed;
                try {
                    changed = ticketService.awaitChange(initial.version(),
                            Duration.ofNanos(remainingNanos));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return GmTicketToolSupport.failure(NAME, "WAIT_INTERRUPTED",
                            "工单板等待被中断");
                }
                if (changed) {
                    wokeByChange = true;
                    break;
                }
                waitTimedOut = true;
                break;
            }
        }

        TicketService.StateView current = ticketService.stateView();
        if (current.version() > initial.version()) {
            wokeByChange = true;
            waitTimedOut = false;
        }
        Map<String, Object> data = boardData(current.snapshot());
        data.put("waitMode", wait);
        data.put("alreadyPending", alreadyPending);
        data.put("wokeByChange", wokeByChange);
        data.put("waitTimedOut", waitTimedOut);
        data.put("changeVersion", current.version());
        return GmTicketToolSupport.success(NAME, data);
    }

    private static Map<String, Object> boardData(
            io.mosire.iibot.taskflow.ticket.TicketSnapshot snapshot) {
        List<Ticket> all = snapshot.tickets();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (TicketType type : TicketType.values()) {
            byType.put(type.name(), 0);
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (TicketStatus status : TicketStatus.values()) {
            byStatus.put(status.name(), 0);
        }
        for (Ticket ticket : all) {
            byType.compute(ticket.type().name(), (key, count) ->
                    count == null ? 1 : count + 1);
            byStatus.compute(ticket.status().name(), (key, count) ->
                    count == null ? 1 : count + 1);
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        all.stream()
                .filter(ticket -> isPending(ticket.status()))
                .sorted(Comparator.comparing(Ticket::updatedAt).reversed()
                        .thenComparing(Ticket::ticketId))
                .limit(GmTicketToolSupport.RECENT_LIMIT)
                .forEach(ticket -> recent.add(GmTicketToolSupport.summary(ticket)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", all.size());
        data.put("byType", byType);
        data.put("byStatus", byStatus);
        // 兼容 board 消费方的常见命名；内容均为同一份无正文计数/摘要。
        data.put("typeCounts", byType);
        data.put("statusCounts", byStatus);
        data.put("recent", recent);
        data.put("openClaimedRecent", recent);
        return data;
    }

    private static boolean hasPending(
            io.mosire.iibot.taskflow.ticket.TicketSnapshot snapshot) {
        return snapshot.tickets().stream().anyMatch(ticket -> isPending(ticket.status()));
    }

    private static boolean isPending(TicketStatus status) {
        return status == TicketStatus.OPEN
                || status == TicketStatus.CLAIMED
                || status == TicketStatus.CONFIRMED;
    }

    private static boolean optionalBoolean(Map<String, Object> args, String name,
                                           boolean fallback) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean value)) {
            throw new GmTicketToolSupport.InvalidArgumentsException(
                    name + " 必须是布尔值");
        }
        return value;
    }

    private static int optionalWaitSeconds(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("waitSeconds");
        if (raw == null) {
            return DEFAULT_WAIT_SECONDS;
        }
        if (!(raw instanceof Number number)) {
            throw new GmTicketToolSupport.InvalidArgumentsException(
                    "waitSeconds 必须是整数");
        }
        long value = number.longValue();
        if (value < 1 || value > MAX_WAIT_SECONDS) {
            throw new GmTicketToolSupport.InvalidArgumentsException(
                    "waitSeconds 必须在 1.." + MAX_WAIT_SECONDS + " 范围内");
        }
        return (int) value;
    }
}
