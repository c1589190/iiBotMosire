package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ticket.TicketActionPromotionService;
import io.mosire.iibot.taskflow.ticket.TicketService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** GM 统一工单 MCP 工具集；完整 Ticket 装配下取代旧 complaint 三工具。 */
public final class GmTicketMcpTools {

    /** 不依赖 promotion 编排的 8 个基础工具。 */
    public static final List<String> BASE_NAMES = List.of(
            GmTicketBoardTool.NAME,
            GmTicketListTool.NAME,
            GmTicketGetTool.NAME,
            GmTicketClaimTool.NAME,
            GmTicketReplyTool.NAME,
            GmTicketBatchReplyTool.NAME,
            GmTicketCloseTool.NAME,
            GmTicketNoteCreateTool.NAME);

    /** 完整 P5-A 工具面：8 个基础工具 + 晋升工具。 */
    public static final List<String> NAMES = List.of(
            GmTicketBoardTool.NAME,
            GmTicketListTool.NAME,
            GmTicketGetTool.NAME,
            GmTicketClaimTool.NAME,
            GmTicketReplyTool.NAME,
            GmTicketBatchReplyTool.NAME,
            GmTicketCloseTool.NAME,
            GmTicketNoteCreateTool.NAME,
            GmTicketPromoteActionTool.NAME);

    /** Inbox 通知模式下保留的工单摘要/详情/写入工具，不暴露 board 长轮询入口。 */
    public static final List<String> NAMES_WITHOUT_BOARD = List.of(
            GmTicketListTool.NAME,
            GmTicketGetTool.NAME,
            GmTicketClaimTool.NAME,
            GmTicketReplyTool.NAME,
            GmTicketBatchReplyTool.NAME,
            GmTicketCloseTool.NAME,
            GmTicketNoteCreateTool.NAME);

    private GmTicketMcpTools() {
    }

    /** 兼容 P4 的 8 工具装配。 */
    public static List<AgentTool> all(TicketService ticketService, Actor supervisor) {
        Objects.requireNonNull(ticketService, "ticketService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                new GmTicketBoardTool(ticketService),
                new GmTicketListTool(ticketService),
                new GmTicketGetTool(ticketService),
                new GmTicketClaimTool(ticketService, supervisor),
                new GmTicketReplyTool(ticketService, supervisor),
                new GmTicketBatchReplyTool(ticketService, supervisor),
                new GmTicketCloseTool(ticketService, supervisor),
                new GmTicketNoteCreateTool(ticketService, supervisor));
    }

    /** P5-A 完整 9 工具装配。 */
    public static List<AgentTool> all(TicketService ticketService,
                                      TicketActionPromotionService promotionService,
                                      Actor supervisor) {
        Objects.requireNonNull(promotionService, "promotionService");
        List<AgentTool> tools = new ArrayList<>(all(ticketService, supervisor));
        tools.add(new GmTicketPromoteActionTool(promotionService, supervisor));
        return List.copyOf(tools);
    }

    /** Inbox 通知模式：不注册 tf_ticket_board，保留 list/get 和其余写工具。 */
    public static List<AgentTool> allWithoutBoard(TicketService ticketService,
                                                   Actor supervisor) {
        Objects.requireNonNull(ticketService, "ticketService");
        Objects.requireNonNull(supervisor, "supervisor");
        return List.of(
                new GmTicketListTool(ticketService),
                new GmTicketGetTool(ticketService),
                new GmTicketClaimTool(ticketService, supervisor),
                new GmTicketReplyTool(ticketService, supervisor),
                new GmTicketBatchReplyTool(ticketService, supervisor),
                new GmTicketCloseTool(ticketService, supervisor),
                new GmTicketNoteCreateTool(ticketService, supervisor));
    }

    /** Inbox 通知模式完整 P5-A：无 board + 晋升工具。 */
    public static List<AgentTool> allWithoutBoard(
            TicketService ticketService,
            TicketActionPromotionService promotionService,
            Actor supervisor) {
        Objects.requireNonNull(promotionService, "promotionService");
        List<AgentTool> tools = new ArrayList<>(allWithoutBoard(ticketService, supervisor));
        tools.add(new GmTicketPromoteActionTool(promotionService, supervisor));
        return List.copyOf(tools);
    }
}
