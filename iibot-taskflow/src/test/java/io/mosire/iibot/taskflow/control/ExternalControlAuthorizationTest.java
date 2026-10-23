package io.mosire.iibot.taskflow.control;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.taskflow.EngineStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 角色、资源命名空间与唯一授权入口的拒绝行为。 */
class ExternalControlAuthorizationTest {

    @Test
    void guestCannotCallWriteToolAndEngineStaysUnchanged() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        EngineStatus before = fixture.status();

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-guest", "playerId", "p-guest",
                        "displayName", "Guest", "expectedRevision", 0L,
                        "idempotencyKey", "guest-write", "reason", "测试"),
                AccessToken.GUEST, RoleSet.of(ControlTestFixture.SUPERVISOR_ROLE),
                ResourceScopeMap.of("taskflow", ResourceScope.unlimited()));

        assertThat(result.code()).isEqualTo("PERMISSION_DENIED");
        assertThat(fixture.status().engineRevision()).isEqualTo(before.engineRevision());
        assertThat(fixture.status().playerCount()).isZero();
        assertThat(fixture.store.size()).isZero();
    }

    @Test
    void supervisorWithTaskflowScopeCanWrite() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "Player One", "expectedRevision", 0L,
                        "idempotencyKey", "register-p1", "reason", "测试注册"));

        assertThat(result.code()).isNull();
        assertThat(fixture.status().playerCount()).isEqualTo(1);
        assertThat(fixture.status().engineRevision()).isEqualTo(1);
        assertThat(fixture.store.size()).isEqualTo(1);
    }

    @Test
    void missingResourceScopeIsDeniedBeforeEngineAndHasNoSideEffects() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        EngineStatus before = fixture.status();

        ToolResult result = fixture.callRaw(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "Player One", "expectedRevision", 0L,
                        "idempotencyKey", "register-p1", "reason", "测试注册"),
                ResourceScopeMap.empty());

        assertThat(result.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(fixture.store.size()).isZero();
        assertThat(fixture.status().engineRevision()).isEqualTo(before.engineRevision());
    }

    @Test
    void explicitNoneResourceScopeIsDeniedAndEngineStaysUnchanged() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        EngineStatus before = fixture.status();

        ToolResult result = fixture.callRaw(control, ExternalControlToolSet.TF_REGISTER_PLAYER,
                ControlTestFixture.args("qqId", "qq-p1", "playerId", "p1",
                        "displayName", "Player One", "expectedRevision", 0L,
                        "idempotencyKey", "register-p1", "reason", "测试注册"),
                ResourceScopeMap.of("taskflow", ResourceScope.none()));

        assertThat(result.code()).isEqualTo("RESOURCE_DENIED");
        assertThat(fixture.store.size()).isZero();
        assertThat(fixture.status().engineRevision()).isEqualTo(before.engineRevision());
    }

    @Test
    void directToolExecutionWithoutInjectedAuthorizerFailsClosed() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.GUEST)
                .allowAll().roles(RoleSet.of(ControlTestFixture.SUPERVISOR_ROLE)).build();
        ToolContext context = new ToolContext(AccessToken.GUEST, permissions,
                Map.of(), Map.of(), AgentIdentity.external());

        assertThatThrownBy(() -> control.find(ExternalControlToolSet.TF_STATUS).orElseThrow()
                .execute(context))
                .isInstanceOf(ResourceDeniedException.class);
    }

    @Test
    void guestCanCallGuestReadToolWithAuthorizedResource() {
        ControlTestFixture fixture = new ControlTestFixture();
        ExternalControlToolSet control = fixture.control();

        ToolResult result = fixture.call(control, ExternalControlToolSet.TF_STATUS, Map.of(),
                AccessToken.GUEST, RoleSet.of(ControlTestFixture.SUPERVISOR_ROLE),
                ResourceScopeMap.of("taskflow", ResourceScope.unlimited()));

        assertThat(result.code()).isNull();
        assertThat(fixture.json(result).path("data").path("worldVersion").asLong()).isZero();
    }
}
