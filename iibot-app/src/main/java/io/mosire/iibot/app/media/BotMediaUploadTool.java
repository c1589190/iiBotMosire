package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.MediaAsset;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 把模型提供的 base64 媒体写入受控资产库，返回短期 assetRef。 */
public final class BotMediaUploadTool implements AgentTool {

    public static final String NAME = "qq_media_upload";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = ((MAX_BYTES + 2) / 3) * 4;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fileName", Map.of("type", "string", "minLength", 1,
                            "maxLength", 256),
                    "mediaType", Map.of("type", "string", "maxLength", 128,
                            "description", "MIME；省略时使用 application/octet-stream"),
                    "dataBase64", Map.of("type", "string", "minLength", 1,
                            "description", "文件字节的标准 base64；最大解码后 10 MiB")),
            "required", List.of("fileName", "dataBase64"),
            "additionalProperties", false);

    private final BotMediaStore mediaStore;

    public BotMediaUploadTool(BotMediaStore mediaStore) {
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "上传一个受控出站媒体资产并返回 assetRef；assetRef 可用于发送图片或文件。"
                + "不接受任意 URL/本地路径，也不返回保存路径。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String fileName = safeFileName(required(context.arguments(), "fileName"));
            String mediaType = optional(context.arguments(), "mediaType");
            if (mediaType.isEmpty()) {
                mediaType = "application/octet-stream";
            }
            if (!isSafeMediaType(mediaType)) {
                return ToolResult.error("INVALID_ARGUMENTS", "mediaType 非法");
            }
            String encoded = required(context.arguments(), "dataBase64")
                    .replaceAll("\\s+", "");
            if (encoded.length() > MAX_BASE64_CHARS) {
                return ToolResult.error("ASSET_TOO_LARGE", "上传资产超过 10 MiB 上限");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException invalid) {
                return ToolResult.error("INVALID_ARGUMENTS", "dataBase64 不是合法 base64");
            }
            if (bytes.length == 0) {
                return ToolResult.error("INVALID_ARGUMENTS", "上传资产不能为空");
            }
            if (bytes.length > MAX_BYTES) {
                return ToolResult.error("ASSET_TOO_LARGE", "上传资产超过 10 MiB 上限");
            }
            String assetRef = mediaStore.storeUpload(
                    new MediaAsset(fileName, mediaType, bytes));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("assetRef", assetRef);
            data.put("fileName", fileName);
            data.put("mediaType", mediaType);
            data.put("size", bytes.length);
            return ToolResult.ok(json(data));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("ASSET_UPLOAD_FAILED",
                    "媒体资产上传失败（内部原因已脱敏）");
        }
    }

    @Override public Map<String, Object> ledgerArgs(ToolContext context) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : List.of("fileName", "mediaType")) {
            if (context.arguments().containsKey(name)) {
                safe.put(name, context.arguments().get(name));
            }
        }
        safe.put("hasData", context.arguments().containsKey("dataBase64"));
        return Map.copyOf(safe);
    }

    private static String required(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    private static String optional(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException(name + " 必须是字符串");
        }
        return text.trim();
    }

    private static String safeFileName(String raw) {
        String name = raw.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = "upload.bin";
        }
        return name.length() <= 256 ? name : name.substring(0, 256);
    }

    private static boolean isSafeMediaType(String value) {
        return value.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("媒体上传结果序列化失败", failure);
        }
    }
}
