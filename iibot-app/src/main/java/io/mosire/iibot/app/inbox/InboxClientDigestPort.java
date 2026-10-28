package io.mosire.iibot.app.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.inbox.client.InboxClient;
import io.mosire.inbox.client.InboxClientException;
import io.mosire.inbox.client.InboxModels;
import io.mosire.iibot.bot.inbox.InboxDigest;
import io.mosire.iibot.bot.inbox.InboxDigestConflictException;
import io.mosire.iibot.bot.inbox.InboxDigestException;
import io.mosire.iibot.bot.inbox.InboxDigestItem;
import io.mosire.iibot.bot.inbox.InboxDigestPort;
import io.mosire.iibot.connect.ops.OpsLog;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 把 bot core 的 {@link InboxDigest} 适配到 InboxMosire 的 summary/content item。
 *
 * <p>Inbox 只保存 bounded digest；content 是 digests 的 JSON 字符串，完整消息正文仍在
 * bot 消息板。该适配器不记录 token 或消息正文日志。</p>
 */
public final class InboxClientDigestPort implements InboxDigestPort {

    public static final String DEFAULT_CHANNEL = "qq";
    public static final String DIGEST_KIND = "digest";

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final InboxClient client;
    private final String inboxId;
    private final String channel;

    public InboxClientDigestPort(InboxClient client, String inboxId) {
        this(client, inboxId, DEFAULT_CHANNEL);
    }

    public InboxClientDigestPort(InboxClient client, String inboxId, String channel) {
        this.client = Objects.requireNonNull(client, "client");
        this.inboxId = requireText(inboxId, "inboxId");
        this.channel = requireText(channel, "channel");
    }

    @Override
    public InboxDigestItem push(String dedupeKey, InboxDigest digest) {
        Objects.requireNonNull(digest, "digest");
        try {
            InboxModels.PushResult result = client.push(inboxId, InboxModels.PushItem
                    .builder(channel)
                    .kind(DIGEST_KIND)
                    .summary(digest.summary())
                    .content(contentJson(digest))
                    .contentRef(contentRef(digest))
                    .dedupeKey(requireText(dedupeKey, "dedupeKey"))
                    .priority(digest.priority())
                    .build());
            if (result == null || result.itemId() == null || result.itemId().isBlank()) {
                throw new IllegalStateException("Inbox push 未返回 itemId");
            }
            OpsLog.debug("event=inbox_client_call action=push threadId={} itemId={} "
                            + "revision={}", digest.threadId(), result.itemId(),
                    result.revision());
            return new InboxDigestItem(result.itemId(), result.revision(), "available",
                    digest.toSeq(), digest.fromSeq(), digest.priority(), digest);
        } catch (RuntimeException failure) {
            RuntimeException mapped = mapFailure(failure);
            OpsLog.warn("event=inbox_client_call action=push threadId={} outcome=error code={}",
                    digest.threadId(), errorCode(mapped));
            throw mapped;
        }
    }

    @Override
    public Optional<InboxDigestItem> view(String itemId) {
        try {
            InboxModels.ItemDetail detail = client.view(inboxId, requireText(itemId, "itemId"));
            OpsLog.debug("event=inbox_client_call action=view itemId={} status={} "
                            + "revision={}", detail.itemId(), detail.status(), detail.revision());
            return Optional.of(new InboxDigestItem(
                    detail.itemId(), detail.revision(), detail.status(),
                    toSeq(detail.content()), fromSeq(detail.content()), detail.priority(),
                    parseDigest(detail.content())));
        } catch (RuntimeException failure) {
            if (isNotFound(failure)) {
                return Optional.empty();
            }
            RuntimeException mapped = mapFailure(failure);
            OpsLog.warn("event=inbox_client_call action=view itemId={} outcome=error code={}",
                    itemId, errorCode(mapped));
            throw mapped;
        }
    }

    @Override
    public InboxDigestItem update(String itemId, long expectedRevision, InboxDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (expectedRevision <= 0L) {
            throw new IllegalArgumentException("expectedRevision 必须为正");
        }
        try {
            InboxModels.ItemMutation mutation = client.update(inboxId,
                    requireText(itemId, "itemId"),
                    new InboxModels.UpdateItem(
                            digest.summary(),
                            contentJson(digest),
                            contentRef(digest),
                            DIGEST_KIND,
                            digest.priority(),
                            expectedRevision));
            OpsLog.debug("event=inbox_client_call action=update threadId={} itemId={} "
                            + "revision={}", digest.threadId(), mutation.itemId(),
                    mutation.revision());
            return new InboxDigestItem(mutation.itemId(), mutation.revision(), "available",
                    digest.toSeq(), digest.fromSeq(), digest.priority(), digest);
        } catch (RuntimeException failure) {
            RuntimeException mapped = mapFailure(failure);
            OpsLog.warn("event=inbox_client_call action=update threadId={} itemId={} "
                            + "outcome=error code={}", digest.threadId(), itemId,
                    errorCode(mapped));
            throw mapped;
        }
    }

    @Override
    public long updatePriority(String itemId, long expectedRevision, int priority) {
        if (expectedRevision <= 0L) {
            throw new IllegalArgumentException("expectedRevision 必须为正");
        }
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
        try {
            InboxModels.ItemMutation mutation = client.update(inboxId,
                    requireText(itemId, "itemId"),
                    new InboxModels.UpdateItem(null, null, null, null,
                            priority, expectedRevision));
            OpsLog.debug("event=inbox_client_call action=update_priority itemId={} "
                            + "revision={}", itemId, mutation.revision());
            return mutation.revision();
        } catch (RuntimeException failure) {
            RuntimeException mapped = mapFailure(failure);
            OpsLog.warn("event=inbox_client_call action=update_priority itemId={} "
                            + "outcome=error code={}", itemId, errorCode(mapped));
            throw mapped;
        }
    }

    @Override
    public void archive(String itemId, long expectedRevision) {
        if (expectedRevision <= 0L) {
            throw new IllegalArgumentException("expectedRevision 必须为正");
        }
        try {
            client.deleteItem(inboxId, requireText(itemId, "itemId"), expectedRevision);
            OpsLog.debug("event=inbox_client_call action=archive itemId={} revision={}",
                    itemId, expectedRevision);
        } catch (RuntimeException failure) {
            RuntimeException mapped = mapFailure(failure);
            OpsLog.warn("event=inbox_client_call action=archive itemId={} outcome=error "
                            + "code={}", itemId, errorCode(mapped));
            throw mapped;
        }
    }

    private static String contentJson(InboxDigest digest) {
        try {
            return JSON.writeValueAsString(digest);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Inbox digest content 序列化失败", failure);
        }
    }

    private static String contentRef(InboxDigest digest) {
        return "message-board:" + digest.threadId();
    }

    private static long toSeq(Object content) {
        return sequence(content, "toSeq");
    }

    private static long fromSeq(Object content) {
        return sequence(content, "fromSeq");
    }

    /**
     * Parse the opaque item content back into the bounded digest projection.
     * Legacy/foreign content is tolerated as {@code null}; callers then keep the
     * old conservative behavior.
     *
     * @param content - JSON node/string returned by Inbox.
     * @returns digest projection or null when the content is not a digest.
     */
    private static InboxDigest parseDigest(Object content) {
        if (content == null) return null;
        try {
            if (content instanceof JsonNode node && node.isObject()) {
                return JSON.treeToValue(node, InboxDigest.class);
            }
            if (content instanceof String text && !text.isBlank()) {
                return JSON.readValue(text, InboxDigest.class);
            }
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Legacy/foreign/incompatible content is not a usable digest.
        }
        return null;
    }

    private static long sequence(Object content, String field) {
        if (content == null) {
            return 0L;
        }
        try {
            if (content instanceof JsonNode node && node.isObject()) {
                return Math.max(0L, node.path(field).asLong(0L));
            }
            if (content instanceof String text && !text.isBlank()) {
                return Math.max(0L, JSON.readTree(text).path(field).asLong(0L));
            }
        } catch (JsonProcessingException ignored) {
            // 内容不是本程序的 digest JSON；游标保持未知，由 state 兜底。
        }
        return 0L;
    }

    private static String errorCode(RuntimeException failure) {
        return failure instanceof InboxDigestException digestFailure
                ? digestFailure.code() : failure.getClass().getSimpleName();
    }

    private static boolean isNotFound(Throwable failure) {
        return failure instanceof InboxClientException clientFailure
                && clientFailure.statusCode() == 404;
    }

    private static RuntimeException mapFailure(RuntimeException failure) {
        if (failure instanceof InboxDigestException digestFailure) {
            return digestFailure;
        }
        if (failure instanceof InboxClientException clientFailure) {
            String code = clientFailure.code() == null
                    ? "INBOX_CLIENT_ERROR" : clientFailure.code();
            Map<String, Object> details = clientFailure.details();
            if (clientFailure.statusCode() == 409 || isConflictCode(code)) {
                String conflictCode = "ITEM_STATUS_CONFLICT".equals(code)
                        || "ITEM_REVISION_CONFLICT".equals(code)
                        ? code : "INBOX_CONFLICT";
                return new InboxDigestConflictException(conflictCode,
                        clientFailure.getMessage(), details, failure);
            }
            if (clientFailure.statusCode() == 401 || clientFailure.statusCode() == 403) {
                return new InboxDigestException("INBOX_AUTH",
                        "Inbox digest 鉴权失败", details, failure);
            }
            if (clientFailure.statusCode() == 404) {
                return new InboxDigestException("ITEM_NOT_FOUND",
                        "Inbox item 不存在或已归档", details, failure);
            }
            if (clientFailure.statusCode() == 0) {
                return new InboxDigestException("INBOX_NETWORK",
                        "Inbox digest 网络请求失败", details, failure);
            }
            return new InboxDigestException("INBOX_UNAVAILABLE",
                    "Inbox digest 请求失败 code=" + code, details, failure);
        }
        return new InboxDigestException("INBOX_INTERNAL",
                "Inbox digest 操作失败（内部原因已脱敏）", Map.of(), failure);
    }

    private static boolean isConflictCode(String code) {
        return "CONFLICT".equalsIgnoreCase(code)
                || "ITEM_STATUS_CONFLICT".equalsIgnoreCase(code)
                || "ITEM_REVISION_CONFLICT".equalsIgnoreCase(code);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
