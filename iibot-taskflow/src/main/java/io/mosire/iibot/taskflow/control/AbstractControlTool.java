package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 控制面工具骨架：统一角色、资源命名空间、执行边界与错误映射。
 *
 * <p>每个工具在执行任何参数读取或领域调用之前，先经宿主注入的
 * {@code ToolContext.resources().require(...)} 做资源断言。{@link ResourceDeniedException}
 * 必须原样重抛给 {@code ToolCallAuthorizer}，工具不得把它降级成普通错误或静默空结果。</p>
 */
abstract class AbstractControlTool implements AgentTool {

    static final String NAMESPACE = "taskflow";
    private static final ResourceManifest RESOURCES = ResourceManifest.of(NAMESPACE, ResourcePolicy.DENY);
    private static final Set<String> ROLES = Set.of("supervisor");

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final ToolSpec spec;

    AbstractControlTool(String name, String description, Map<String, Object> schema, ToolSpec spec) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.schema = Objects.requireNonNull(schema, "schema");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public final Map<String, Object> jsonSchema() {
        return schema;
    }

    @Override
    public final ToolSpec spec() {
        return spec;
    }

    @Override
    public final Set<String> roles() {
        return ROLES;
    }

    @Override
    public final ResourceManifest resources() {
        return RESOURCES;
    }

    @Override
    public final ToolResult execute(ToolContext context) {
        try {
            // 资源断言必须在参数解析与领域调用之前；不能用“参数错了就先回错”绕过授权边界。
            context.resources().require(operation(), ResourceId.of(NAMESPACE, name));
            return executeChecked(context);
        } catch (ResourceDeniedException denied) {
            // 唯一入口负责转成 RESOURCE_DENIED；工具吞掉就是 fail-open。
            throw denied;
        } catch (InvalidControlArgumentException invalid) {
            return ControlResults.failure(name, "INVALID_ARGUMENTS", invalid.getMessage());
        } catch (ControlFailureException expected) {
            return ControlResults.failure(name, expected.code(), expected.getMessage());
        } catch (RuntimeException unexpected) {
            return ControlResults.internalError(name, unexpected);
        }
    }

    /** 本工具需要的资源操作。 */
    protected abstract Operation operation();

    /** 已完成资源断言后执行工具逻辑。 */
    protected abstract ToolResult executeChecked(ToolContext context);

    /** 工具自身资源标识，供测试与审计引用。 */
    static ResourceId resourceId(String toolName) {
        return ResourceId.of(NAMESPACE, toolName);
    }
}
