package io.mosire.iibot.connect.agent;

import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Set;

/**
 * QQ 连接工具公共骨架。
 *
 * <p>统一声明 {@code qq} 资源命名空间与缺省 {@link ResourcePolicy#DENY}；统一把参数错误映射为
 * {@code INVALID_ARGUMENTS}、未知异常映射为脱敏的 {@code INTERNAL_ERROR}。{@link ResourceDeniedException}
 * 必须原样重抛，交给宿主的 {@code ToolCallAuthorizer} 转换为 {@code RESOURCE_DENIED}，工具不得吞掉。</p>
 */
abstract class AbstractQqTool implements AgentTool {

    /** 参数错误稳定码。 */
    static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    /** 内部错误稳定码。 */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final ResourceManifest QQ_RESOURCES =
            ResourceManifest.of(QqResourcePaths.NAMESPACE, ResourcePolicy.DENY);

    private final ToolSpec spec;
    private final Set<String> roles;

    AbstractQqTool(ToolSpec spec, Set<String> roles) {
        this.spec = spec;
        this.roles = ConnectToolOptions.normalizeRoles(roles);
    }

    @Override
    public final ToolSpec spec() {
        return spec;
    }

    @Override
    public final Set<String> roles() {
        return roles;
    }

    @Override
    public final ResourceManifest resources() {
        return QQ_RESOURCES;
    }

    @Override
    public final ToolResult execute(ToolContext context) {
        try {
            return executeChecked(context);
        } catch (ResourceDeniedException denied) {
            // 资源拒绝必须由宿主唯一入口转成 RESOURCE_DENIED；工具吞掉就是 fail-open。
            throw denied;
        } catch (InvalidToolArgumentException invalid) {
            return ToolResult.error(INVALID_ARGUMENTS, invalid.getMessage());
        } catch (RuntimeException unexpected) {
            // 不拼接异常消息/堆栈：底层协议对象、token 与完整 URL 都不得进入模型上下文。
            return ToolResult.error(INTERNAL_ERROR, "工具执行失败（内部错误已脱敏）");
        }
    }

    /**
     * 子类实际执行逻辑。
     *
     * @param context 宿主注入的调用上下文
     * @return 工具结果
     */
    protected abstract ToolResult executeChecked(ToolContext context);
}
