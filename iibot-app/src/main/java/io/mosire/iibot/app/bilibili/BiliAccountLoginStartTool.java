package io.mosire.iibot.app.bilibili;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 开始 B 站扫码登录；只返回二维码 URL 和进程内 loginId，不返回 Cookie。 */
public final class BiliAccountLoginStartTool implements AgentTool {

    public static final String NAME = "bili_account_login_start";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "accountId", Map.of("type", "string",
                            "description", "可选；多 B 站账号时指定要登录的账号")),
            "additionalProperties", false);

    private final BiliLoginService service;

    public BiliAccountLoginStartTool(BiliLoginService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "开始 B 站扫码登录。返回 qrUrl 与 loginId；用户扫码确认后调用 "
                + "bili_account_login_poll(loginId)。工具不会返回或记录 Cookie。";
    }

    @Override public Map<String, Object> jsonSchema() { return SCHEMA; }

    @Override public ToolSpec spec() { return ToolSpec.DEFAULT; }

    @Override public Set<String> roles() { return Set.of("supervisor"); }

    @Override public ToolResult execute(ToolContext context) {
        try {
            Object raw = context.arguments().get("accountId");
            String accountId = raw instanceof String value ? value.trim() : "";
            BiliLoginService.StartResult result = service.start(accountId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("loginId", result.loginId());
            data.put("accountId", result.accountId());
            data.put("qrUrl", result.qrUrl());
            data.put("expiresInSeconds", result.expiresInSeconds());
            return ToolResult.ok(json(Map.of("tool", NAME, "ok", true, "data", data)));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.error("INVALID_ARGUMENTS", invalid.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("BILI_LOGIN_START_FAILED", "B站登录二维码获取失败");
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
