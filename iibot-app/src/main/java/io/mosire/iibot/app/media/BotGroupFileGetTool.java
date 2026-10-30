package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.GroupFileCapability;
import io.mosire.iibot.connect.core.MediaAsset;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通过短期 fileRef 读取群文件。
 *
 * <p>文本文件返回有界 UTF-8 文本；图片走 BotMediaStore 以 ImageContent 返回；其他
 * 二进制只返回元数据，不把字节/base64 塞进模型上下文。</p>
 */
public final class BotGroupFileGetTool implements AgentTool {

    public static final String NAME = "qq_group_file_get";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEXT_BYTES = 64 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024L * 1024L;
    private static final long TIMEOUT_SECONDS = 30L;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "fileRef", Map.of("type", "string", "minLength", 1,
                            "description", "qq_group_files 返回的当前进程内短期句柄")),
            "required", List.of("fileRef"),
            "additionalProperties", false);

    private final Map<String, GroupFileCapability> providers;
    private final BotGroupFileHandles handles;
    private final BotMediaStore mediaStore;

    public BotGroupFileGetTool(Map<String, GroupFileCapability> providers,
                               BotGroupFileHandles handles,
                               BotMediaStore mediaStore) {
        Objects.requireNonNull(providers, "providers");
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.handles = Objects.requireNonNull(handles, "handles");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "通过 qq_group_files 的短期 fileRef 读取群文件：文本有界返回正文，图片以 "
                + "MCP ImageContent 返回，其他二进制只返回元数据；不暴露 file_id、busid、"
                + "下载 URL 或本地路径。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String fileRef = requiredText(context.arguments(), "fileRef");
            BotGroupFileHandles.Entry entry = handles.resolve(fileRef).orElseThrow(() ->
                    new GroupFileToolException("FILE_REF_UNKNOWN",
                            "fileRef 无效或已过期；请重新调用 qq_group_files"));
            GroupFileCapability provider = providers.get(entry.accountId());
            if (provider == null) {
                return ToolResult.error("ACCOUNT_UNAVAILABLE",
                        "该文件所属账号当前不可用或未提供群文件能力");
            }
            if (entry.file().byteSize() > MAX_DOWNLOAD_BYTES) {
                return ToolResult.error("FILE_TOO_LARGE",
                        "群文件超过 10 MiB 下载上限");
            }
            MediaAsset asset = provider.downloadGroupFile(entry.groupId(), entry.file())
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (asset.size() > MAX_DOWNLOAD_BYTES) {
                return ToolResult.error("FILE_TOO_LARGE",
                        "群文件超过 10 MiB 下载上限");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", entry.accountId());
            data.put("groupId", entry.groupId());
            data.put("fileName", asset.fileName());
            data.put("size", asset.size());
            data.put("mediaType", asset.mediaType());

            List<String> assetIds = List.of();
            if (isImage(asset)) {
                assetIds = List.of(mediaStore.store(asset));
                data.put("contentKind", "image");
                data.put("contentOmitted", false);
            } else if (isTextLike(asset)) {
                byte[] bytes = asset.bytes();
                int previewBytes = Math.min(bytes.length, MAX_TEXT_BYTES);
                String text = new String(bytes, 0, previewBytes, StandardCharsets.UTF_8);
                data.put("contentKind", "text");
                data.put("contentOmitted", false);
                data.put("truncated", bytes.length > previewBytes);
                data.put("text", text);
            } else {
                data.put("contentKind", "binary");
                data.put("contentOmitted", true);
                data.put("truncated", false);
            }
            return ToolResult.ok(JSON.writeValueAsString(data), assetIds);
        } catch (GroupFileToolException failure) {
            return ToolResult.error(failure.code(), failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error("FILE_UNAVAILABLE", "群文件读取被中断");
        } catch (Exception failure) {
            return ToolResult.error("FILE_UNAVAILABLE",
                    "群文件读取失败（内部原因已脱敏）");
        }
    }

    private static boolean isImage(MediaAsset asset) {
        return asset.mediaType() != null
                && asset.mediaType().toLowerCase(java.util.Locale.ROOT).startsWith("image/");
    }

    private static boolean isTextLike(MediaAsset asset) {
        String mediaType = asset.mediaType() == null ? ""
                : asset.mediaType().toLowerCase(java.util.Locale.ROOT);
        if (mediaType.startsWith("text/")
                || mediaType.equals("application/json")
                || mediaType.equals("application/xml")
                || mediaType.equals("application/yaml")
                || mediaType.equals("application/x-yaml")) {
            return true;
        }
        String name = asset.fileName() == null ? ""
                : asset.fileName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".csv") || name.endsWith(".xml")
                || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".log");
    }

    private static String requiredText(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new GroupFileToolException("INVALID_ARGUMENTS", name + " 不能为空");
        }
        return text.trim();
    }

    private static final class GroupFileToolException extends RuntimeException {
        private final String code;

        private GroupFileToolException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
