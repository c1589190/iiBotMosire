package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.MessagingAdapter;
import io.mosire.iibot.connect.core.OutboundMessage;
import io.mosire.iibot.connect.core.OutboundMessage.TargetType;
import io.mosire.iibot.connect.core.SendReceipt;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code qq_send_message}：通过标准 {@link MessagingAdapter} 发送一条纯文本消息。
 *
 * <p>工具为 DEFAULT 级、{@code sensitive=true}、{@code destructive=false}。发送前必须通过宿主注入的
 * {@code ResourceAuthorizer} 获得 {@code qq:<adapterId>/<targetType>:<targetId>} 写权限。调用会等待
 * 适配器回执，但等待时间有上限；超时、线程中断或适配器异常完成一律映射为 {@code SEND_UNKNOWN}，明确告知
 * 消息可能已发出，不应自动重发。</p>
 */
final class QqSendMessageTool extends AbstractQqTool {

    static final String NAME = "qq_send_message";
    static final String SEND_FAILED = "SEND_FAILED";
    static final String SEND_UNKNOWN = "SEND_UNKNOWN";

    private static final String UNKNOWN_ADVICE =
            "发送状态未知（UNKNOWN）：消息可能已到达目标，不应自动重发；应先人工核对，或在确认幂等语义后用同一幂等键处理。";
    private static final String FAILED_ADVICE =
            "发送失败（FAILED_BEFORE_SEND）：请求在发出前失败，未产生新的外部消息；修正原因后可重试。";

    private final AdapterRegistry adapters;
    private final Duration sendTimeout;

    QqSendMessageTool(AdapterRegistry adapters, Set<String> roles, Duration sendTimeout) {
        super(ToolSpec.level(AccessToken.DEFAULT, true, false), roles);
        this.adapters = adapters;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向指定 QQ 私聊或群聊发送纯文本消息，并等待三态发送回执。"
                + "该操作敏感但非破坏性；UNKNOWN 表示消息可能已经发出，不应自动重发。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "description", "发送一条纯文本 QQ 消息并等待回执。",
                "properties", Map.of(
                        "adapterId", Map.of(
                                "type", "string",
                                "description", "qq_list_adapters 返回的适配器 id"),
                        "targetType", Map.of(
                                "type", "string",
                                "enum", List.of("PRIVATE", "GROUP"),
                                "description", "PRIVATE 表示私聊，GROUP 表示群聊"),
                        "targetId", Map.of(
                                "type", "string",
                                "description", "私聊用户 id 或群 id"),
                        "text", Map.of(
                                "type", "string",
                                "description", "要发送的纯文本，不解释 CQ Code"),
                        "idempotencyKey", Map.of(
                                "type", "string",
                                "description", "可选幂等键；缺省时由工具生成 UUID")),
                "required", List.of("adapterId", "targetType", "targetId", "text"),
                "additionalProperties", false);
    }

    @Override
    protected ToolResult executeChecked(ToolContext context) {
        String adapterId = ToolArguments.requiredText(context.arguments(), "adapterId");
        String targetTypeText = ToolArguments.requiredText(context.arguments(), "targetType");
        String targetId = ToolArguments.requiredText(context.arguments(), "targetId");
        String text = ToolArguments.requiredText(context.arguments(), "text");

        ToolArguments.requireSafePathIdentifier(adapterId, "adapterId");
        ToolArguments.requireSafePathIdentifier(targetId, "targetId");
        TargetType targetType = parseTargetType(targetTypeText);
        String idempotencyKey = resolveIdempotencyKey(context.arguments());

        Optional<MessagingAdapter> found = adapters.find(adapterId);
        if (found.isEmpty()) {
            return ToolResult.error("ADAPTER_NOT_FOUND", "未找到适配器: " + Redaction.sanitize(adapterId));
        }
        MessagingAdapter adapter = found.get();
        context.resources().require(
                Operation.WRITE, QqResourcePaths.target(adapterId, targetType, targetId));

        OutboundMessage message = new OutboundMessage(
                targetType, targetId, List.of(text), idempotencyKey);
        return sendAndMap(adapter, message);
    }

    private static TargetType parseTargetType(String value) {
        try {
            return TargetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidToolArgumentException("参数 targetType 必须是 PRIVATE 或 GROUP");
        }
    }

    private static String resolveIdempotencyKey(Map<String, Object> arguments) {
        String provided = ToolArguments.optionalText(arguments, "idempotencyKey");
        return provided.isEmpty() ? UUID.randomUUID().toString() : provided;
    }

    private ToolResult sendAndMap(MessagingAdapter adapter, OutboundMessage message) {
        final CompletionStage<SendReceipt> stage;
        try {
            stage = adapter.send(message);
        } catch (RuntimeException exception) {
            return unknown(adapter, message, "SEND_DISPATCH_EXCEPTION", "发送调用异常退出，消息状态未知");
        }
        if (stage == null) {
            return unknown(adapter, message, "NULL_SEND_STAGE", "适配器未返回发送回执，消息状态未知");
        }

        final CompletableFuture<SendReceipt> future;
        try {
            future = stage.toCompletableFuture();
        } catch (RuntimeException exception) {
            return unknown(adapter, message, "SEND_STAGE_INVALID", "适配器发送回执不可等待，消息状态未知");
        }

        final SendReceipt receipt;
        try {
            receipt = future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknown(adapter, message, "INTERRUPTED", "等待发送回执时线程被中断，消息状态未知");
        } catch (TimeoutException timeout) {
            return unknown(adapter, message, "RECEIPT_TIMEOUT", "等待发送回执超时，消息状态未知");
        } catch (ExecutionException | CancellationException exception) {
            return unknown(adapter, message, "SEND_COMPLETION_FAILED", "发送回执异常完成，消息状态未知");
        } catch (RuntimeException exception) {
            return unknown(adapter, message, "SEND_WAIT_FAILED", "等待发送回执失败，消息状态未知");
        }

        if (receipt == null) {
            return unknown(adapter, message, "NULL_RECEIPT", "适配器返回了空回执，消息状态未知");
        }
        return mapReceipt(adapter, message, receipt);
    }

    private static ToolResult mapReceipt(
            MessagingAdapter adapter, OutboundMessage message, SendReceipt receipt) {
        if (receipt.state() == null) {
            return unknown(adapter, message, "NULL_RECEIPT_STATE", "发送回执缺少状态，消息状态未知");
        }
        return switch (receipt.state()) {
            case SENT -> ToolResult.ok(Json.write(resultBody(
                    "SENT", adapter, message, receipt.adapterMessageIds(),
                    receipt.errorCode(), receipt.detail(), "发送成功（SENT）。", receipt.occurredAt())));
            case FAILED_BEFORE_SEND -> ToolResult.error(
                    SEND_FAILED,
                    Json.write(resultBody(
                            "FAILED_BEFORE_SEND", adapter, message,
                            receipt.adapterMessageIds(), receipt.errorCode(), receipt.detail(),
                            FAILED_ADVICE, receipt.occurredAt())));
            case UNKNOWN -> ToolResult.error(
                    SEND_UNKNOWN,
                    Json.write(resultBody(
                            "UNKNOWN", adapter, message,
                            receipt.adapterMessageIds(), receipt.errorCode(), receipt.detail(),
                            UNKNOWN_ADVICE, receipt.occurredAt())));
        };
    }

    private static ToolResult unknown(
            MessagingAdapter adapter, OutboundMessage message, String errorCode, String detail) {
        SendReceipt synthetic = SendReceipt.unknown(errorCode, detail);
        return ToolResult.error(
                SEND_UNKNOWN,
                Json.write(resultBody(
                        "UNKNOWN", adapter, message,
                        synthetic.adapterMessageIds(), synthetic.errorCode(), synthetic.detail(),
                        UNKNOWN_ADVICE, synthetic.occurredAt())));
    }

    private static Map<String, Object> resultBody(
            String state,
            MessagingAdapter adapter,
            OutboundMessage message,
            List<String> messageIds,
            String errorCode,
            String detail,
            String advice,
            java.time.Instant occurredAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("state", state);
        root.put("adapterId", Redaction.sanitize(adapter.adapterId()));
        root.put("targetType", message.targetType().name());
        root.put("targetId", Redaction.sanitize(message.targetId()));
        List<String> safeIds = new ArrayList<>();
        if (messageIds != null) {
            for (String messageId : messageIds) {
                safeIds.add(Redaction.sanitize(messageId));
            }
        }
        root.put("adapterMessageIds", safeIds);
        root.put("errorCode", Redaction.sanitizeCode(errorCode));
        root.put("detail", Redaction.sanitize(detail));
        root.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
        root.put("message", advice);
        return root;
    }
}
