package io.mosire.iibot.taskflow.ticket;

import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P4 统一工单领域：类型/身份隔离、状态机、CAS、幂等、批量原子与写失败零推进。 */
class TicketServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Actor PLAYER_A = new Actor("player-a", ActorRole.PLAYER);
    private static final Actor PLAYER_B = new Actor("player-b", ActorRole.PLAYER);
    private static final Actor GM = new Actor("gm-1", ActorRole.GM);
    private static final Actor DIRECTOR = new Actor("director-1", ActorRole.DIRECTOR);
    private static final Actor SUPERVISOR = new Actor("supervisor-1", ActorRole.SUPERVISOR);

    @Test
    void secretarySubmitsAnyNonGmNoteTypeWithoutTurnAndPlayersAreIsolated() {
        TicketService service = service();

        for (TicketType type : List.of(TicketType.SETTING_QUERY,
                TicketType.ACTION_DRAFT, TicketType.COMPLAINT)) {
            TicketCommandResult result = service.submit(PLAYER_A, type,
                    "标题-" + type, "正文-" + type, "submit-" + type);
            assertThat(result.ok()).as(type + " -> " + result).isTrue();
            assertThat(result.revision()).isEqualTo(1);
            Ticket ticket = service.getForGm(result.ticketId()).orElseThrow();
            assertThat(ticket.playerId()).isEqualTo("player-a");
            assertThat(ticket.type()).isEqualTo(type);
            assertThat(ticket.status()).isEqualTo(TicketStatus.OPEN);
            assertThat(ticket.body()).isEqualTo("正文-" + type);
        }
        TicketCommandResult noteByPlayer = service.submit(PLAYER_A, TicketType.GM_NOTE,
                "玩家备注", "玩家不能建 GM_NOTE", "player-gm-note");
        assertThat(noteByPlayer.ok()).isFalse();
        assertThat(noteByPlayer.code()).isEqualTo(TicketService.INVALID_ARGUMENT);

        TicketCommandResult note = service.createGmNote(GM, "GM 备注", "仅 GM 可见备注",
                "gm-note-1");
        assertThat(note.ok()).isTrue();
        Ticket gmNote = service.getForGm(note.ticketId()).orElseThrow();
        assertThat(gmNote.type()).isEqualTo(TicketType.GM_NOTE);
        assertThat(gmNote.playerId()).isNull();
        assertThat(service.createGmNote(PLAYER_A, "t", "b", "bad-note").ok()).isFalse();
        assertThat(service.createGmNote(PLAYER_A, "t", "b", "bad-note").code())
                .isEqualTo(TicketService.FORBIDDEN);

        TicketCommandResult b = service.submit(PLAYER_B, TicketType.COMPLAINT,
                "B 标题", "B 绝密正文", "submit-b");
        assertThat(service.listForSecretary("player-a"))
                .extracting(Ticket::playerId).containsOnly("player-a");
        assertThat(service.listForSecretary("player-b"))
                .extracting(Ticket::ticketId).containsExactly(b.ticketId());
        assertThat(service.getForSecretary("player-b", note.ticketId())).isEmpty();
        assertThat(service.getForSecretary("player-a", b.ticketId())).isEmpty();
        assertThat(service.listForGm()).hasSize(5);
    }

    @Test
    void claimReplyCloseEnforceStateMachineRevisionCasAndGmRoles() {
        TicketService service = service();
        String ticketId = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "状态机标题", "状态机正文", "state-submit").ticketId();

        assertThat(service.claim(PLAYER_A, ticketId, 1, "bad-claim-role").code())
                .isEqualTo(TicketService.FORBIDDEN);
        assertThat(service.claim(GM, ticketId, 0, "bad-claim-rev").code())
                .isEqualTo(TicketService.REVISION_CONFLICT);
        TicketCommandResult claimed = service.claim(GM, ticketId, 1, "claim-1");
        assertThat(claimed.ok()).isTrue();
        assertThat(claimed.revision()).isEqualTo(2);
        assertThat(service.getForGm(ticketId).orElseThrow().status())
                .isEqualTo(TicketStatus.CLAIMED);
        assertThat(service.claim(GM, ticketId, 2, "claim-again").code())
                .isEqualTo(TicketService.INVALID_STATUS);

        assertThat(service.reply(DIRECTOR, ticketId, 1, "stale reply", "reply-stale").code())
                .isEqualTo(TicketService.REVISION_CONFLICT);
        TicketCommandResult replied = service.reply(DIRECTOR, ticketId, 2,
                "GM 回复正文", "reply-1");
        assertThat(replied.ok()).isTrue();
        assertThat(replied.revision()).isEqualTo(3);
        Ticket resolved = service.getForGm(ticketId).orElseThrow();
        assertThat(resolved.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(resolved.claimedBy()).isEqualTo("gm-1");
        assertThat(resolved.replies()).singleElement()
                .satisfies(reply -> assertThat(reply.body()).isEqualTo("GM 回复正文"));
        assertThat(service.reply(SUPERVISOR, ticketId, 3, "again", "reply-again").code())
                .isEqualTo(TicketService.INVALID_STATUS);

        TicketCommandResult closed = service.close(SUPERVISOR, ticketId, 3,
                "已处理完毕", "close-1");
        assertThat(closed.ok()).isTrue();
        assertThat(closed.revision()).isEqualTo(4);
        Ticket closedTicket = service.getForGm(ticketId).orElseThrow();
        assertThat(closedTicket.status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(closedTicket.closedBy()).isEqualTo("supervisor-1");
        assertThat(closedTicket.closeReason()).isEqualTo("已处理完毕");
        assertThat(service.close(GM, ticketId, 4, "again", "close-again").code())
                .isEqualTo(TicketService.INVALID_STATUS);

        // OPEN 也可直接关闭。
        String openTicket = service.submit(PLAYER_A, TicketType.SETTING_QUERY,
                "直接关闭", "正文", "direct-close-submit").ticketId();
        assertThat(service.close(GM, openTicket, 1, "无需处理", "direct-close").ok())
                .isTrue();
    }

    @Test
    void successfulIdempotencyReplaysAndDifferentRequestsConflictWithoutMutation() {
        TicketService service = service();
        TicketCommandResult first = service.submit(PLAYER_A, TicketType.SETTING_QUERY,
                "原始标题", "原始正文", "idem-submit");
        TicketSnapshot afterSubmit = service.snapshot();

        // 同 key 同请求：成功重放。
        assertThat(service.submit(PLAYER_A, TicketType.SETTING_QUERY,
                "原始标题", "原始正文", "idem-submit")).isEqualTo(first);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);

        // 同 key 不同正文 / 类型 / 命令：冲突且零变更。
        TicketCommandResult bodyConflict = service.submit(PLAYER_A, TicketType.SETTING_QUERY,
                "原始标题", "完全不同正文", "idem-submit");
        assertThat(bodyConflict.ok()).isFalse();
        assertThat(bodyConflict.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);
        TicketCommandResult typeConflict = service.submit(PLAYER_A, TicketType.ACTION_DRAFT,
                "原始标题", "原始正文", "idem-submit");
        assertThat(typeConflict.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);
        TicketCommandResult commandConflict = service.claim(
                GM, first.ticketId(), 1, "idem-submit");
        assertThat(commandConflict.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);

        // 失败不缓存：错误 revision 失败后，同 key 正确请求可成功。
        assertThat(service.claim(GM, first.ticketId(), 99, "idem-claim").code())
                .isEqualTo(TicketService.REVISION_CONFLICT);
        TicketCommandResult claim = service.claim(GM, first.ticketId(), 1, "idem-claim");
        assertThat(claim.ok()).isTrue();
        TicketSnapshot afterClaim = service.snapshot();
        assertThat(service.claim(GM, first.ticketId(), 1, "idem-claim")).isEqualTo(claim);
        assertThat(service.snapshot()).isEqualTo(afterClaim);
        assertThat(service.claim(GM, first.ticketId(), 2, "idem-claim").code())
                .isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterClaim);

        TicketCommandResult replied = service.reply(GM, first.ticketId(), 2,
                "第一次回复", "idem-reply");
        assertThat(replied.ok()).isTrue();
        TicketSnapshot afterReply = service.snapshot();
        assertThat(service.reply(GM, first.ticketId(), 2,
                "第一次回复", "idem-reply")).isEqualTo(replied);
        assertThat(service.snapshot()).isEqualTo(afterReply);
        assertThat(service.reply(GM, first.ticketId(), 2,
                "不同回复", "idem-reply").code())
                .isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterReply);
        assertThat(service.getForGm(first.ticketId()).orElseThrow().replies()).hasSize(1);

        TicketCommandResult closed = service.close(GM, first.ticketId(), 3,
                "关闭原因", "idem-close");
        assertThat(closed.ok()).isTrue();
        TicketSnapshot afterClose = service.snapshot();
        assertThat(service.close(GM, first.ticketId(), 3,
                "关闭原因", "idem-close")).isEqualTo(closed);
        assertThat(service.snapshot()).isEqualTo(afterClose);
        assertThat(service.close(GM, first.ticketId(), 3,
                "不同原因", "idem-close").code())
                .isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterClose);

        TicketCommandResult note = service.createGmNote(GM, "备注标题", "备注正文",
                "idem-note");
        assertThat(note.ok()).isTrue();
        TicketSnapshot afterNote = service.snapshot();
        assertThat(service.createGmNote(GM, "备注标题", "备注正文", "idem-note"))
                .isEqualTo(note);
        assertThat(service.snapshot()).isEqualTo(afterNote);
        assertThat(service.createGmNote(GM, "另一标题", "另一正文", "idem-note").code())
                .isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterNote);
        assertThat(service.snapshot().tickets().stream()
                .filter(ticket -> ticket.type() == TicketType.GM_NOTE).count()).isEqualTo(1);
    }

    @Test
    void legacySnapshotWithoutFingerprintConflictsInsteadOfBlindReplay() {
        TicketCommandResult legacyResult = TicketCommandResult.success("legacy-ticket", 1L);
        TicketSnapshot legacy = new TicketSnapshot(List.of(),
                Map.of("legacy-key", legacyResult));
        TicketService service = new TicketService(new InMemoryTicketStore(legacy), CLOCK);
        TicketSnapshot before = service.snapshot();

        TicketCommandResult result = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "legacy 标题", "legacy 正文", "legacy-key");
        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(before);
        assertThat(service.snapshot().tickets()).isEmpty();
    }

    @Test
    void fingerprintIncludesActorAndAuthorizationPrecedesIdempotencyLookup() {
        TicketService service = service();
        TicketCommandResult first = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "actor 幂等标题", "actor 幂等正文", "actor-key");
        TicketSnapshot afterSubmit = service.snapshot();
        assertThat(service.submit(PLAYER_A, TicketType.COMPLAINT,
                "actor 幂等标题", "actor 幂等正文", "actor-key")).isEqualTo(first);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);

        // 异 actor 同 key 同请求：不得返回 A 的 ticketId。
        TicketCommandResult otherActor = service.submit(PLAYER_B, TicketType.COMPLAINT,
                "actor 幂等标题", "actor 幂等正文", "actor-key");
        assertThat(otherActor.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(otherActor.ticketId()).isNotEqualTo(first.ticketId());
        assertThat(service.snapshot()).isEqualTo(afterSubmit);
        TicketCommandResult crossRoleNote = service.createGmNote(GM,
                "actor 幂等标题", "actor 幂等正文", "actor-key");
        assertThat(crossRoleNote.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);

        // 鉴权必须先于幂等回执：PLAYER 调 GM_NOTE / null actor，均 FORBIDDEN 而非 replay。
        assertThat(service.createGmNote(PLAYER_A, "t", "b", "actor-key").code())
                .isEqualTo(TicketService.FORBIDDEN);
        assertThat(service.submit(null, TicketType.COMPLAINT, "t", "b", "actor-key").code())
                .isEqualTo(TicketService.FORBIDDEN);
        assertThat(service.claim(PLAYER_A, first.ticketId(), 1, "player-claim").code())
                .isEqualTo(TicketService.FORBIDDEN);
        assertThat(service.snapshot()).isEqualTo(afterSubmit);

        // 同 actor 同请求 claim 成功重放；异 actor 复用同 key + 同参数冲突。
        TicketCommandResult claimed = service.claim(GM, first.ticketId(), 1, "claim-actor-key");
        assertThat(claimed.ok()).isTrue();
        TicketSnapshot afterClaim = service.snapshot();
        assertThat(service.claim(GM, first.ticketId(), 1, "claim-actor-key"))
                .isEqualTo(claimed);
        assertThat(service.snapshot()).isEqualTo(afterClaim);
        TicketCommandResult directorReplay = service.claim(DIRECTOR,
                first.ticketId(), 1, "claim-actor-key");
        assertThat(directorReplay.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(directorReplay.ticketId()).isNotEqualTo(first.ticketId());
        assertThat(service.snapshot()).isEqualTo(afterClaim);
    }

    @Test
    void batchReplyIsAllOrNothingAndSuccessfulBatchIsIdempotent() {
        TicketService service = service();
        String t1 = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "批量一", "正文一", "batch-submit-1").ticketId();
        String t2 = service.submit(PLAYER_B, TicketType.ACTION_DRAFT,
                "批量二", "正文二", "batch-submit-2").ticketId();

        TicketCommandResult batch = service.batchReply(GM, List.of(
                new TicketService.BatchReplyItem(t1, 1, "批量回复一"),
                new TicketService.BatchReplyItem(t2, 1, "批量回复二")), "batch-1");
        assertThat(batch.ok()).as(batch.toString()).isTrue();
        assertThat(service.getForGm(t1).orElseThrow().status())
                .isEqualTo(TicketStatus.RESOLVED);
        assertThat(service.getForGm(t2).orElseThrow().status())
                .isEqualTo(TicketStatus.RESOLVED);
        assertThat(service.getForGm(t1).orElseThrow().replies()).hasSize(1);
        assertThat(service.getForGm(t2).orElseThrow().replies()).hasSize(1);

        // 同内容不同顺序：fingerprint 规范化后可重放。
        TicketSnapshot afterBatch = service.snapshot();
        TicketCommandResult replay = service.batchReply(GM, List.of(
                new TicketService.BatchReplyItem(t2, 1, "批量回复二"),
                new TicketService.BatchReplyItem(t1, 1, "批量回复一")), "batch-1");
        assertThat(replay).isEqualTo(batch);
        assertThat(service.snapshot()).isEqualTo(afterBatch);
        assertThat(service.getForGm(t1).orElseThrow().replies()).hasSize(1);

        // 任一 item 内容不同：同 key 冲突且零变更。
        TicketCommandResult itemConflict = service.batchReply(GM, List.of(
                new TicketService.BatchReplyItem(t1, 1, "批量回复一"),
                new TicketService.BatchReplyItem(t2, 1, "不同回复二")), "batch-1");
        assertThat(itemConflict.ok()).isFalse();
        assertThat(itemConflict.code()).isEqualTo(TicketService.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(service.snapshot()).isEqualTo(afterBatch);
        assertThat(service.getForGm(t2).orElseThrow().replies()).hasSize(1);

        String t3 = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "原子三", "正文三", "batch-submit-3").ticketId();
        String t4 = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "原子四", "正文四", "batch-submit-4").ticketId();
        assertThat(service.claim(GM, t3, 1, "batch-claim-3").ok()).isTrue();

        TicketCommandResult failed = service.batchReply(GM, List.of(
                new TicketService.BatchReplyItem(t3, 1, "stale"),
                new TicketService.BatchReplyItem(t4, 1, "valid")), "batch-failed");
        assertThat(failed.ok()).isFalse();
        assertThat(failed.code()).isEqualTo(TicketService.REVISION_CONFLICT);
        assertThat(service.getForGm(t4).orElseThrow().status())
                .isEqualTo(TicketStatus.OPEN);
        assertThat(service.getForGm(t4).orElseThrow().revision()).isEqualTo(1);
        assertThat(service.getForGm(t4).orElseThrow().replies()).isEmpty();

        // 失败不缓存：同 key 以正确 revision 重试可成功。
        TicketCommandResult retried = service.batchReply(GM, List.of(
                new TicketService.BatchReplyItem(t3, 2, "corrected"),
                new TicketService.BatchReplyItem(t4, 1, "valid")), "batch-failed");
        assertThat(retried.ok()).isTrue();
        assertThat(service.getForGm(t3).orElseThrow().status())
                .isEqualTo(TicketStatus.RESOLVED);
        assertThat(service.getForGm(t4).orElseThrow().status())
                .isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    void storeFailureDoesNotAdvanceMemoryState() {
        TicketStore failingStore = new TicketStore() {
            @Override
            public TicketSnapshot load() {
                return TicketSnapshot.empty();
            }

            @Override
            public void save(TicketSnapshot snapshot) {
                throw new IllegalStateException("模拟工单存储失败");
            }
        };
        TicketService service = new TicketService(failingStore, CLOCK);
        TicketSnapshot before = service.snapshot();
        TicketCommandResult result = service.submit(PLAYER_A, TicketType.COMPLAINT,
                "失败标题", "失败正文", "store-failure");
        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo(TicketService.TICKET_STORE_UNAVAILABLE);
        assertThat(service.snapshot()).isEqualTo(before);
        assertThat(service.snapshot().tickets()).isEmpty();
    }

    private static TicketService service() {
        return new TicketService(new InMemoryTicketStore(), CLOCK);
    }
}
