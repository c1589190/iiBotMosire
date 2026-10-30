package io.mosire.iibot.app.reply;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.app.media.BotMediaStore;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.chat.DeliveryState;
import io.mosire.iibot.bot.chat.MessageSourceAddress;
import io.mosire.iibot.bot.store.MessageAppendResult;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.GroupFileUploadCapability;
import io.mosire.iibot.connect.core.MessageSegment;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.ops.OpsLog;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 通过受控资产上传群文件；外部副作用和本地 OUT 三态同源。 */
public final class BotGroupFileUploadTool implements AgentTool {

    public static final String NAME = "qq_group_file_upload";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final long TIMEOUT_SECONDS = 30L;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "群来源地址 qq_group/{account}/{group}"),
                    "assetRef", Map.of("type", "string", "minLength", 1,
                            "description", "qq_media_upload 返回的资产引用"),
                    "fileName", Map.of("type", "string", "minLength", 1,
                            "maxLength", 256),
                    "mediaType", Map.of("type", "string", "maxLength", 128),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1,
                            "description", "可选；省略时服务端生成 UUID")),
            "required", List.of("source", "assetRef", "fileName"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final Map<String, GroupFileUploadCapability> providers;
    private final BotMediaStore mediaStore;
    private final Clock clock;

    public BotGroupFileUploadTool(MessageBoardStore board,
                                  Map<String, GroupFileUploadCapability> providers,
                                  BotMediaStore mediaStore) {
        this(board, providers, mediaStore, Clock.systemUTC());
    }

    public BotGroupFileUploadTool(MessageBoardStore board,
                                  Map<String, GroupFileUploadCapability> providers,
                                  BotMediaStore mediaStore, Clock clock) {
        this.board = Objects.requireNonNull(board, "board");
        Objects.requireNonNull(providers, "providers");
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "把 qq_media_upload 的资产作为群文件上传。先写消息板 OUT/PENDING，再调用"
                + "群文件上传并回写三态；不接受 URL/本地路径，UNKNOWN 不自动重试。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String sourceText = requiredText(context.arguments(), "source", 1024);
            MessageSourceAddress address;
            try {
                address = MessageSourceAddress.parse(sourceText);
            } catch (RuntimeException invalid) {
                return ToolResult.error("INVALID_ARGUMENTS", "source 非法");
            }
            if (!address.isQqGroup()) {
                return ToolResult.error("INVALID_ARGUMENTS",
                        "qq_group_file_upload 只接受 qq_group 来源");
            }
            String accountId = address.accountId();
            String groupId = address.targetId();
            GroupFileUploadCapability provider = providers.get(accountId);
            if (provider == null) {
                return ToolResult.error("ACCOUNT_UNAVAILABLE",
                        "该账号当前不可用或未提供群文件上传能力");
            }
            String assetRef = requiredText(context.arguments(), "assetRef", 128);
            ToolAsset asset = mediaStore.resolve(assetRef).orElse(null);
            if (asset == null) {
                return ToolResult.error("ASSET_NOT_FOUND", "assetRef 不存在或已过期");
            }
            byte[] bytes = asset.bytes();
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                return ToolResult.error("ASSET_TOO_LARGE", "群文件为空或超过 10 MiB");
            }
            String fileName = safeFileName(
                    requiredText(context.arguments(), "fileName", 256));
            String mediaType = optionalText(context.arguments(), "mediaType", 128);
            if (mediaType.isEmpty()) {
                mediaType = asset.mediaType();
            }
            String idempotencyKey = context.arguments().containsKey("idempotencyKey")
                    ? requiredText(context.arguments(), "idempotencyKey", 200)
                    : UUID.randomUUID().toString();
            ChatSourceKey source = ChatSourceKey.groupChat(accountId, groupId);
            ChatMessage candidate = ChatMessage.outbound(source, idempotencyKey,
                    clock.instant(), "", "[文件×1] " + fileName, null);
            var existing = board.find(candidate.messageKey());
            if (existing.isPresent()) {
                return okData(existing.orElseThrow(), true);
            }
            MessageAppendResult appended = board.append(candidate,
                    List.of(MessageSegment.file(0, fileName, assetRef, bytes.length)));
            if (!appended.created()) {
                return okData(appended.message(), true);
            }

            SendReceipt receipt;
            try {
                receipt = provider.uploadGroupFile(groupId, fileName, mediaType,
                                bytes, idempotencyKey)
                        .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                board.updateDelivery(appended.message().messageKey(),
                        DeliveryState.UNKNOWN, null, null);
                return ToolResult.error("FILE_UPLOAD_UNKNOWN",
                        "群文件上传被中断，记录 UNKNOWN");
            } catch (Exception failure) {
                board.updateDelivery(appended.message().messageKey(),
                        DeliveryState.UNKNOWN, null, null);
                return ToolResult.error("FILE_UPLOAD_UNKNOWN",
                        "群文件上传结果未知（内部原因已脱敏）");
            }
            ChatMessage updated = board.updateDelivery(
                    appended.message().messageKey(), map(receipt), null, clock.instant());
            OpsLog.info("event=group_file_upload accountId={} groupId={} state={} code={}",
                    accountId, groupId, receipt.state(), receipt.errorCode());
            return okData(updated, false);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("FILE_UPLOAD_FAILED",
                    "群文件上传暂不可用（内部原因已脱敏）");
        }
    }

    private ToolResult okData(ChatMessage message, boolean alreadyAttempted) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageKey", message.messageKey());
        data.put("source", message.source().sourceAddress().canonical());
        data.put("deliveryState", message.deliveryState().name());
        data.put("idempotencyKey", message.idempotencyKey());
        data.put("alreadyAttempted", alreadyAttempted);
        try {
            return ToolResult.ok(JSON.writeValueAsString(data));
        } catch (Exception failure) {
            return ToolResult.error("FILE_UPLOAD_RESULT_ENCODE_FAILED",
                    "群文件上传结果序列化失败");
        }
    }

    private static DeliveryState map(SendReceipt receipt) {
        return switch (receipt.state()) {
            case SENT -> DeliveryState.SENT;
            case FAILED_BEFORE_SEND -> DeliveryState.FAILED_BEFORE_SEND;
            case UNKNOWN -> DeliveryState.UNKNOWN;
        };
    }

    private static String requiredText(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        String value = text.trim();
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(name + " 超过长度上限");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> args, String name, int maxChars) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(name + " 必须是字符串");
        }
        String value = text.trim();
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(name + " 超过长度上限");
        }
        return value;
    }

    private static String safeFileName(String raw) {
        String name = raw.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        return name;
    }
}
