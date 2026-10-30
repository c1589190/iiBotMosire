package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.GroupFileCapability;
import io.mosire.iibot.connect.core.QqGroupFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 列出 QQ 群文件目录；只返回短期 fileRef 与安全展示元数据。 */
public final class BotGroupFilesTool implements AgentTool {

    public static final String NAME = "qq_group_files";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 20L;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "Bot 账号；单账号可省略，多账号必须指定"),
                    "groupId", Map.of("type", "string", "minLength", 1,
                            "description", "Bot 已加入的群号"),
                    "folderId", Map.of("type", "string",
                            "description", "群文件目录；默认根目录 /"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "最多返回文件数，默认 50")),
            "required", List.of("groupId"),
            "additionalProperties", false);

    private final Map<String, GroupFileCapability> providers;
    private final BotGroupFileHandles handles;

    public BotGroupFilesTool(Map<String, GroupFileCapability> providers,
                             BotGroupFileHandles handles) {
        Objects.requireNonNull(providers, "providers");
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "列出某个 Bot 已加入群的群文件。返回名称、大小、上传者和当前进程内有效的"
                + " fileRef；不返回 file_id、busid 或下载 URL。多账号时必须指定 accountId。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            String accountId = resolveAccount(context.arguments());
            GroupFileCapability provider = providers.get(accountId);
            String groupId = requiredText(context.arguments(), "groupId");
            String folderId = optionalText(context.arguments(), "folderId", "/");
            int limit = optionalInt(context.arguments(), "limit", 50, 1, 100);

            List<QqGroupFile> files = provider.groupFiles(groupId, folderId)
                    .toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (QqGroupFile file : files) {
                if (rows.size() >= limit) {
                    break;
                }
                String fileRef = handles.register(accountId, groupId, file);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fileRef", fileRef);
                row.put("name", file.name());
                row.put("size", file.byteSize());
                row.put("uploaderId", file.uploaderId());
                row.put("uploaderName", file.uploaderName());
                row.put("uploadedAt", file.uploadedAt() == null
                        ? null : file.uploadedAt().toString());
                rows.add(row);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", accountId);
            data.put("groupId", groupId);
            data.put("folderId", folderId);
            data.put("totalCount", files.size());
            data.put("count", rows.size());
            data.put("truncated", files.size() > rows.size());
            data.put("files", rows);
            return ToolResult.ok(JSON.writeValueAsString(data));
        } catch (GroupFileToolException failure) {
            return ToolResult.error(failure.code(), failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error("GROUP_FILES_UNAVAILABLE", "群文件目录读取被中断");
        } catch (Exception failure) {
            return ToolResult.error("GROUP_FILES_UNAVAILABLE",
                    "群文件目录读取失败（内部原因已脱敏）");
        }
    }

    private String resolveAccount(Map<String, Object> args) {
        Object raw = args == null ? null : args.get("accountId");
        if (raw != null) {
            if (!(raw instanceof String text) || text.isBlank()) {
                throw new GroupFileToolException("INVALID_ARGUMENTS",
                        "accountId 必须是非空白字符串");
            }
            String accountId = text.trim();
            if (!providers.containsKey(accountId)) {
                throw new GroupFileToolException("ACCOUNT_UNAVAILABLE",
                        "Bot 账号不可用: " + accountId);
            }
            if (providers.get(accountId) == null) {
                throw new GroupFileToolException("GROUP_FILES_UNSUPPORTED",
                        "该账号不支持群文件能力");
            }
            return accountId;
        }
        List<String> available = providers.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        if (available.isEmpty()) {
            throw new GroupFileToolException("GROUP_FILES_UNSUPPORTED",
                    "当前没有账号支持群文件能力");
        }
        if (available.size() > 1) {
            throw new GroupFileToolException("ACCOUNT_REQUIRED",
                    "存在多个可用账号，必须指定 accountId");
        }
        return available.getFirst();
    }

    private static String requiredText(Map<String, Object> args, String name) {
        Object raw = args == null ? null : args.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new GroupFileToolException("INVALID_ARGUMENTS", name + " 不能为空");
        }
        return text.trim();
    }

    private static String optionalText(Map<String, Object> args, String name, String fallback) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new GroupFileToolException("INVALID_ARGUMENTS",
                    name + " 必须是非空白字符串");
        }
        return text.trim();
    }

    private static int optionalInt(Map<String, Object> args, String name, int fallback,
                                   int minimum, int maximum) {
        Object raw = args == null ? null : args.get(name);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number)) {
            throw new GroupFileToolException("INVALID_ARGUMENTS", name + " 必须是整数");
        }
        int value = number.intValue();
        if (value < minimum || value > maximum) {
            throw new GroupFileToolException("INVALID_ARGUMENTS",
                    name + " 必须在 " + minimum + ".." + maximum);
        }
        return value;
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
