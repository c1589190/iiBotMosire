package io.mosire.iibot.app.bilibili;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.reply.BotReplyException;
import io.mosire.iibot.bot.reply.BotReplyResult;
import io.mosire.iibot.bot.reply.BotReplyService;
import io.mosire.iibot.connect.core.MessageSourceAddress;
import io.mosire.iibot.connect.video.bilibili.BilibiliTarget;
import io.mosire.iibot.connect.video.bilibili.BilibiliTargetResolver;
import io.mosire.iibot.connect.video.core.VideoAccountRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 显式发布 B 站顶层评论或评论树回复；目标解析和写入都走既有 Bot 出站通道。 */
public final class VideoCommentReplyTool implements AgentTool {

    public static final String NAME = "video_comment_reply";
    private static final int MAX_TEXT = 1000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "target", Map.of("type", "string", "minLength", 1,
                            "description", "B站 BV 号或视频 URL；必须能解析出 BV 号"),
                    "accountId", Map.of("type", "string",
                            "description", "B站账号 ID；只有一个 B站账号时可省略"),
                    "rootRpid", Map.of("type", "string", "pattern", "^[0-9]{1,20}$",
                            "description", "回复评论树时提供根评论 rpid；顶层评论省略"),
                    "replyToRpid", Map.of("type", "string", "pattern", "^[0-9]{1,20}$",
                            "description", "回复楼中楼时提供被回复 rpid；必须同时提供 rootRpid"),
                    "text", Map.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT,
                            "description", "评论正文；不接受 HTML 或附件"),
                    "idempotencyKey", Map.of("type", "string", "maxLength", 200,
                            "description", "可选；同一 key 不重复写入")),
            "required", List.of("target", "text"),
            "additionalProperties", false);

    private final List<VideoAccountRef> accounts;
    private final BotReplyService replies;
    private final BilibiliTargetResolver resolver = new BilibiliTargetResolver();

    public VideoCommentReplyTool(List<VideoAccountRef> accounts, BotReplyService replies) {
        this.accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        this.replies = Objects.requireNonNull(replies, "replies");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "显式发布一条 B站视频评论或评论树回复。默认要求 B站账号 writeEnabled=true；"
                + "一次调用只写一条，不自动循环。评论会先写入消息板再经 BilibiliAdapter 发送，"
                + "冷却/每日配额/幂等与 UNKNOWN 语义沿用 Adapter。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override public Set<String> roles() { return Set.of("user", "assistant"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            BilibiliTarget target = resolver.resolve(VideoToolSupport.requiredText(
                    context.arguments(), "target", 2048));
            if (target.bvid().isBlank()) {
                return VideoToolSupport.failure("BILI_INVALID_TARGET",
                        "评论写入只支持可解析出 BV 号的视频目标");
            }
            VideoAccountRef account = selectAccount(context.arguments().get("accountId"));
            String text = VideoToolSupport.requiredText(context.arguments(), "text", MAX_TEXT);
            String root = optionalRpid(context.arguments().get("rootRpid"), "rootRpid");
            String replyTo = optionalRpid(context.arguments().get("replyToRpid"),
                    "replyToRpid");
            if (root.isEmpty() && !replyTo.isEmpty()) {
                return VideoToolSupport.failure("INVALID_ARGUMENTS",
                        "replyToRpid 必须同时提供 rootRpid");
            }
            MessageSourceAddress source = root.isEmpty()
                    ? MessageSourceAddress.biliVideo(account.accountId(), target.bvid())
                    : MessageSourceAddress.biliComment(
                            account.accountId(), target.bvid(), root);
            String key = context.arguments().containsKey("idempotencyKey")
                    ? VideoToolSupport.requiredText(context.arguments(), "idempotencyKey", 200)
                    : UUID.randomUUID().toString();
            BotReplyResult result = replies.sendExternal(source, text, replyTo, key);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", source.canonical());
            data.put("accountId", account.accountId());
            data.put("bvid", target.bvid());
            data.put("rootRpid", root);
            data.put("replyToRpid", replyTo);
            data.put("idempotencyKey", key);
            data.put("deliveryState", result.deliveryState().name());
            data.put("messageIds", result.messageIds());
            data.put("errorCode", result.errorCode());
            data.put("alreadyAttempted", result.alreadyAttempted());
            return VideoToolSupport.ok(NAME, data);
        } catch (VideoToolSupport.InvalidArgumentsException invalid) {
            return VideoToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (BotReplyException failure) {
            return VideoToolSupport.failure(failure.code(), failure.getMessage());
        } catch (IllegalArgumentException invalid) {
            return VideoToolSupport.failure("BILI_INVALID_TARGET", invalid.getMessage());
        } catch (RuntimeException failure) {
            return VideoToolSupport.failure("COMMENT_REPLY_UNAVAILABLE",
                    "评论写入暂不可用（内部原因已脱敏）");
        }
    }

    private VideoAccountRef selectAccount(Object raw) {
        String requested = raw == null ? "" : raw.toString().trim();
        if (!requested.isEmpty()) {
            return accounts.stream().filter(account -> requested.equals(account.accountId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("B站账号不存在"));
        }
        if (accounts.size() == 1) return accounts.getFirst();
        throw new IllegalArgumentException(
                accounts.isEmpty() ? "没有可用 B站账号" : "存在多个 B站账号，请提供 accountId");
    }

    private static String optionalRpid(Object raw, String name) {
        if (raw == null) return "";
        String value = raw.toString().trim();
        if (value.isEmpty()) return "";
        if (!value.matches("[0-9]{1,20}")) {
            throw new VideoToolSupport.InvalidArgumentsException(name + " 必须是数字 rpid");
        }
        return value;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("target", "accountId", "rootRpid", "replyToRpid",
                "idempotencyKey")) {
            if (context.arguments().containsKey(key)) {
                safe.put(key, context.arguments().get(key));
            }
        }
        return Map.copyOf(safe);
    }
}
