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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 读取一个或多个 JSON 配置源，并按顺序做对象级深合并。
 *
 * <p>一个源可以是单个 {@code *.json} 文件，也可以是目录。目录只读取直接子文件中的
 * {@code *.json}，按文件名升序执行；更深的子目录不自动递归。所有源必须位于同一目录，
 * 以便配置中的相对路径有唯一的解析基准。</p>
 *
 * <p>合并规则：对象递归合并；数组、标量和 {@code null} 由后文件整体覆盖前文件。
 * 单文件内重复 key 仍由 strict duplicate detection 拒绝。</p>
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
            throw new ConfigException("至少需要一个配置文件或配置目录");
        }
        List<Path> files = resolveFiles(sources);
        if (files.isEmpty()) {
            throw new ConfigException("配置源中没有可读取的 *.json 文件");
        }
        Path baseDir = commonParent(files);
        ObjectNode merged = MAPPER.createObjectNode();
        for (Path file : files) {
            JsonNode document = readDocument(file);
            if (!document.isObject()) {
                throw new ConfigException("配置文件根必须是 JSON 对象: " + file);
            }
            merged = merge(merged, (ObjectNode) document);
        }
        return new Bundle(merged, baseDir, List.copyOf(files));
    }

    public static Bundle load(Path source) {
        if (source == null) {
            throw new ConfigException("配置文件或配置目录不能为空");
        }
        return load(List.of(source));
    }

    private static List<Path> resolveFiles(List<Path> sources) {
        Set<Path> ordered = new LinkedHashSet<>();
        for (Path source : sources) {
            if (source == null) {
                throw new ConfigException("配置文件或配置目录不能包含 null");
            }
            Path absolute = source.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                List<Path> directoryFiles = listJsonFiles(absolute);
                if (directoryFiles.isEmpty()) {
                    throw new ConfigException("配置目录中没有 *.json 文件: " + absolute);
                }
                ordered.addAll(directoryFiles);
            } else if (Files.isRegularFile(absolute)) {
                if (!absolute.getFileName().toString().endsWith(".json")) {
                    throw new ConfigException("配置文件必须以 .json 结尾: " + absolute);
                }
                ordered.add(absolute);
            } else {
                throw new ConfigException("配置文件或配置目录不存在: " + absolute);
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
            throw new ConfigException("读取配置目录失败: " + directory);
        }
    }

    private static Path commonParent(List<Path> files) {
        Path parent = files.getFirst().getParent();
        if (parent == null) {
            throw new ConfigException("无法确定配置文件父目录: " + files.getFirst());
        }
        for (Path file : files) {
            Path current = file.getParent();
            if (!Objects.equals(parent, current)) {
                throw new ConfigException(
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
            if (detail != null && detail.contains("Duplicate field")) {
                throw new ConfigException(
                        "配置文件包含重复字段（配置键/route 名必须唯一）: " + file);
            }
            throw new ConfigException("配置文件不是合法 JSON: " + file);
        } catch (IOException failure) {
            throw new ConfigException("读取配置文件失败: " + file);
        }
    }

    private static ObjectNode merge(ObjectNode base, ObjectNode overlay) {
        ObjectNode result = base.deepCopy();
        overlay.fields().forEachRemaining(entry -> {
            JsonNode existing = result.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (existing != null && existing.isObject() && incoming.isObject()) {
                result.set(entry.getKey(), merge((ObjectNode) existing,
                        (ObjectNode) incoming));
            } else {
                result.set(entry.getKey(), incoming.deepCopy());
            }
        });
        return result;
    }

    /** 合并后的根对象、相对路径基准目录，以及实际参与合并的文件。 */
    public record Bundle(JsonNode root, Path baseDir, List<Path> files) {
        public Bundle {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(baseDir, "baseDir");
            files = List.copyOf(Objects.requireNonNull(files, "files"));
        }
    }
}
