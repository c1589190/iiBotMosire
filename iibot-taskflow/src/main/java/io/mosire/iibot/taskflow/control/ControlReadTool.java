package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;

import java.util.Map;

/** 只读控制工具：GUEST 级、非敏感、非破坏。 */
final class ControlReadTool extends AbstractControlTool {

    @FunctionalInterface
    interface Body {
        Map<String, Object> execute(ToolContext context);
    }

    private final Body body;

    ControlReadTool(String name, String description, Map<String, Object> schema, Body body) {
        super(name, description, schema, ToolSpec.level(AccessToken.GUEST, false, false));
        this.body = body;
    }

    @Override
    protected Operation operation() {
        return Operation.READ;
    }

    @Override
    protected ToolResult executeChecked(ToolContext context) {
        return ControlResults.success(name(), body.execute(context));
    }
}
