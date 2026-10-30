package io.mosire.iibot.app.reply;

import io.mosire.iibot.app.delivery.OutboxSender;
import io.mosire.iibot.app.delivery.SendOutcome;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.ChatType;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.reply.BotReplyPort;
import io.mosire.iibot.bot.reply.BotReplySendResult;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.iibot.connect.core.OutboundSegment;
import io.mosire.iibot.connect.ops.OpsLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 bot core 的 thread reply 适配到按账号划分的 {@link OutboxSender}。
 *
 * <p>出站目标只由 {@link ChatSourceKey} 决定：先用 {@link ChatSourceKey#accountId()} 选择
 * 该账号 sender，再按 chatType/targetId 发送。找不到账号时返回稳定的
 * {@code ACCOUNT_UNAVAILABLE} 与 {@code FAILED_BEFORE_SEND}，绝不借用其它账号。</p>
 *
 * <p>单 sender 构造器仅保留给单账号兼容测试/旧调用；生产多账号组合根必须使用 Map
 * 构造器。每个 sender 仍保留自己的最小发送间隔与三态/UNKNOWN 语义。</p>
 */
public final class BotOutboxReplyPort implements BotReplyPort {

    public static final String ACCOUNT_UNAVAILABLE = "ACCOUNT_UNAVAILABLE";

    private final Map<String, OutboxSender> outboxes;
    private final OutboxSender legacySingleOutbox;
    private final BotMediaStore mediaStore;

    /**
     * 单账号兼容构造器。生产多账号路径应使用 {@link #BotOutboxReplyPort(Map)}。
     */
    public BotOutboxReplyPort(OutboxSender outbox) {
        this(outbox, null);
    }

    public BotOutboxReplyPort(OutboxSender outbox, BotMediaStore mediaStore) {
        this.outboxes = Map.of();
        this.legacySingleOutbox = Objects.requireNonNull(outbox, "outbox");
        this.mediaStore = mediaStore;
    }

    /**
     * 按稳定 accountId 路由到各自 sender。
     *
     * @param outboxes accountId 到 sender 的不可变映射；允许为空以表达所有账号均不可用
     */
    public BotOutboxReplyPort(Map<String, OutboxSender> outboxes) {
        this(outboxes, null);
    }

    public BotOutboxReplyPort(Map<String, OutboxSender> outboxes,
                              BotMediaStore mediaStore) {
        Objects.requireNonNull(outboxes, "outboxes");
        Map<String, OutboxSender> copy = new LinkedHashMap<>();
        outboxes.forEach((accountId, sender) -> {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("outboxes accountId 不能为空");
            }
            copy.put(accountId.trim(), Objects.requireNonNull(sender, "outbox"));
        });
        this.outboxes = Map.copyOf(copy);
        this.legacySingleOutbox = null;
        this.mediaStore = mediaStore;
    }

    @Override
    public BotReplySendResult send(ChatSourceKey source, String text, String replyToMessageId,
                                   String idempotencyKey) {
        return sendWithMention(source, text, replyToMessageId, "", idempotencyKey);
    }

    @Override
    public BotReplySendResult sendWithMention(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, String idempotencyKey) {
        return sendWithMentions(source, text, replyToMessageId, mentionQqId,
                false, idempotencyKey);
    }

    @Override
    public BotReplySendResult sendWithMentions(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, boolean mentionAll, String idempotencyKey) {
        Objects.requireNonNull(source, "source");
        boolean hasMention = mentionQqId != null && !mentionQqId.isBlank();
        if (mentionAll && hasMention) {
            return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND,
                    List.of(), "INVALID_ARGUMENTS");
        }
        if (mentionAll && source.chatType() != ChatType.GROUP) {
            return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND,
                    List.of(), "MENTION_ALL_REQUIRES_GROUP");
        }
        Optional<OutboxSender> selected = outboxFor(source.accountId());
        if (selected.isEmpty()) {
            OpsLog.warn("event=outbound_route_unavailable accountId={} chatType={} "
                            + "targetId={} code={}",
                    source.accountId(), source.chatType(), source.targetId(),
                    ACCOUNT_UNAVAILABLE);
            return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND,
                    List.of(), ACCOUNT_UNAVAILABLE);
        }
        OutboxSender outbox = selected.get();
        String correlationKey = "bot-reply:" + requireText(idempotencyKey, "idempotencyKey");
        boolean hasReply = replyToMessageId != null && !replyToMessageId.isBlank();
        if (source.chatType() == ChatType.EXTERNAL) {
            if (hasMention || mentionAll) {
                return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND,
                        List.of(), "MENTION_NOT_SUPPORTED");
            }
            List<OutboundSegment> externalSegments = new ArrayList<>();
            if (hasReply) {
                externalSegments.add(new OutboundSegment.Reply(replyToMessageId.trim()));
            }
            externalSegments.add(new OutboundSegment.Text(requireText(text, "text")));
            return map(outbox.sendToSource(source.sourceAddress(), externalSegments,
                    correlationKey));
        }
        if (!hasReply && !hasMention && !mentionAll) {
            return map(source.chatType() == ChatType.PRIVATE
                    ? outbox.sendPrivateToUser(source.targetId(), List.of(text), correlationKey)
                    : outbox.broadcastToPublicGroup(
                            source.targetId(), List.of(text), correlationKey));
        }

        List<OutboundSegment> segments = new ArrayList<>();
        if (hasReply) {
            segments.add(new OutboundSegment.Reply(replyToMessageId.trim()));
        }
        if (mentionAll) {
            segments.add(new OutboundSegment.MentionAll());
        } else if (hasMention) {
            segments.add(new OutboundSegment.Mention(mentionQqId.trim()));
        }
        segments.add(new OutboundSegment.Text(requireText(text, "text")));
        return map(source.chatType() == ChatType.PRIVATE
                ? outbox.sendRichPrivateToUser(source.targetId(), segments, correlationKey)
                : outbox.sendRichGroup(source.targetId(), segments, correlationKey));
    }

    @Override
    public BotReplySendResult sendRich(
            ChatSourceKey source, String text, String replyToMessageId,
            String mentionQqId, boolean mentionAll, List<OutboundSegment> attachments,
            String idempotencyKey) {
        Objects.requireNonNull(source, "source");
        if (attachments == null || attachments.isEmpty()) {
            return sendWithMentions(source, text, replyToMessageId, mentionQqId,
                    mentionAll, idempotencyKey);
        }
        boolean hasMention = mentionQqId != null && !mentionQqId.isBlank();
        if (source.chatType() == ChatType.EXTERNAL) {
            return richFailure("EXTERNAL_ATTACHMENTS_NOT_SUPPORTED");
        }
        if (mentionAll && hasMention) {
            return richFailure("INVALID_ARGUMENTS");
        }
        if (mentionAll && source.chatType() != ChatType.GROUP) {
            return richFailure("MENTION_ALL_REQUIRES_GROUP");
        }
        Optional<OutboxSender> selected = outboxFor(source.accountId());
        if (selected.isEmpty()) {
            OpsLog.warn("event=outbound_route_unavailable accountId={} chatType={} "
                            + "targetId={} code={}", source.accountId(), source.chatType(),
                    source.targetId(), ACCOUNT_UNAVAILABLE);
            return richFailure(ACCOUNT_UNAVAILABLE);
        }

        List<OutboundSegment> segments = new ArrayList<>();
        boolean hasReply = replyToMessageId != null && !replyToMessageId.isBlank();
        if (hasReply) {
            segments.add(new OutboundSegment.Reply(replyToMessageId.trim()));
        }
        if (mentionAll) {
            segments.add(new OutboundSegment.MentionAll());
        } else if (hasMention) {
            segments.add(new OutboundSegment.Mention(mentionQqId.trim()));
        }
        if (text != null && !text.isBlank()) {
            segments.add(new OutboundSegment.Text(text.trim()));
        }
        try {
            for (OutboundSegment attachment : attachments) {
                segments.add(resolveAttachment(attachment));
            }
        } catch (AttachmentException failure) {
            return richFailure(failure.code);
        }
        if (segments.isEmpty()) {
            return richFailure("OUTBOX_BLANK_TEXT");
        }

        OutboxSender outbox = selected.get();
        String correlationKey = "bot-reply:" + requireText(idempotencyKey, "idempotencyKey");
        return map(source.chatType() == ChatType.PRIVATE
                ? outbox.sendRichPrivateToUser(source.targetId(), segments, correlationKey)
                : outbox.sendRichGroup(source.targetId(), segments, correlationKey));
    }

    private OutboundSegment resolveAttachment(OutboundSegment attachment) {
        if (attachment instanceof OutboundSegment.Sticker sticker) {
            return sticker;
        }
        if (attachment instanceof OutboundSegment.Image image) {
            if (mediaStore == null) {
                throw new AttachmentException("ASSET_STORE_UNAVAILABLE");
            }
            ToolAsset asset = mediaStore.resolve(image.assetRef()).orElseThrow(() ->
                    new AttachmentException("ASSET_NOT_FOUND"));
            if (!asset.mediaType().toLowerCase(java.util.Locale.ROOT)
                    .startsWith("image/")) {
                throw new AttachmentException("ASSET_NOT_IMAGE");
            }
            return new OutboundSegment.Image(image.assetRef(), image.fileName(),
                    asset.mediaType(), asset.bytes());
        }
        throw new AttachmentException("UNSUPPORTED_ATTACHMENT");
    }

    private static BotReplySendResult richFailure(String code) {
        return new BotReplySendResult(DeliveryState.FAILED_BEFORE_SEND, List.of(), code);
    }

    private static final class AttachmentException extends RuntimeException {
        private final String code;

        private AttachmentException(String code) {
            super(code);
            this.code = code;
        }
    }

    private Optional<OutboxSender> outboxFor(String accountId) {
        if (legacySingleOutbox != null) {
            return Optional.of(legacySingleOutbox);
        }
        return Optional.ofNullable(outboxes.get(accountId));
    }

    private static BotReplySendResult map(List<SendOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return new BotReplySendResult(DeliveryState.UNKNOWN, List.of(),
                    "OUTBOX_EMPTY_RESULT");
        }
        SendOutcome outcome = outcomes.getFirst();
        DeliveryState state = switch (outcome.state()) {
            case SENT -> DeliveryState.SENT;
            case FAILED_BEFORE_SEND -> DeliveryState.FAILED_BEFORE_SEND;
            case UNKNOWN -> DeliveryState.UNKNOWN;
        };
        return new BotReplySendResult(state, outcome.adapterMessageIds(),
                outcome.errorCode() == null ? "" : outcome.errorCode());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
