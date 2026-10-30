package io.mosire.iibot.bot.mcp;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.reply.BotReplyException;
import io.mosire.iibot.bot.reply.BotReplyResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.connect.core.OutboundSegment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 按 thread 回复，目标由宿主反解。 */
public final class BotReplyTool implements AgentTool {

    public static final String NAME = "qq_conversation_send";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "source", Map.of("type", "string", "minLength", 1,
                            "description", "统一来源地址，如 qq_group/bot-1/955432692"),
                    "threadId", Map.of("type", "string", "minLength", 1,
                            "description", "旧版内部 threadId；新调用方使用 source"),
                    "text", Map.of("type", "string", "maxLength", 4000,
                            "description", "正文；有附件时可省略"),
                    "replyToMessageId", Map.of("type", "string"),
                    "mentionSender", Map.of("type", "boolean",
                            "description", "群聊中回复时 @ 被回复消息的发送者"),
                    "mentionAll", Map.of("type", "boolean",
                            "description", "群聊中 @全体成员；不可与 mentionSender 同时为 true"),
                    "attachments", Map.of("type", "array", "maxItems", 9,
                            "description", "image/sticker 附件；file 请使用 qq_group_file_upload",
                            "items", Map.of("type", "object",
                                    "properties", Map.of(
                                            "type", Map.of("type", "string",
                                                    "enum", List.of("image", "sticker")),
                                            "assetRef", Map.of("type", "string"),
                                            "fileName", Map.of("type", "string"),
                                            "mediaType", Map.of("type", "string"),
                                            "faceId", Map.of("type", "string")),
                                    "required", List.of("type"),
                                    "additionalProperties", false)),
                    "idempotencyKey", Map.of("type", "string", "minLength", 1,
                            "description", "可选；仅在调用方需要重试时显式传入。省略时服务端生成 UUID。")),
            "required", List.of(),
            "additionalProperties", false);

    private final BotReplyService service;

    public BotReplyTool(BotReplyService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "回复一个来源 thread；目标由宿主从 threadId 解析，不接受任意 QQ/群号。"
                + "mentionSender=true 时可 @ 被回复消息的发送者；mentionAll=true 时 "
                + "在群聊 @全体成员，两者不可同时使用。attachments 可附带 "
                + "qq_media_upload 得到的 image assetRef 或 sticker faceId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override public Set<String> roles() { return BotToolSupport.roles(); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String idempotencyKey = context.arguments().containsKey("idempotencyKey")
                    ? BotToolSupport.requiredText(
                            context.arguments(), "idempotencyKey", 200)
                    : UUID.randomUUID().toString();
            String threadId = BotToolSupport.threadKey(context.arguments()).threadId();
            List<OutboundSegment> attachments = parseAttachments(
                    context.arguments().get("attachments"));
            BotReplyResult result = service.replyRich(
                    threadId,
                    BotToolSupport.optionalText(context.arguments(), "text", 4000),
                    BotToolSupport.optionalText(context.arguments(), "replyToMessageId", 256),
                    BotToolSupport.optionalBoolean(context.arguments(), "mentionSender", false),
                    BotToolSupport.optionalBoolean(context.arguments(), "mentionAll", false),
                    attachments,
                    idempotencyKey);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageKey", result.message().messageKey());
            data.put("source", result.message().source().sourceAddress().canonical());
            data.put("threadId", result.message().source().threadId());
            data.put("idempotencyKey", result.message().idempotencyKey());
            data.put("deliveryState", result.deliveryState().name());
            data.put("messageIds", result.messageIds());
            data.put("errorCode", result.errorCode());
            data.put("alreadyAttempted", result.alreadyAttempted());
            data.put("attachmentCount", attachments.size());
            return BotToolSupport.ok(NAME, data);
        } catch (BotToolSupport.InvalidArgumentsException invalid) {
            return BotToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (BotReplyException failure) {
            return BotToolSupport.failure(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return BotToolSupport.failure("REPLY_UNAVAILABLE",
                    "回复暂不可用（内部原因已脱敏）");
        }
    }

    private static List<OutboundSegment> parseAttachments(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            throw new BotToolSupport.InvalidArgumentsException("attachments 必须是数组");
        }
        if (values.size() > 9) {
            throw new BotToolSupport.InvalidArgumentsException("attachments 最多 9 项");
        }
        List<OutboundSegment> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "attachments 每项必须是对象");
            }
            String type = attachmentText(item, "type", 32, true);
            if ("image".equals(type)) {
                String assetRef = attachmentText(item, "assetRef", 128, true);
                String fileName = attachmentText(item, "fileName", 256, false);
                String mediaType = attachmentText(item, "mediaType", 128, false);
                result.add(new OutboundSegment.Image(assetRef,
                        fileName.isEmpty() ? "image" : fileName,
                        mediaType.isEmpty() ? "application/octet-stream" : mediaType));
            } else if ("sticker".equals(type)) {
                result.add(new OutboundSegment.Sticker(
                        attachmentText(item, "faceId", 64, true)));
            } else if ("file".equals(type)) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "file 附件请使用 qq_group_file_upload");
            } else {
                throw new BotToolSupport.InvalidArgumentsException(
                        "不支持的 attachment type: " + type);
            }
        }
        return List.copyOf(result);
    }

    private static String attachmentText(Map<?, ?> item, String name, int maxChars,
                                         boolean required) {
        Object raw = item.get(name);
        if (raw == null) {
            if (required) {
                throw new BotToolSupport.InvalidArgumentsException(
                        "attachment." + name + " 不能为空");
            }
            return "";
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new BotToolSupport.InvalidArgumentsException(
                    "attachment." + name + " 必须是非空白字符串");
        }
        String value = text.trim();
        if (value.length() > maxChars) {
            throw new BotToolSupport.InvalidArgumentsException(
                    "attachment." + name + " 超过长度上限");
        }
        return value;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("source", "threadId", "replyToMessageId",
                "mentionSender", "mentionAll", "idempotencyKey")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
