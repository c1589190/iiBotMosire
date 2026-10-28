package io.mosire.iibot.app.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 读取一个或多个 JSON 配置源，并按顺序做对象级深合并。
 *
 * <p>一个源可以是单个 {@code *.json} 文件，也可以是目录。目录只读取直接子文件中的
 * {@code *.json}，按文件名升序执行；更深的子目录不自动递归。所有源必须位于同一目录，
 * 以便配置中的相对路径有唯一的解析基准。</p>
 *
 * <p>合并规则：对象递归合并；数组、标量和 {@code null} 由后文件整体覆盖前文件。
 * 单文件内重复 key 仍由 strict duplicate detection 拒绝。合并过程为每个 JSON pointer
 * 保留最终来源文件和覆盖链，供配置错误与诊断使用。</p>
 */
public final class JsonConfigBundle {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private JsonConfigBundle() {
    }

    public static Bundle load(List<Path> sources) {
        if (sources == null || sources.isEmpty()) {
            throw ConfigException.invalid((Path) null, "/", "至少一个配置文件或配置目录",
                    "missing", "使用 --config 指定一个 *.json 文件或包含 *.json 的目录");
        }
        List<Path> files = resolveFiles(sources);
        if (files.isEmpty()) {
            throw ConfigException.invalid((Path) null, "/", "至少一个可读取的 *.json 文件",
                    "missing", "检查配置目录中是否存在非隐藏的 *.json 文件");
        }
        Path baseDir = commonParent(files);
        ObjectNode merged = MAPPER.createObjectNode();
        Map<String, Origin> origins = new LinkedHashMap<>();
        for (Path file : files) {
            JsonNode document = readDocument(file);
            if (!document.isObject()) {
                throw ConfigException.invalid(file, "/", "JSON object",
                        nodeType(document), "配置文件根必须是 JSON 对象");
            }
            recordOrigin(origins, "/", file);
            mergeInto(merged, (ObjectNode) document, "", file, origins);
        }
        return new Bundle(merged, baseDir, List.copyOf(files), Map.copyOf(origins));
    }

    public static Bundle load(Path source) {
        if (source == null) {
            throw ConfigException.invalid((Path) null, "/", "配置文件或配置目录", "missing",
                    "配置文件或配置目录不能为空");
        }
        return load(List.of(source));
    }

    private static List<Path> resolveFiles(List<Path> sources) {
        Set<Path> ordered = new LinkedHashSet<>();
        for (Path source : sources) {
            if (source == null) {
                throw ConfigException.invalid((Path) null, "/", "配置文件或配置目录", "null",
                        "配置源不能包含 null");
            }
            Path absolute = source.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                List<Path> directoryFiles = listJsonFiles(absolute);
                if (directoryFiles.isEmpty()) {
                    throw ConfigException.invalid(absolute, "/", "至少一个非隐藏 *.json 文件",
                            "empty directory",
                            "配置目录中没有 *.json 文件；首次启动应由 bootstrap 生成模板");
                }
                ordered.addAll(directoryFiles);
            } else if (Files.isRegularFile(absolute)) {
                if (!absolute.getFileName().toString().endsWith(".json")) {
                    throw ConfigException.invalid(absolute, "/", "*.json 文件",
                            "non-json file", "配置文件必须以 .json 结尾");
                }
                ordered.add(absolute);
            } else {
                throw ConfigException.invalid(absolute, "/", "存在的文件或目录",
                        "missing path", "检查 --config 路径是否拼写正确");
            }
        }
        return List.copyOf(ordered);
    }

    private static List<Path> listJsonFiles(Path directory) {
        try (var stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
            return List.copyOf(files);
        } catch (IOException failure) {
            throw ConfigException.invalid(directory, "/", "可读取目录", "I/O error",
                    "读取配置目录失败", failure);
        }
    }

    private static Path commonParent(List<Path> files) {
        Path parent = files.getFirst().getParent();
        if (parent == null) {
            throw ConfigException.invalid(files.getFirst(), "/", "具有父目录的文件",
                    "missing parent", "无法确定配置文件父目录");
        }
        for (Path file : files) {
            Path current = file.getParent();
            if (!Objects.equals(parent, current)) {
                throw ConfigException.invalid(file, "/", "与其它配置源相同的目录",
                        current == null ? "missing parent" : current.toString(),
                        "多个配置源必须位于同一目录才能解析相对路径: "
                                + parent + " vs " + current);
            }
        }
        return parent.toAbsolutePath().normalize();
    }

    private static JsonNode readDocument(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (JsonProcessingException failure) {
            String detail = failure.getOriginalMessage();
            if (detail != null && detail.toLowerCase().contains("duplicate field")) {
                throw ConfigException.invalid(file, "/", "唯一 JSON key", "duplicate key",
                        "配置文件包含重复字段；同一对象内不得重复声明同一个 key", failure);
            }
            String location = failure.getLocation() == null ? ""
                    : " line=" + failure.getLocation().getLineNr()
                    + " column=" + failure.getLocation().getColumnNr();
            throw ConfigException.invalid(file, "/", "合法 JSON",
                    "parse error" + location,
                    "配置文件不是合法 JSON；不输出原始内容以避免泄漏", failure);
        } catch (IOException failure) {
            throw ConfigException.invalid(file, "/", "可读取文件", "I/O error",
                    "读取配置文件失败", failure);
        }
    }

    private static ObjectNode mergeInto(ObjectNode base, ObjectNode overlay, String path,
                                        Path file, Map<String, Origin> origins) {
        ObjectNode result = base;
        var fields = overlay.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String childPointer = pointer(path, entry.getKey());
            JsonNode incoming = entry.getValue();
            JsonNode existing = result.get(entry.getKey());
            if (existing != null && existing.isObject() && incoming.isObject()) {
                recordOrigin(origins, childPointer, file);
                mergeInto((ObjectNode) existing, (ObjectNode) incoming, childPointer,
                        file, origins);
            } else {
                removeDescendantOrigins(origins, childPointer);
                result.set(entry.getKey(), incoming.deepCopy());
                recordTreeOrigins(origins, childPointer, incoming, file);
            }
        }
        return result;
    }

    private static void recordTreeOrigins(Map<String, Origin> origins, String pointer,
                                          JsonNode node, Path file) {
        recordOrigin(origins, pointer, file);
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    recordTreeOrigins(origins, pointer(pointer, entry.getKey()),
                            entry.getValue(), file));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                recordTreeOrigins(origins, pointer + "/" + index, node.get(index), file);
            }
        }
    }

    private static void recordOrigin(Map<String, Origin> origins, String pointer, Path file) {
        Origin previous = origins.get(pointer);
        List<Path> history = new ArrayList<>();
        if (previous != null) {
            history.addAll(previous.history());
        }
        history.add(file);
        origins.put(pointer, new Origin(file, List.copyOf(history)));
    }

    private static void removeDescendantOrigins(Map<String, Origin> origins, String pointer) {
        String prefix = pointer + "/";
        origins.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String pointer(String parent, String field) {
        String escaped = field.replace("~", "~0").replace("/", "~1");
        return parent + "/" + escaped;
    }

    public static String nodeType(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "missing";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isIntegralNumber()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return node.getNodeType().name().toLowerCase();
    }

    /** 一个 JSON pointer 的最终来源文件与该 pointer 被写入的完整文件顺序。 */
    public record Origin(Path file, List<Path> history) {
        public Origin {
            Objects.requireNonNull(file, "file");
            history = List.copyOf(Objects.requireNonNull(history, "history"));
        }
    }

    /** 合并后的根对象、相对路径基准目录、实际参与合并的文件和 pointer 来源。 */
    public record Bundle(JsonNode root, Path baseDir, List<Path> files,
                         Map<String, Origin> origins) {
        public Bundle {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(baseDir, "baseDir");
            files = List.copyOf(Objects.requireNonNull(files, "files"));
            origins = Map.copyOf(Objects.requireNonNull(origins, "origins"));
        }

        /** 返回精确 pointer 的来源；不存在时返回空。 */
        public Optional<Origin> originOf(String jsonPointer) {
            return Optional.ofNullable(origins.get(jsonPointer == null ? "/" : jsonPointer));
        }

        /** 返回精确或最近祖先 pointer 的来源，用于 missing 字段定位。 */
        public Optional<Path> nearestSourceOf(String jsonPointer) {
            String current = jsonPointer == null || jsonPointer.isBlank()
                    ? "/" : jsonPointer;
            while (true) {
                Origin origin = origins.get(current);
                if (origin != null) {
                    return Optional.of(origin.file());
                }
                int slash = current.lastIndexOf('/');
                if (slash <= 0) {
                    return Optional.empty();
                }
                current = current.substring(0, slash);
            }
        }
    }
}
