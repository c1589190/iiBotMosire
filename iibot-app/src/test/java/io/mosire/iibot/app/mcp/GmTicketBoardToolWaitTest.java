package io.mosire.iibot.app.mcp;

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
import io.mosire.iibot.taskflow.ticket.TicketService;
import io.mosire.iibot.taskflow.ticket.TicketSnapshot;
import io.mosire.iibot.taskflow.ticket.TicketStore;
import io.mosire.iibot.taskflow.ticket.TicketType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 工单板 wait=true 的真实阻塞：新工单唤醒、已有工单立即返回、超时返回。 */
class GmTicketBoardToolWaitTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Actor PLAYER = new Actor("player-1", ActorRole.PLAYER);

    @Test
    void waitsUntilFirstTicketArrives() throws Exception {
        MemoryTicketStore store = new MemoryTicketStore();
        TicketService service = new TicketService(store);
        GmTicketBoardTool tool = new GmTicketBoardTool(service);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<ToolResult> call = CompletableFuture.supplyAsync(
                    () -> call(tool, Map.of("wait", true, "waitSeconds", 5)), executor);
            Thread.sleep(180);
            assertThat(call.isDone()).isFalse();

            assertThat(service.submit(PLAYER, TicketType.ACTION_DRAFT,
                    "行动草案", "行动正文", "board-wait-create").ok()).isTrue();

            ToolResult result = call.get(2, TimeUnit.SECONDS);
            assertThat(result.success()).isTrue();
            JsonNode data = JSON.readTree(result.message()).path("data");
            assertThat(data.path("waitMode").asBoolean()).isTrue();
            assertThat(data.path("alreadyPending").asBoolean()).isFalse();
            assertThat(data.path("wokeByChange").asBoolean()).isTrue();
            assertThat(data.path("waitTimedOut").asBoolean()).isFalse();
            assertThat(data.path("recent")).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void alreadyPendingReturnsImmediately() throws Exception {
        MemoryTicketStore store = new MemoryTicketStore();
        TicketService service = new TicketService(store);
        service.submit(PLAYER, TicketType.SETTING_QUERY,
                "已有问题", "正文", "board-existing");
        GmTicketBoardTool tool = new GmTicketBoardTool(service);

        long start = System.nanoTime();
        ToolResult result = call(tool, Map.of("wait", true, "waitSeconds", 5));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis).isLessThan(1_000);
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("alreadyPending").asBoolean()).isTrue();
        assertThat(data.path("wokeByChange").asBoolean()).isFalse();
        assertThat(data.path("recent")).hasSize(1);
    }

    @Test
    void boundedTimeoutReturnsEmptyBoardWithoutHangingForever() throws Exception {
        TicketService service = new TicketService(new MemoryTicketStore());
        GmTicketBoardTool tool = new GmTicketBoardTool(service);

        long start = System.nanoTime();
        ToolResult result = call(tool, Map.of("wait", true, "waitSeconds", 1));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(800);
        JsonNode data = JSON.readTree(result.message()).path("data");
        assertThat(data.path("waitTimedOut").asBoolean()).isTrue();
        assertThat(data.path("recent")).isEmpty();
    }

    private static ToolResult call(GmTicketBoardTool tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(GmTicketBoardTool.NAME)
                .roles(RoleSet.of("supervisor"))
                .resourceScopes(new ResourceScopeMap(Map.of(
                        TicketStore.RESOURCE_NAMESPACE,
                        ResourceScope.of(TicketStore.RESOURCE_NAMESPACE))))
                .sensitiveAllowed(true)
                .build();
        ToolContext context = new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), args, AgentIdentity.external());
        return ToolCallAuthorizer.standard().execute(
                registry, GmTicketBoardTool.NAME, context);
    }

    private static final class MemoryTicketStore implements TicketStore {
        private TicketSnapshot state = TicketSnapshot.empty();

        @Override
        public TicketSnapshot load() {
            return state;
        }

        @Override
        public void save(TicketSnapshot snapshot) {
            state = snapshot;
        }
    }
}
