package io.mosire.iibot.taskflow.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.Actor;
import io.mosire.iibot.taskflow.ActorRole;
import io.mosire.iibot.taskflow.EngineStatus;
import io.mosire.iibot.taskflow.TaskflowEngine;
import io.mosire.iibot.taskflow.event.InMemoryEventStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** 控制面测试共享 fixture：真实引擎 + 真实 ToolRegistry + 真实 ToolCallAuthorizer。 */
final class ControlTestFixture {

    static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    static final String SUPERVISOR_ROLE = "supervisor";

    final Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
    final InMemoryEventStore store = new InMemoryEventStore();
    final TaskflowEngine engine = new TaskflowEngine(clock, store);
    final Actor supervisor = new Actor("external-supervisor", ActorRole.SUPERVISOR);
    final Actor gm = new Actor("external-gm", ActorRole.GM);
    private final ObjectMapper json = new ObjectMapper();

    ExternalControlToolSet control() {
        return control(supervisor);
    }

    ExternalControlToolSet control(Actor actor) {
        return ExternalControlToolSet.builder(engine, actor)
                .clock(clock)
                .eventReadPort(InMemoryEventReadPort.of(store))
                .build();
    }

    ToolRegistry registry(ExternalControlToolSet control) {
        return control.registerAll(new ToolRegistry());
    }

    ToolResult call(ExternalControlToolSet control, String tool, Map<String, Object> args) {
        return call(control, tool, args, AccessToken.DEFAULT, RoleSet.of(SUPERVISOR_ROLE),
                ResourceScopeMap.of("taskflow", ResourceScope.unlimited()));
    }

    ToolResult call(ExternalControlToolSet control, String tool, Map<String, Object> args,
                    AccessToken token, RoleSet roles, ResourceScopeMap scopes) {
        ToolRegistry registry = registry(control);
        AgentPermissionSet permissions = AgentPermissionSet.builder(token)
                .allowAll()
                .sensitiveAllowed(true)
                .destructiveAllowed(true)
                .roles(roles)
                .resourceScopes(scopes)
                .build();
        ToolContext context = new ToolContext(token, permissions, Map.of(), args,
                AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(registry, tool, context);
    }

    ToolResult callRaw(ExternalControlToolSet control, String tool, Map<String, Object> args,
                       ResourceScopeMap scopes) {
        return call(control, tool, args, AccessToken.DEFAULT,
                RoleSet.of(SUPERVISOR_ROLE), scopes);
    }

    JsonNode json(ToolResult result) {
        try {
            return json.readTree(result.message());
        } catch (Exception failure) {
            throw new AssertionError("工具结果不是 JSON: " + result.message(), failure);
        }
    }

    EngineStatus status() {
        return engine.status();
    }

    Map<String, Object> envelope(long expectedRevision, String key) {
        return Map.of("expectedRevision", expectedRevision,
                "idempotencyKey", key, "reason", "自动化控制面测试");
    }

    static Map<String, Object> args(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对");
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return result;
    }
}
