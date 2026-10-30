package io.mosire.iibot.app.bilibili;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.AccountRegistry;
import io.mosire.iibot.connect.core.SendReceipt;
import io.mosire.iibot.connect.video.bilibili.BilibiliAdapter;
import io.mosire.iibot.connect.video.bilibili.BilibiliTarget;
import io.mosire.iibot.connect.video.bilibili.BilibiliTargetResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 显式、单次、可审计的 B 站互动；每个 action 是独立工具。 */
public final class VideoInteractionTool implements AgentTool {

    public enum Action {
        LIKE("video_like", "like"),
        COIN("video_coin", "coin"),
        FAVORITE("video_favorite", "favorite"),
        FOLLOW("video_follow", "follow");

        private final String toolName;
        private final String wireName;

        Action(String toolName, String wireName) {
            this.toolName = toolName;
            this.wireName = wireName;
        }

        public String toolName() { return toolName; }
        public String wireName() { return wireName; }
    }

    private final Action action;
    private final AccountRegistry registry;
    private final BilibiliTargetResolver resolver = new BilibiliTargetResolver();

    public VideoInteractionTool(Action action, AccountRegistry registry) {
        this.action = Objects.requireNonNull(action, "action");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override public String name() { return action.toolName(); }

    @Override public String description() {
        return "显式对 B站视频执行一次 " + action.wireName() + "；默认要求 B站账号 "
                + "writeEnabled=true，单次调用、不自动循环。点赞/投币/收藏/关注仍受 "
                + "Adapter 冷却、每日配额和幂等约束，UNKNOWN 不得自动重试。";
    }

    @Override public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("target", Map.of("type", "string", "minLength", 1,
                "description", "B站 BV 号或视频 URL；必须能解析出 BV 号"));
        properties.put("accountId", Map.of("type", "string",
                "description", "B站账号 ID；只有一个 B站账号时可省略"));
        properties.put("idempotencyKey", Map.of("type", "string", "maxLength", 200,
                "description", "可选；同一 key 不重复执行"));
        if (action == Action.COIN) {
            properties.put("count", Map.of("type", "integer", "minimum", 1, "maximum", 2,
                    "description", "投币数量，默认 1"));
        }
        return Map.of("type", "object", "properties", Map.copyOf(properties),
                "required", List.of("target"), "additionalProperties", false);
    }

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
                        "互动只支持可解析出 BV 号的视频目标");
            }
            BilibiliAdapter adapter = selectAdapter(context.arguments().get("accountId"));
            String key = context.arguments().containsKey("idempotencyKey")
                    ? VideoToolSupport.requiredText(context.arguments(), "idempotencyKey", 200)
                    : UUID.randomUUID().toString();
            SendReceipt receipt = switch (action) {
                case LIKE -> adapter.likeVideo(target.bvid(), key);
                case COIN -> adapter.addCoin(target.bvid(), coinCount(context), key);
                case FAVORITE -> adapter.favoriteVideo(target.bvid(), key);
                case FOLLOW -> adapter.followUploader(target.bvid(), key);
            };
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", action.wireName());
            data.put("accountId", adapter.config().accountId());
            data.put("bvid", target.bvid());
            data.put("idempotencyKey", key);
            data.put("deliveryState", receipt.state().name());
            data.put("messageIds", receipt.adapterMessageIds());
            data.put("errorCode", receipt.errorCode());
            data.put("detail", receipt.detail());
            return VideoToolSupport.ok(name(), data);
        } catch (VideoToolSupport.InvalidArgumentsException invalid) {
            return VideoToolSupport.failure("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return VideoToolSupport.failure("BILI_INVALID_TARGET", invalid.getMessage());
        } catch (RuntimeException failure) {
            return VideoToolSupport.failure("INTERACTION_UNAVAILABLE",
                    "互动写入暂不可用（内部原因已脱敏）");
        }
    }

    private BilibiliAdapter selectAdapter(Object raw) {
        String requested = raw == null ? "" : raw.toString().trim();
        List<AccountRegistry.RegisteredAccount> bili = registry.snapshot().stream()
                .filter(account -> account.adapter() instanceof BilibiliAdapter)
                .toList();
        if (!requested.isEmpty()) {
            return bili.stream()
                    .filter(account -> requested.equals(account.accountId()))
                    .map(account -> (BilibiliAdapter) account.adapter())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("B站账号不存在"));
        }
        if (bili.size() == 1) return (BilibiliAdapter) bili.getFirst().adapter();
        throw new IllegalArgumentException(
                bili.isEmpty() ? "没有可用 B站账号" : "存在多个 B站账号，请提供 accountId");
    }

    private int coinCount(ToolContext context) {
        Object raw = context.arguments().get("count");
        if (raw == null) return 1;
        if (!(raw instanceof Number number)) {
            throw new VideoToolSupport.InvalidArgumentsException("count 必须是整数");
        }
        int value = number.intValue();
        if (value < 1 || value > 2) {
            throw new VideoToolSupport.InvalidArgumentsException("count 只允许 1 或 2");
        }
        return value;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("target", "accountId", "count", "idempotencyKey")) {
            if (context.arguments().containsKey(key)) {
                safe.put(key, context.arguments().get(key));
            }
        }
        return Map.copyOf(safe);
    }
}
