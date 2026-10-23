package io.mosire.iibot.app.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.iibot.taskflow.Visibility;
import io.mosire.iibot.taskflow.WorldFact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 JSON 世界事实文件映射为版本 1 的 {@link WorldFact} 列表。
 *
 * <p>文件形如：</p>
 * <pre>{@code
 * {"facts": [
 *   {"factId": "public-1", "content": "港口迷雾", "visibility": "PUBLIC"},
 *   {"factId": "dir-1", "content": "幕后黑手", "visibility": "DIRECTOR_ONLY"},
 *   {"factId": "p1-1", "content": "你的秘密", "visibility":
 *       {"type": "PLAYERS", "playerIds": ["player-1"]}}
 * ]}
 * }</pre>
 *
 * <p>未知字段、未知 visibility、空 PLAYERS 集合、重复 factId 都拒绝。装载器不解析玩家注册表：
 * 玩家 ID 是否真实存在由 {@code engine.loadWorldFacts} 之前的配置与后续业务校验负责；这里只
 * 做结构合法性。</p>
 */
public final class WorldFactsLoader {

    public static final long BOOTSTRAP_WORLD_VERSION = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of("facts");
    private static final Set<String> FACT_FIELDS = Set.of(
            "factId", "content", "visibility", "worldVersion");
    private static final Set<String> PLAYER_VISIBILITY_FIELDS = Set.of("type", "playerIds");

    private WorldFactsLoader() {
    }

    /** 读取并校验世界事实文件；返回不可变列表。 */
    public static List<WorldFact> load(Path factsFile) {
        if (factsFile == null) {
            throw new WorldFactsException("world.factsFile 不能为空");
        }
        Path file = factsFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new WorldFactsException("世界事实文件不存在或不是普通文件: " + file);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (JsonProcessingException e) {
            throw new WorldFactsException("世界事实文件不是合法 JSON: " + file);
        } catch (IOException e) {
            throw new WorldFactsException("读取世界事实文件失败: " + file);
        }
        if (root == null || !root.isObject()) {
            throw new WorldFactsException("世界事实根必须是 JSON 对象");
        }
        rejectUnknown(root, ROOT_FIELDS, "根对象");
        JsonNode factsNode = root.get("facts");
        if (factsNode == null || !factsNode.isArray() || factsNode.isEmpty()) {
            throw new WorldFactsException("facts 必须是非空数组");
        }

        List<WorldFact> facts = new ArrayList<>(factsNode.size());
        Set<String> ids = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode node : factsNode) {
            String location = "facts[" + index + "]";
            if (node == null || !node.isObject()) {
                throw new WorldFactsException(location + " 必须是 JSON 对象");
            }
            rejectUnknown(node, FACT_FIELDS, location);
            String factId = requiredText(node, "factId", location);
            if (!ids.add(factId)) {
                throw new WorldFactsException(location + " 的 factId 重复: " + factId);
            }
            String content = requiredText(node, "content", location);
            if (node.has("worldVersion")) {
                JsonNode version = node.get("worldVersion");
                if (!version.isIntegralNumber() || version.asLong() != BOOTSTRAP_WORLD_VERSION) {
                    throw new WorldFactsException(location + " 的 worldVersion 必须为 "
                            + BOOTSTRAP_WORLD_VERSION);
                }
            }
            Visibility visibility = parseVisibility(node.get("visibility"), location);
            facts.add(new WorldFact(factId, BOOTSTRAP_WORLD_VERSION, content, visibility));
            index++;
        }
        return List.copyOf(facts);
    }

    private static Visibility parseVisibility(JsonNode node, String location) {
        if (node == null || node.isNull()) {
            throw new WorldFactsException(location + " 缺少 visibility");
        }
        if (node.isTextual()) {
            String text = node.asText().strip();
            return switch (text) {
                case "PUBLIC" -> Visibility.PUBLIC;
                case "DIRECTOR_ONLY" -> Visibility.DIRECTOR_ONLY;
                case "PLAYERS" -> throw new WorldFactsException(
                        location + " 的 PLAYERS visibility 必须提供 playerIds 数组");
                default -> throw new WorldFactsException(location + " 的 visibility 非法: " + text);
            };
        }
        if (!node.isObject()) {
            throw new WorldFactsException(location + " 的 visibility 必须是字符串或对象");
        }
        rejectUnknown(node, PLAYER_VISIBILITY_FIELDS, location + ".visibility");
        String type = requiredText(node, "type", location + ".visibility");
        switch (type) {
            case "PUBLIC" -> {
                return Visibility.PUBLIC;
            }
            case "DIRECTOR_ONLY" -> {
                return Visibility.DIRECTOR_ONLY;
            }
            case "PLAYERS" -> {
                JsonNode playerIdsNode = node.get("playerIds");
                if (playerIdsNode == null || !playerIdsNode.isArray() || playerIdsNode.isEmpty()) {
                    throw new WorldFactsException(location
                            + " 的 PLAYERS visibility 需要非空 playerIds 数组");
                }
                Set<String> playerIds = new LinkedHashSet<>();
                int playerIndex = 0;
                for (JsonNode playerIdNode : playerIdsNode) {
                    if (playerIdNode == null || !playerIdNode.isTextual()
                            || playerIdNode.asText().isBlank()) {
                        throw new WorldFactsException(location + ".visibility.playerIds["
                                + playerIndex + "] 必须是非空字符串");
                    }
                    String playerId = playerIdNode.asText().strip();
                    if (!playerIds.add(playerId)) {
                        throw new WorldFactsException(location
                                + " 的 playerIds 重复: " + playerId);
                    }
                    playerIndex++;
                }
                return Visibility.forPlayers(playerIds);
            }
            default -> throw new WorldFactsException(location + " 的 visibility.type 非法: " + type);
        }
    }

    private static String requiredText(JsonNode parent, String field, String location) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new WorldFactsException(location + " 缺少非空字符串字段: " + field);
        }
        return node.asText().strip();
    }

    private static void rejectUnknown(JsonNode object, Set<String> allowed, String location) {
        Set<String> unknown = new LinkedHashSet<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new WorldFactsException(location + " 包含未知字段: " + String.join(", ", unknown));
        }
    }
}
