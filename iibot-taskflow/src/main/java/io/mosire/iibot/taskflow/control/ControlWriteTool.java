package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;

import java.util.Map;

/** 全游戏写工具：DEFAULT 级、敏感、非破坏；统一强制三要素并做控制层幂等缓存。 */
final class ControlWriteTool extends AbstractControlTool {

    @FunctionalInterface
    interface Body {
        ToolResult execute(ToolContext context, Map<String, Object> arguments, WriteEnvelope envelope);
    }

    private final Body body;
    private final ControlWriteCache cache;

    ControlWriteTool(String name, String description, Map<String, Object> schema,
                     ControlWriteCache cache, Body body) {
        super(name, description, schema, ToolSpec.level(AccessToken.DEFAULT, true, false));
        this.cache = cache;
        this.body = body;
    }

    @Override
    protected Operation operation() {
        return Operation.WRITE;
    }

    @Override
    protected ToolResult executeChecked(ToolContext context) {
        Map<String, Object> arguments = context.arguments();
        WriteEnvelope envelope = WriteEnvelope.from(arguments);
        return cache.execute(envelope.idempotencyKey(),
                () -> body.execute(context, arguments, envelope));
    }
}
