package io.mosire.iibot.app.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.iibot.app.config.AppConfig;
import io.mosire.iibot.app.config.AppConfigLoader;
import io.mosire.iibot.connect.core.InboundMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** 应用层测试共享的配置/消息构造工具。 */
public final class TestSupport {

    public static final Instant BASE = Instant.parse("2026-10-24T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSupport() {
    }

    public static ObjectNode validConfigNode(Path dataDir, Path factsFile) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("dataDir", dataDir.toString());
        root.put("publicGroupId", "group-public");
        root.put("maxTurnMinutes", 240);
        ObjectNode napcat = root.putObject("napcat");
        napcat.put("eventWebSocketUri", "ws://127.0.0.1:3001/");
        napcat.put("httpBaseUri", "http://127.0.0.1:3000/");
        napcat.put("accessTokenEnv", "IIBOT_NAPCAT_TOKEN");
        napcat.put("accountId", "bot-1");
        napcat.put("suppressSelfMessages", true);
        ArrayNode players = root.putArray("players");
        players.add(account("player-1", "qq-p1", "Player One"));
        players.add(account("player-2", "qq-p2", "Player Two"));
        ArrayNode directors = root.putArray("directors");
        directors.add(account("director-1", "qq-director", "Director"));
        ObjectNode world = root.putObject("world");
        world.put("promptVersion", "prompt-v1");
        world.put("factsFile", factsFile.toString());
        ObjectNode llm = root.putObject("llm");
        llm.put("baseUrl", "http://127.0.0.1:1/v1");
        llm.put("model", "fake-model");
        llm.put("apiKeyEnv", "IIBOT_LLM_KEY");
        llm.put("connectTimeoutSeconds", 2);
        llm.put("readTimeoutSeconds", 5);
        llm.put("echoReasoningContent", false);
        return root;
    }

    public static ObjectNode account(String playerId, String qqId, String displayName) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("playerId", playerId);
        node.put("qqId", qqId);
        node.put("displayName", displayName);
        return node;
    }

    public static Path writeJson(Path file, JsonNode node) {
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("写测试 JSON 失败", e);
        }
    }

    public static Path writeFacts(Path file, String json) {
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, json);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("写测试事实文件失败", e);
        }
    }

    public static Path writeConfig(Path file, ObjectNode node) {
        return writeJson(file, node);
    }

    public static AppConfig loadConfig(Path file) {
        return AppConfigLoader.load(file);
    }

    public static InboundMessage privateMessage(String qqId, String text, String dedupeKey) {
        return new InboundMessage("adapter-1", "bot-1", "msg-" + UUID.randomUUID(), qqId,
                InboundMessage.SessionType.PRIVATE, null, text, BASE, dedupeKey);
    }
}
