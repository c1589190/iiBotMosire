package io.mosire.iibot.app.mcp;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.iibot.taskflow.agent.SecretarySessionRegistry;
import io.mosire.iibot.taskflow.workspace.SecretaryWorkspaceStore;

import java.util.List;
import java.util.Objects;

/** GM 侧秘书工作区与秘书对话工具集；仅在工作区 store 与会话 registry 同时注入时使用。 */
public final class GmSecretaryMcpTools {

    public static final List<String> NAMES = List.of(
            GmSecretaryListTool.NAME,
            GmSecretaryReadTool.NAME,
            GmSecretaryDispatchTool.NAME,
            GmSecretaryChatTool.NAME);

    private GmSecretaryMcpTools() {
    }

    /** 四个工具，顺序稳定。 */
    public static List<AgentTool> all(SecretaryWorkspaceStore store,
                                      SecretarySessionRegistry sessions) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sessions, "sessions");
        return List.of(
                new GmSecretaryListTool(store, sessions),
                new GmSecretaryReadTool(store, sessions),
                new GmSecretaryDispatchTool(store, sessions),
                new GmSecretaryChatTool(sessions));
    }
}
