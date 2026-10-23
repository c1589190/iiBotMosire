package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.agent.SecretarySession;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只读列出已装配秘书会话及其工作区四个逻辑根。
 *
 * <p>输出只有 playerId 与 Docs 相对根名；不返回物理路径、QQ 标识或文件正文。</p>
 */
public final class GmSecretaryListTool implements AgentTool {

    public static final String NAME = "tf_gm_secretary_list";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "description", "列出所有已装配玩家的秘书工作区根目录；只返回 playerId 与逻辑根名。",
            "properties", Map.of(),
            "required", List.of(),
            "additionalProperties", false);

    private final SecretaryWorkspaceStore store;
    private final SecretarySessionRegistry sessions;

    public GmSecretaryListTool(SecretaryWorkspaceStore store,
                               SecretarySessionRegistry sessions) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "只读列出所有已装配玩家的秘书工作区及其 public/player/gm/notes 四个逻辑根；"
                + "不返回物理路径、QQ 标识或文件内容。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.DEFAULT;
    }

    @Override
    public java.util.Set<String> roles() {
        return GmSecretaryToolSupport.ROLES;
    }

    @Override
    public io.mosire.agentlib.permission.ResourceManifest resources() {
        return GmSecretaryToolSupport.RESOURCES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            GmSecretaryToolSupport.requireRead(context);
            List<Map<String, Object>> players = new ArrayList<>();
            for (SecretarySession session : sessions.all()) {
                String playerId = session.playerId();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playerId", playerId);
                item.put("roots", store.list(playerId, ""));
                players.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("count", players.size());
            data.put("players", players);
            return GmSecretaryToolSupport.success(NAME, data);
        } catch (ResourceDeniedException denied) {
            throw denied;
        } catch (RuntimeException failure) {
            return GmSecretaryToolSupport.failure(NAME, "INTERNAL_ERROR",
                    "秘书工作区列表读取失败");
        }
    }
}
