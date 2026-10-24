package io.mosire.iibot.taskflow.agent;

import io.mosire.iibot.connect.core.InboundMessage;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.CommandResult;
import io.mosire.iibot.taskflow.DecisionDraft;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.SettingProposal;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.TurnState;
import io.mosire.iibot.taskflow.TurnView;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;
import io.mosire.iibot.taskflow.ticket.Ticket;
import io.mosire.iibot.taskflow.ticket.TicketCommandResult;
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * QQ 入站消息到任务流回复的纯应用服务。
 *
 * <p>不直接调用 MessagingAdapter；所有回复交给上层 outbox。群聊本版不进入 Agent，只保留
 * publicGroupId 配置供未来公开通道使用，因此返回空列表。私聊普通文本只交给该玩家的
 * {@link SecretarySession}；硬确认和推演命令都按宿主配置映射校验身份，且所有领域写操作只经
 * {@link TaskflowEngine} 的 revision CAS 入口。</p>
 */
public final class InteractionTaskflowService {

    private static final int DEFAULT_MAX_TURN_MINUTES = 240;
    private static final int DEFAULT_TURN_MINUTES = 30;

    private final TaskflowEngine engine;
    private final IdentityResolver identityResolver;
    private final SecretarySessionRegistry sessionRegistry;
    private final AdjudicationService adjudicationService;
    private final Supplier<List<WorldFact>> worldFactsSupplier;
    private final String publicGroupId;
    private final Clock clock;
    private final int maxTurnMinutes;
    private final Actor autoFreezeActor;
    private final TicketService ticketService;

    public InteractionTaskflowService(TaskflowEngine engine, IdentityResolver identityResolver,
                                      SecretarySessionRegistry sessionRegistry,
                                      AdjudicationService adjudicationService,
                                      Supplier<List<WorldFact>> worldFactsSupplier,
                                      String publicGroupId, Clock clock) {
        this(engine, identityResolver, sessionRegistry, adjudicationService, worldFactsSupplier,
                publicGroupId, clock, DEFAULT_MAX_TURN_MINUTES, null);
    }

    public InteractionTaskflowService(TaskflowEngine engine, IdentityResolver identityResolver,
                                      SecretarySessionRegistry sessionRegistry,
                                      AdjudicationService adjudicationService,
                                      Supplier<List<WorldFact>> worldFactsSupplier,
                                      String publicGroupId, Clock clock, int maxTurnMinutes,
                                      Actor autoFreezeActor) {
        this(engine, identityResolver, sessionRegistry, adjudicationService, worldFactsSupplier,
                publicGroupId, clock, maxTurnMinutes, autoFreezeActor, null);
    }

    /** 完整构造器：额外注入 ACTION_DRAFT 工单确认服务。 */
    public InteractionTaskflowService(TaskflowEngine engine, IdentityResolver identityResolver,
                                      SecretarySessionRegistry sessionRegistry,
                                      AdjudicationService adjudicationService,
                                      Supplier<List<WorldFact>> worldFactsSupplier,
                                      String publicGroupId, Clock clock, int maxTurnMinutes,
                                      Actor autoFreezeActor, TicketService ticketService) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.adjudicationService = Objects.requireNonNull(adjudicationService, "adjudicationService");
        this.worldFactsSupplier = Objects.requireNonNull(worldFactsSupplier, "worldFactsSupplier");
        if (publicGroupId == null || publicGroupId.isBlank()) {
            throw new IllegalArgumentException("publicGroupId 不能为空");
        }
        this.publicGroupId = publicGroupId;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxTurnMinutes <= 0) {
            throw new IllegalArgumentException("maxTurnMinutes 必须为正");
        }
        this.maxTurnMinutes = maxTurnMinutes;
        this.autoFreezeActor = autoFreezeActor;
        this.ticketService = ticketService;
    }

    /** 从 QQ 映射 map 构造，便捷用于 app/tests。 */
    public InteractionTaskflowService(TaskflowEngine engine,
                                      Map<String, MappedIdentity> qqToIdentity,
                                      SecretarySessionRegistry sessionRegistry,
                                      AdjudicationService adjudicationService,
                                      Supplier<List<WorldFact>> worldFactsSupplier,
                                      String publicGroupId, Clock clock) {
        this(engine, IdentityResolver.fromMap(qqToIdentity), sessionRegistry,
                adjudicationService, worldFactsSupplier, publicGroupId, clock);
    }

    /** 从 QQ 映射 map 构造（可配置自动冻结 actor）。 */
    public InteractionTaskflowService(TaskflowEngine engine,
                                      Map<String, MappedIdentity> qqToIdentity,
                                      SecretarySessionRegistry sessionRegistry,
                                      AdjudicationService adjudicationService,
                                      Supplier<List<WorldFact>> worldFactsSupplier,
                                      String publicGroupId, Clock clock, int maxTurnMinutes,
                                      Actor autoFreezeActor) {
        this(engine, IdentityResolver.fromMap(qqToIdentity), sessionRegistry,
                adjudicationService, worldFactsSupplier, publicGroupId, clock,
                maxTurnMinutes, autoFreezeActor);
    }

    /**
     * 处理一条入站消息。
     *
     * @return 小回复列表；群聊/忽略场景返回空列表
     */
    public List<InteractionReply> handle(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.sessionType() == InboundMessage.SessionType.GROUP) {
            // 本版群聊不进入 Agent；只保留 publicGroupId 配置边界，不产生任何确认/推演副作用。
            return List.of();
        }

        Optional<MappedIdentity> resolved = identityResolver.resolve(message.userId());
        if (resolved.isEmpty()) {
            return reply("未授权的 QQ 账号，请联系管理员完成身份映射。");
        }
        MappedIdentity identity = resolved.get();
        String text = message.text() == null ? "" : message.text().trim();
        if (text.isEmpty()) {
            return List.of();
        }

        if (text.equals("/帮助")) {
            return reply(help(identity));
        }
        if (text.equals("/状态")) {
            return reply(statusText());
        }
        if (text.startsWith("/提议设定")) {
            return handleProposeSetting(message, identity, text);
        }
        if (text.startsWith("/设定")) {
            return handleDirectSetting(message, identity, text);
        }
        if (text.startsWith("/待审设定")) {
            return handlePendingSettings(identity);
        }
        if (text.startsWith("/通过设定")) {
            return handleDecideSetting(message, identity, text, true);
        }
        if (text.startsWith("/拒绝设定")) {
            return handleDecideSetting(message, identity, text, false);
        }
        if (text.startsWith("/确认")) {
            return handleConfirm(message, identity, text);
        }
        if (text.startsWith("/开回合") || text.equals("/冻结")
                || text.equals("/推演") || text.equals("/发布")) {
            return handleTurnCommand(message, identity, text);
        }

        if (!identity.isPlayer()) {
            return reply("该账号是推演身份，没有玩家秘书会话；请使用 /帮助 查看推演命令。");
        }
        SecretarySession session = sessionRegistry.find(identity.profile().playerId())
                .orElse(null);
        if (session == null) {
            return reply("当前玩家尚未装配秘书会话。");
        }
        String answer = session.chat(text);
        return reply(answer);
    }

    /**
     * 自动冻结 hook：只检查并调用 engine.lockTurn，不启动后台线程。
     *
     * @param operator 调度器使用的已认证操作者（GM/DIRECTOR/SUPERVISOR）
     */
    public Optional<CommandResult> pollExpiredTurn(Actor operator) {
        Objects.requireNonNull(operator, "operator");
        TurnView turn = engine.turnView().orElse(null);
        if (turn == null || turn.state() != TurnState.OPEN
                || turn.closesAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(engine.lockTurn(operator, turn.revision(),
                "auto-freeze-" + turn.turnId() + "-" + turn.revision()));
    }

    /** 使用构造时注入的自动冻结 actor；未注入时返回空。 */
    public Optional<CommandResult> pollExpiredTurn() {
        return autoFreezeActor == null ? Optional.empty() : pollExpiredTurn(autoFreezeActor);
    }

    private List<InteractionReply> handleProposeSetting(InboundMessage message,
                                                         MappedIdentity identity,
                                                         String text) {
        if (!identity.isPlayer()) {
            return reply("权限不足：只有已映射的玩家可以提交设定提案；推演请使用 `/设定 <文本>`。");
        }
        String content = argument(text, "/提议设定");
        if (content.isBlank()) {
            return reply("用法：`/提议设定 <文本>`（玩家提案固定为 PUBLIC，等待推演审核）。");
        }
        long revision = engine.status().engineRevision();
        CommandResult result = engine.proposeSetting(identity.actor(), content,
                Visibility.PUBLIC, revision, idempotency(message, "propose-setting"));
        if (!result.ok()) {
            return reply("提议设定失败：[" + result.code() + "] " + result.message());
        }
        String proposalId = result.referenceId().orElse("");
        return reply("设定提案已提交，等待推演审核。\nproposalId: " + proposalId
                + "\nstatus: PENDING\nvisibility: PUBLIC");
    }

    private List<InteractionReply> handleDirectSetting(InboundMessage message,
                                                        MappedIdentity identity,
                                                        String text) {
        if (!identity.isOperator()) {
            return reply("权限不足：只有已映射的 DIRECTOR/GM/SUPERVISOR 可以使用 `/设定 <文本>`。");
        }
        String content = argument(text, "/设定");
        if (content.isBlank()) {
            return reply("用法：`/设定 <文本>`（直接创建并批准一条 PUBLIC 世界事实）。");
        }
        CommandResult proposed = engine.proposeSetting(identity.actor(), content,
                Visibility.PUBLIC, engine.status().engineRevision(),
                idempotency(message, "direct-setting-propose"));
        if (!proposed.ok()) {
            return reply("设定失败：[" + proposed.code() + "] " + proposed.message());
        }
        String proposalId = proposed.referenceId().orElse("");
        CommandResult decided = engine.decideSetting(identity.actor(), proposalId, true,
                "推演直接设定", engine.status().engineRevision(),
                idempotency(message, "direct-setting-approve"));
        if (!decided.ok()) {
            return reply("设定提案已创建，但自动批准失败：[" + decided.code() + "] "
                    + decided.message() + "\nproposalId: " + proposalId
                    + "\n可发送 `/通过设定 " + proposalId + "` 重试审核。");
        }
        return reply("设定已生效。\nproposalId: " + proposalId
                + "\nworldFactId: " + decided.details().getOrDefault("worldFactId", "")
                + "\nworldVersion: " + decided.details().getOrDefault("worldVersion", ""));
    }

    private List<InteractionReply> handlePendingSettings(MappedIdentity identity) {
        if (!identity.isOperator()) {
            return reply("权限不足：只有已映射的 DIRECTOR/GM/SUPERVISOR 可以查看待审设定。");
        }
        List<SettingProposal> pending = engine.pendingSettings();
        if (pending.isEmpty()) {
            return reply("当前没有待审设定。");
        }
        StringBuilder text = new StringBuilder("待审设定（").append(pending.size()).append("）\n");
        for (SettingProposal proposal : pending) {
            text.append("- ").append(proposal.proposalId())
                    .append(" | author=").append(proposal.authorActorId())
                    .append(" | visibility=").append(visibilityLabel(proposal.visibility()))
                    .append(" | ").append(preview(proposal.content()))
                    .append('\n');
        }
        text.append("审核：`/通过设定 <proposalId>` 或 `/拒绝设定 <proposalId> [理由]`");
        return reply(text.toString());
    }

    private List<InteractionReply> handleDecideSetting(InboundMessage message,
                                                       MappedIdentity identity,
                                                       String text, boolean approve) {
        if (!identity.isOperator()) {
            return reply("权限不足：只有已映射的 DIRECTOR/GM/SUPERVISOR 可以审核设定提案。");
        }
        String command = approve ? "/通过设定" : "/拒绝设定";
        String argument = argument(text, command);
        if (argument.isBlank()) {
            return reply("用法：`" + command + " <proposalId>"
                    + (approve ? "`" : " [理由]`") + "。");
        }
        String[] parts = argument.split("\\s+", 2);
        String proposalId = parts[0];
        if (!proposalId.matches("[A-Za-z0-9._:-]+")) {
            return reply("审核失败：proposalId 格式不合法。请从 `/待审设定` 复制 proposalId。");
        }
        String note = parts.length > 1 ? parts[1].trim() : "";
        CommandResult result = engine.decideSetting(identity.actor(), proposalId, approve, note,
                engine.status().engineRevision(),
                idempotency(message, approve ? "approve-setting" : "reject-setting"));
        if (!result.ok()) {
            return reply("审核失败：[" + result.code() + "] " + result.message());
        }
        if (approve) {
            return reply("已通过设定。\nproposalId: " + proposalId
                    + "\nworldFactId: " + result.details().getOrDefault("worldFactId", "")
                    + "\nworldVersion: " + result.details().getOrDefault("worldVersion", ""));
        }
        return reply("已拒绝设定。\nproposalId: " + proposalId
                + "\nstatus: REJECTED\nworldVersion: "
                + result.details().getOrDefault("worldVersion", "")
                + "（未改变）");
    }

    private static String argument(String text, String command) {
        if (!text.startsWith(command)) {
            return "";
        }
        return text.substring(command.length()).trim();
    }

    private static String visibilityLabel(Visibility visibility) {
        if (visibility instanceof Visibility.PublicVisibility) {
            return "PUBLIC";
        }
        if (visibility instanceof Visibility.DirectorOnlyVisibility) {
            return "DIRECTOR_ONLY";
        }
        if (visibility instanceof Visibility.PlayerSetVisibility playerSet) {
            return "PLAYERS:" + String.join(",", playerSet.playerIds().stream()
                    .sorted().toList());
        }
        return visibility.getClass().getSimpleName();
    }

    private static String preview(String content) {
        String flattened = content.replace('\n', ' ').replace('\r', ' ');
        return flattened.length() <= 80 ? flattened : flattened.substring(0, 80) + "…";
    }

    private List<InteractionReply> handleConfirm(InboundMessage message, MappedIdentity identity,
                                                  String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 2 || !parts[1].matches("[A-Za-z0-9._:-]+")) {
            return reply("确认没有成功。请直接复制秘书最后给你的那一行：/确认 <确认码>。");
        }
        if (!identity.isPlayer()) {
            return reply("确认没有成功。只有玩家本人的私聊可以确认行动。");
        }
        String token = parts[1];
        String playerId = identity.profile().playerId();

        DecisionDraft draft = engine.draft(token)
                .or(() -> engine.findDraftByConfirmationCode(playerId, token))
                .orElse(null);
        if (draft != null) {
            return confirmDecisionDraft(message, identity, draft);
        }

        if (ticketService != null) {
            Ticket ticket = ticketService.findActionDraftByConfirmationCode(playerId, token)
                    .orElse(null);
            if (ticket != null) {
                return confirmActionTicket(message, identity, ticket);
            }
        }

        return reply("这个确认码无效、已经过期，或者不属于你。让秘书重新发你一份行动，再确认一次即可。");
    }

    private List<InteractionReply> confirmDecisionDraft(InboundMessage message,
                                                        MappedIdentity identity,
                                                        DecisionDraft draft) {
        if (!draft.playerId().equals(identity.profile().playerId())) {
            return reply("这个确认码不属于你。");
        }
        if (draft.status() == io.mosire.iibot.taskflow.DraftStatus.CONFIRMED) {
            return reply("这份行动已经确认过了，不需要再确认。等我或 GM 给你结果即可。");
        }
        if (draft.status() != io.mosire.iibot.taskflow.DraftStatus.DRAFT) {
            return reply("这份行动已经失效或已经被新的草案替代。让秘书重新发你一份即可。");
        }
        TurnView turn = engine.turnView().orElse(null);
        if (turn == null) {
            return reply("现在没有进行中的回合，暂时不能确认行动。");
        }
        if (!draft.turnId().equals(turn.turnId())) {
            return reply("这份行动不属于当前回合，已经失效。让秘书重新发你一份即可。");
        }
        if (!draft.expiresAt().isAfter(clock.instant())) {
            return reply("这份行动的确认时间已经过了。让秘书重新发你一份即可。");
        }
        CommandResult result = engine.confirmDecision(identity.actor(), draft.draftId(),
                draft.previewHash(), turn.revision(), idempotency(message, "confirm"));
        if (!result.ok()) {
            return reply(friendlyConfirmFailure(result.code()));
        }
        return reply("已确认。你说的这一步已经记下来了，GM 处理后我会把结果和下一步告诉你。");
    }

    private List<InteractionReply> confirmActionTicket(InboundMessage message,
                                                       MappedIdentity identity, Ticket ticket) {
        if (!ticket.playerId().equals(identity.profile().playerId())) {
            return reply("这个确认码不属于你。");
        }
        if (ticket.status() == TicketStatus.CONFIRMED) {
            return reply("这份行动已经确认过了，不需要再确认。GM 会继续处理。");
        }
        if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.CLAIMED) {
            return reply("这份行动已经失效或已被处理。让秘书重新发你一份即可。");
        }
        TicketCommandResult result = ticketService.confirmActionDraft(identity.actor(),
                ticket.ticketId(), ticket.revision(), idempotency(message, "confirm-ticket"));
        if (!result.ok()) {
            return reply(friendlyConfirmFailure(result.code()));
        }
        return reply("已确认。你的行动已经交给 GM，接下来等结果即可。");
    }

    private static String friendlyConfirmFailure(io.mosire.iibot.taskflow.ErrorCode code) {
        if (code == io.mosire.iibot.taskflow.ErrorCode.REVISION_CONFLICT) {
            return "局面刚刚有更新。让秘书重新发你一份最新的行动，再确认一次。";
        }
        if (code == io.mosire.iibot.taskflow.ErrorCode.DRAFT_EXPIRED) {
            return "这份行动已经过期。让秘书重新发你一份即可。";
        }
        if (code == io.mosire.iibot.taskflow.ErrorCode.DECISION_ALREADY_CONFIRMED) {
            return "你本回合已经确认过行动了，不需要再确认。";
        }
        return "刚才没能确认成功。让秘书重新发你一份行动，再试一次。";
    }

    private static String friendlyConfirmFailure(String ignored) {
        return "刚才没能确认成功。让秘书重新发你一份行动，再试一次。";
    }

    private List<InteractionReply> handleTurnCommand(InboundMessage message,
                                                      MappedIdentity identity, String text) {
        if (!identity.isOperator()) {
            return reply("权限不足：推演命令只允许已映射的推演/GM/监督账号。");
        }
        if (text.startsWith("/开回合")) {
            String[] parts = text.split("\\s+");
            if (parts.length != 2) {
                return reply("用法：`/开回合 <分钟>`");
            }
            int minutes;
            try {
                minutes = Integer.parseInt(parts[1]);
            } catch (NumberFormatException invalid) {
                return reply("开回合失败：分钟必须是正整数。");
            }
            if (minutes <= 0 || minutes > maxTurnMinutes) {
                return reply("开回合失败：分钟必须在 1.." + maxTurnMinutes + " 范围内。");
            }
            EngineStatus status = engine.status();
            CommandResult result = engine.openTurn(identity.actor(), status.engineRevision(),
                    idempotency(message, "open-turn"),
                    clock.instant().plus(Duration.ofMinutes(minutes)));
            return reply(format(result, "回合已开启"));
        }
        if (text.equals("/冻结")) {
            TurnView turn = engine.turnView().orElse(null);
            if (turn == null) {
                return reply("冻结失败：[NO_ACTIVE_TURN] 当前没有进行中的回合。");
            }
            CommandResult result = engine.lockTurn(identity.actor(), turn.revision(),
                    idempotency(message, "lock-turn"));
            return reply(format(result, "回合已冻结"));
        }
        if (text.equals("/推演")) {
            List<WorldFact> facts = worldFactsSupplier.get();
            if (facts == null) {
                return reply("推演失败：宿主未提供世界事实。");
            }
            AdjudicationResult result = adjudicationService.adjudicate(identity.actor(), facts);
            if (result.committed()) {
                return reply("推演已提交。\nturnId: " + result.turnId()
                        + "\nstate: " + result.turnState()
                        + (result.text().isBlank() ? "" : "\n" + result.text()));
            }
            return reply("推演未提交（可重试）。\nturnId: " + result.turnId()
                    + "\nstate: " + result.turnState()
                    + "\n原因: " + result.failureReason());
        }
        if (text.equals("/发布")) {
            TurnView turn = engine.turnView().orElse(null);
            if (turn == null) {
                return reply("发布失败：[NO_ACTIVE_TURN] 当前没有进行中的回合。");
            }
            CommandResult result = engine.publishTurn(identity.actor(), turn.revision(),
                    idempotency(message, "publish-turn"));
            return reply(format(result, "回合已发布"));
        }
        return reply(help(identity));
    }

    private String statusText() {
        EngineStatus status = engine.status();
        StringBuilder text = new StringBuilder("任务流状态\n")
                .append("engineRevision: ").append(status.engineRevision()).append('\n')
                .append("worldVersion: ").append(status.worldVersion()).append('\n')
                .append("players: ").append(status.playerCount()).append('\n')
                .append("directors: ").append(status.directorCount()).append('\n')
                .append("events: ").append(status.eventCount()).append('\n');
        status.currentTurn().ifPresentOrElse(turn -> text.append("turn: ").append(turn.turnId())
                        .append(" state=").append(turn.state())
                        .append(" revision=").append(turn.revision())
                        .append(" drafts=").append(turn.drafts().size())
                        .append(" decisions=").append(turn.decisions().size())
                        .append(" closesAt=").append(turn.closesAt()),
                () -> text.append("turn: 无"));
        return text.toString();
    }

    private String help(MappedIdentity identity) {
        if (identity.isPlayer()) {
            return "玩家命令：\n"
                    + "普通文本：与私人秘书对话\n"
                    + "/提议设定 <文本>：提交 PUBLIC 世界观设定提案，等待推演审核\n"
                    + "/确认 <确认码>：确认秘书最后发给你的行动\n"
                    + "/状态：查看任务流状态\n"
                    + "/帮助：查看帮助";
        }
        return "推演命令：\n"
                + "/设定 <文本>：创建并批准一条 PUBLIC 世界事实\n"
                + "/待审设定：列出等待审核的设定提案\n"
                + "/通过设定 <proposalId>：批准待审设定\n"
                + "/拒绝设定 <proposalId> [理由]：拒绝待审设定\n"
                + "/开回合 <分钟>：开启回合\n"
                + "/冻结：冻结当前 OPEN 回合\n"
                + "/推演：对冻结快照运行 GM Agent\n"
                + "/发布：发布 COMMITTED 回合\n"
                + "/状态：查看任务流状态\n"
                + "/帮助：查看帮助";
    }

    private static String format(CommandResult result, String successPrefix) {
        if (result.ok()) {
            return successPrefix + "。\nstate: " + result.turnState()
                    + "\nrevision: " + result.revision()
                    + "\nengineRevision: " + result.engineRevision();
        }
        return "操作失败：[" + result.code() + "] " + result.message();
    }

    private String idempotency(InboundMessage message, String action) {
        return "inbound:" + message.dedupeKey() + ":" + action;
    }

    private static List<InteractionReply> reply(String text) {
        return List.of(new InteractionReply(text));
    }
}
