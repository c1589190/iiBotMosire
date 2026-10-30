package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.store.MessageBoardStore;
import io.mosire.iibot.connect.core.AudioCapability;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.MessageSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 提取消息中的语音为受控 audioRef；当前不做 ASR/转写。 */
public final class BotAudioGetTool implements AgentTool {

    public static final String NAME = "qq_audio_get";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 20L;
    private static final int MAX_AUDIO = 4;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "messageKey", Map.of("type", "string", "minLength", 1),
                    "segmentIndex", Map.of("type", "integer", "minimum", 0,
                            "description", "可选；指定 audio 段下标"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_AUDIO,
                            "description", "最多返回语音数，默认 1")),
            "required", List.of("messageKey"),
            "additionalProperties", false);

    private final MessageBoardStore board;
    private final Map<String, AudioCapability> providers;
    private final BotMediaStore mediaStore;

    public BotAudioGetTool(MessageBoardStore board,
                           Map<String, AudioCapability> providers,
                           BotMediaStore mediaStore) {
        this.board = Objects.requireNonNull(board, "board");
        Objects.requireNonNull(providers, "providers");
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "提取消息中的语音并提供受控 audioRef/MIME/size；不返回 URL、路径或 base64。"
                + "当前未配置 ASR，不会返回转写文本。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String messageKey = requiredText(context.arguments(), "messageKey");
            ChatMessage message = board.find(messageKey).orElseThrow(() ->
                    new IllegalArgumentException("messageKey 不存在"));
            AudioCapability provider = providers.get(message.source().accountId());
            if (provider == null) {
                return ToolResult.error("ACCOUNT_UNAVAILABLE",
                        "该消息所属账号当前不可用或未提供音频提取能力");
            }
            int segmentIndex = optionalInt(context.arguments(), "segmentIndex", -1);
            int limit = optionalInt(context.arguments(), "limit", 1);
            List<MessageSegment> audios = board.segments(messageKey).stream()
                    .filter(segment -> segment.type() == MessageSegment.Type.AUDIO)
                    .filter(segment -> segmentIndex < 0 || segment.index() == segmentIndex)
                    .limit(limit)
                    .toList();
            if (audios.isEmpty()) {
                return ToolResult.error("AUDIO_NOT_FOUND", "消息中没有语音段");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            for (MessageSegment audio : audios) {
                try {
                    MediaAsset asset = provider.audio(audio)
                            .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    String audioRef = mediaStore.storeUpload(asset);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("segmentIndex", audio.index());
                    row.put("audioRef", audioRef);
                    row.put("mediaType", asset.mediaType());
                    row.put("size", asset.size());
                    rows.add(row);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("AUDIO_UNAVAILABLE", "音频提取被中断");
                } catch (Exception failure) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("segmentIndex", audio.index());
                    item.put("code", "AUDIO_UNAVAILABLE");
                    errors.add(item);
                }
            }
            if (rows.isEmpty()) {
                return ToolResult.error("AUDIO_UNAVAILABLE",
                        "音频提取失败（内部原因已脱敏）");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageKey", messageKey);
            data.put("source", message.source().sourceAddress().canonical());
            data.put("count", rows.size());
            data.put("audios", rows);
            data.put("errors", errors);
            data.put("asrConfigured", false);
            data.put("transcriptAvailable", false);
            return ToolResult.ok(JSON.writeValueAsString(data));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("AUDIO_UNAVAILABLE",
                    "音频提取失败（内部原因已脱敏）");
        }
    }

    private static String requiredText(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        int value = number.intValue();
        if (value < 0 || (("limit".equals(name) && (value < 1 || value > MAX_AUDIO)))) {
            throw new IllegalArgumentException(name + " 超出范围");
        }
        return value;
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("messageKey", "segmentIndex", "limit")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        return Map.copyOf(safe);
    }
}
