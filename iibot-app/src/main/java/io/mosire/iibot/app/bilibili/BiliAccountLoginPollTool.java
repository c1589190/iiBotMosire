package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.video.bilibili.BiliLoginService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 轮询 B 站扫码登录状态；成功后把 0600 Cookie 文件写到配置路径。 */
public final class BiliAccountLoginPollTool implements AgentTool {

    public static final String NAME = "bili_account_login_poll";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "loginId", Map.of("type", "string", "minLength", 1,
                            "description", "bili_account_login_start 返回的 loginId")),
            "required", List.of("loginId"),
            "additionalProperties", false);

    private final BiliLoginService service;

    public BiliAccountLoginPollTool(BiliLoginService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "轮询 B 站扫码登录状态。state=WAITING_SCAN/SCANNED/SUCCESS/EXPIRED/"
                + "FAILED；SUCCESS 时 Cookie 已原子写入配置的 0600 文件，不会返回 Cookie。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            Object raw = context.arguments().get("loginId");
            if (!(raw instanceof String loginId) || loginId.isBlank()) {
                return ToolResult.error("INVALID_ARGUMENTS", "loginId 不能为空");
            }
            BiliLoginService.PollResult result = service.poll(loginId.trim());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("state", result.state());
            data.put("message", result.message());
            data.put("accountId", result.accountId());
            data.put("credentialWritten", "SUCCESS".equals(result.state()));
            return ToolResult.ok(json(Map.of("tool", NAME, "ok", true, "data", data)));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("BILI_LOGIN_POLL_FAILED", "B站登录状态轮询失败");
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("B站登录结果序列化失败", failure);
        }
    }
}
