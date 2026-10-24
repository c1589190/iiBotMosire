package io.mosire.iibot.app.event.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 事件正文/元数据的最后一道脱敏。
 *
 * <p>规则与旧的审计脱敏保持一致并扩展到嵌套 JSON：敏感键名整值替换，已知密钥值在任意
 * 文本中替换；不保留 token/API key 的可恢复片段。写入前先脱敏、再计算 hash，确保库内
 * 正文与 hash 始终对应。</p>
 */
final class EventContentSanitizer {

    static final String PLACEHOLDER = "[REDACTED]";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final List<String> secrets;

    EventContentSanitizer(Collection<String> secrets) {
        this.secrets = secrets == null ? List.of() : secrets.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    String sanitizeJson(String json, boolean requireObject) {
        return sanitizeJson(json, requireObject, true);
    }

    /**
     * @param redactSensitiveKeys 完整领域快照必须关闭字段名级替换，保留任意业务 Map key；
     *                            已知 secret 值仍会替换。
     */
    String sanitizeJson(String json, boolean requireObject, boolean redactSensitiveKeys) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("[EVENT_JSON_EMPTY] JSON 正文不能为空");
        }
        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("[EVENT_JSON_INVALID] JSON 正文无法解析");
        }
        if (parsed == null || parsed.isMissingNode()) {
            throw new IllegalArgumentException("[EVENT_JSON_INVALID] JSON 正文不能为空");
        }
        if (requireObject && !parsed.isObject()) {
            throw new IllegalArgumentException("[EVENT_JSON_OBJECT_REQUIRED] 元数据必须是 JSON 对象");
        }
        try {
            return MAPPER.writeValueAsString(sanitizeNode(parsed, redactSensitiveKeys));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("[EVENT_JSON_SANITIZE_FAILED] JSON 正文脱敏失败");
        }
    }

    String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("[EVENT_TEXT_EMPTY] 文本正文不能为空");
        }
        return sanitizeKnownSecrets(text);
    }

    /** 脱敏 header 中的短文本（eventType/agent/correlation/turnId），保持 null 语义。 */
    String sanitizeHeaderValue(String value) {
        return value == null ? null : sanitizeKnownSecrets(value);
    }

    private JsonNode sanitizeNode(JsonNode node, boolean redactSensitiveKeys) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.properties().forEach(entry -> {
                // 普通事件保留字段名级脱敏；完整领域快照是无损结构，业务 Map key
                // （如 worldFacts 的 factId）不能被当作凭据字段改写。
                String key = redactSensitiveKeys
                        ? sanitizeKnownSecrets(entry.getKey()) : entry.getKey();
                if (redactSensitiveKeys && sensitiveKey(key)) {
                    result.put(key, PLACEHOLDER);
                } else {
                    result.set(key, sanitizeNode(entry.getValue(), redactSensitiveKeys));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(value -> result.add(sanitizeNode(value, redactSensitiveKeys)));
            return result;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeKnownSecrets(node.asText()));
        }
        return node;
    }

    /**
     * 脱敏已知 secret 值。
     *
     * <p>先枚举每个 secret 的所有出现位置，在长度等于文本的 boolean 掩码上标记覆盖区间；
     * 随后把连续覆盖区间统一替换为一个占位符。这样任意起点重叠（如 abc 与 bcdef 覆盖
     * abcdef）都会完整覆盖，且结果与 secret 集合顺序无关。掩码只在首次命中后分配，内存
     * 上限与单段文本长度线性相关；调用方已在入口限制 payload/content 的 4MiB 字节上限。</p>
     */
    private String sanitizeKnownSecrets(String value) {
        if (value == null || value.isEmpty() || secrets.isEmpty()) {
            return value;
        }
        boolean anyMatch = false;
        for (String secret : secrets) {
            if (value.indexOf(secret) >= 0) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            return value;
        }

        boolean[] covered = new boolean[value.length()];
        for (String secret : secrets) {
            int from = 0;
            while (from <= value.length() - secret.length()) {
                int hit = value.indexOf(secret, from);
                if (hit < 0) {
                    break;
                }
                Arrays.fill(covered, hit, hit + secret.length(), true);
                from = hit + 1;
            }
        }

        StringBuilder redacted = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            if (!covered[index]) {
                redacted.append(value.charAt(index));
                index++;
                continue;
            }
            while (index < value.length() && covered[index]) {
                index++;
            }
            redacted.append(PLACEHOLDER);
        }
        return redacted.toString();
    }

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "token", "accesstoken", "refreshtoken", "authtoken", "bearertoken",
            "secret", "secretkey", "clientsecret",
            "password", "passwd",
            "authorization", "credential", "credentials", "cookie", "bearer",
            "apikey", "xapikey", "accesskey", "privatekey");

    /**
     * 只把“整个字段名就是凭据名”视为敏感；不能对任意业务 Map key 做子串匹配，
     * 否则 factId 如 sim-director-secret 会破坏完整快照结构。
     */
    static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(".", "");
        return SENSITIVE_FIELD_NAMES.contains(normalized);
    }
}
