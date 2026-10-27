package io.mosire.iibot.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.bot.chat.ChatMessage;
import io.mosire.iibot.bot.chat.ChatSourceKey;
import io.mosire.iibot.bot.store.InMemoryMessageBoardStore;
import io.mosire.iibot.bot.weight.InMemorySourceWeightStore;
import io.mosire.iibot.bot.weight.SourceWeightService;
import io.mosire.iibot.bot.weight.WeightSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotSourceWeightToolTest {

    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final ChatSourceKey GROUP = ChatSourceKey.groupChat("bot-1", "group-9");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void setsAndReadsSourceWeight() throws Exception {
        InMemoryMessageBoardStore board = new InMemoryMessageBoardStore();
        board.append(ChatMessage.inbound(GROUP, "m-1", "qq-1", "Alice",
                T0, T0, "普通群聊", "", ""));
        SourceWeightService service = new SourceWeightService(
                new InMemorySourceWeightStore(), board, WeightSettings.defaults(), CLOCK);
        service.ensurePolicy(GROUP, true);

        JsonNode set = call(new BotSourceWeightSetTool(service), Map.of(
                "threadId", GROUP.threadId(),
                "sourceWeight", 500,
                "notifyThreshold", 100,
                "keywordWeights", Map.of("上线", 120)));
        assertThat(set.path("data").path("sourceWeight").asInt()).isEqualTo(500);
        assertThat(set.path("data").path("revision").asLong()).isEqualTo(2L);

        JsonNode get = call(new BotSourceWeightGetTool(service),
                Map.of("threadId", GROUP.threadId()));
        assertThat(get.path("data").path("sourceWeight").asInt()).isEqualTo(500);
        assertThat(get.path("data").path("keywordWeights").path("上线").asInt())
                .isEqualTo(120);

        JsonNode list = call(new BotSourceWeightListTool(service), Map.of());
        assertThat(list.path("data").path("sources")).hasSize(1);
    }

    private static JsonNode call(AgentTool tool, Map<String, Object> args) throws Exception {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        ToolResult result = tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
        assertThat(result.success()).isTrue();
        return JSON.readTree(result.message());
    }
}
