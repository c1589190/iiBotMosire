package io.mosire.iibot.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 配置医生：按 JSON Schema 找出「不认识的键」与「过时键」，并给出可自动执行的修复。
 *
 * <p>为什么要有它：{@code BotConfigLoader} 对未知键是 **fail-closed** 的（拼错一个键就起不来），
 * 而这些键往往只是升级后改名、或旧版本遗留。这里把两类**安全且可逆**的改动自动做掉：</p>
 *
 * <ul>
 *   <li>{@code UNKNOWN_KEY}：整棵子树搬到顶层 {@code x-unknown}（保留内容、不生效、随时可删）；</li>
 *   <li>{@code DEPRECATED_KEY}：按旧→新映射改名（{@code summary.debounceMillis} →
 *       {@code messageBoard.summaryDebounceMillis} 之类）。</li>
 * </ul>
 *
 * <p>取值越界、类型不对这类**语义**问题不猜，一律报 {@code INVALID_VALUE} 让启动照旧失败——
 * 自动把 0..365 之外的天数改成 7 只会掩盖真实的配置错误。</p>
 *
 * <p>写回永远先备份（{@code bot.json.bak-<时间戳>}）再原子替换，任何一步失败都不动原文件。</p>
 */
public final class BotConfigDoctor {

    /** schema 随 jar 发布（{@code config/schema/bot-config.schema.json} 的副本，测试保证一致）。 */
    public static final String SCHEMA_RESOURCE = "/bot-config.schema.json";

    /** 搬运未知键的去处：仍然留在文件里，但 loader 明确忽略。 */
    public static final String UNKNOWN_BUCKET = "x-unknown";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 旧键 → 新键（与 {@code config/README.md} 的迁移表同源）。 */
    private static final Map<String, String> RENAMES = renames();

    private final Schema schema;
    private final Clock clock;

    public BotConfigDoctor() {
        this(Clock.systemDefaultZone());
    }

    public BotConfigDoctor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schema = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(readSchemaStream());
    }

    /** 一次诊断的结果：{@code repaired} 是修好后的整棵树（没有可修项时与原树等价）。 */
    public record Diagnosis(List<Finding> findings, ObjectNode repaired) {

        public Diagnosis {
            findings = List.copyOf(findings);
            repaired = Objects.requireNonNull(repaired, "repaired");
        }

        public boolean repairable() {
            return findings.stream().anyMatch(Finding::repairable);
        }

        public boolean hasInvalidValues() {
            return findings.stream().anyMatch(finding -> !finding.repairable());
        }

        public List<Finding> repairableFindings() {
            return findings.stream().filter(Finding::repairable).toList();
        }
    }

    /** 单条发现。{@code path} 是 JSON Pointer（如 {@code /media/ttlDays}）。 */
    public record Finding(String path, Kind kind, String message) {

        public enum Kind {
            /** schema 里不存在这个键。 */
            UNKNOWN_KEY,
            /** 键在旧版本叫别的名字。 */
            DEPRECATED_KEY,
            /** 类型/范围/必填不满足：只报告，不自动改。 */
            INVALID_VALUE
        }

        public boolean repairable() {
            return kind == Kind.UNKNOWN_KEY || kind == Kind.DEPRECATED_KEY;
        }
    }

    public Diagnosis diagnose(JsonNode root) {
        Objects.requireNonNull(root, "root");
        ObjectNode work = root.isObject()
                ? ((ObjectNode) root).deepCopy()
                : JSON.createObjectNode();
        List<Finding> findings = new ArrayList<>();

        // 1) 先处理改名：它们同时也是「未知键」，先改名才不会被当成垃圾搬走。
        applyRenames(work, findings);

        // 2) 再按 schema 找剩下的未知键，整棵搬进 x-unknown。
        for (Error error : schema.validate(work)) {
            String pointer = pointerOf(error);
            if ("additionalProperties".equals(error.getKeyword())) {
                String property = error.getProperty();
                String target = property == null || property.isBlank()
                        ? pointer : join(pointer, property);
                if (moveToUnknownBucket(work, target)) {
                    findings.add(new Finding(target, Finding.Kind.UNKNOWN_KEY,
                            "schema 里没有这个键，已搬到 /" + UNKNOWN_BUCKET));
                }
            } else {
                findings.add(new Finding(pointer == null ? "" : pointer,
                        Finding.Kind.INVALID_VALUE, error.getMessage()));
            }
        }
        return new Diagnosis(findings, work);
    }

    /**
     * 写回修复后的配置：先备份，再原子替换。
     *
     * @return 备份文件路径
     */
    public Path apply(Path file, Diagnosis diagnosis) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(diagnosis, "diagnosis");
        Path absolute = file.toAbsolutePath().normalize();
        Path backup = absolute.resolveSibling(absolute.getFileName() + ".bak-"
                + STAMP.format(LocalDateTime.now(clock)));
        Files.copy(absolute, backup, StandardCopyOption.COPY_ATTRIBUTES);
        Path temporary = Files.createTempFile(absolute.getParent(), ".bot-config-", ".json");
        try {
            Files.writeString(temporary,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(diagnosis.repaired())
                            + System.lineSeparator());
            Files.move(temporary, absolute,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return backup;
    }

    /**
     * 对每个配置源文件单独诊断，必要时就地修好（写回前备份）。
     *
     * <p>逐文件而不是逐合并树，是因为写回必须落到确切的文件上；一个键来自哪个文件，
     * 只有分文件读才知道。</p>
     */
    public Report repairFiles(List<Path> files, boolean applyRepairs) throws IOException {
        List<FileReport> reports = new ArrayList<>();
        for (Path file : files) {
            JsonNode tree = JSON.readTree(file.toFile());
            Diagnosis diagnosis = diagnose(tree);
            Path backup = null;
            if (applyRepairs && diagnosis.repairable()) {
                backup = apply(file, diagnosis);
            }
            reports.add(new FileReport(file, diagnosis, backup));
        }
        return new Report(reports);
    }

    /** {@link #repairFiles} 的结果汇总。 */
    public record Report(List<FileReport> files) {

        public Report {
            files = List.copyOf(files);
        }

        public List<Finding> findings() {
            return files.stream()
                    .flatMap(file -> file.diagnosis().findings().stream())
                    .toList();
        }

        public List<FileReport> changed() {
            return files.stream().filter(file -> file.backup() != null).toList();
        }

        public boolean hasInvalidValues() {
            return files.stream().anyMatch(file -> file.diagnosis().hasInvalidValues());
        }
    }

    /** 单个文件的诊断与备份记录。 */
    public record FileReport(Path file, Diagnosis diagnosis, Path backup) {
    }

    /** 给人看的一行摘要。 */
    public static String describe(Finding finding) {
        return switch (finding.kind()) {
            case UNKNOWN_KEY -> "未知键 " + finding.path() + "（已搬入 /" + UNKNOWN_BUCKET + "）";
            case DEPRECATED_KEY -> "过时键 " + finding.path() + "（已改名）";
            case INVALID_VALUE -> "取值不合法 " + finding.path() + "：" + finding.message();
        };
    }

    private void applyRenames(ObjectNode root, List<Finding> findings) {
        RENAMES.forEach((oldPointer, newPointer) -> {
            JsonNode value = at(root, oldPointer);
            if (value == null) {
                return;
            }
            boolean overwrite = at(root, newPointer) == null;
            if (overwrite) {
                set(root, newPointer, value);
            }
            remove(root, oldPointer);
            findings.add(new Finding(oldPointer, Finding.Kind.DEPRECATED_KEY,
                    "新位置 " + newPointer + (overwrite ? "" : "（目标已存在，按新值优先）")));
        });
    }

    private boolean moveToUnknownBucket(ObjectNode root, String pointer) {
        JsonNode value = at(root, pointer);
        if (value == null) {
            return false;
        }
        String parentPointer = parentOf(pointer);
        if (UNKNOWN_BUCKET.equals(lastToken(pointer))
                || parentPointer.startsWith("/" + UNKNOWN_BUCKET)) {
            // x-unknown 内部允许任意键（schema 未限制），不该被反复搬运。
            return false;
        }
        String bucketKey = pointer.substring(1).replace('/', '.');
        remove(root, pointer);
        ObjectNode bucket = at(root, "/" + UNKNOWN_BUCKET) instanceof ObjectNode existing
                ? existing
                : root.putObject(UNKNOWN_BUCKET);
        bucket.set(bucketKey, value);
        return true;
    }

    private static JsonNode at(ObjectNode root, String pointer) {
        JsonNode current = root;
        for (String token : tokens(pointer)) {
            if (!current.isObject()) {
                return null;
            }
            current = current.get(token);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static void set(ObjectNode root, String pointer, JsonNode value) {
        List<String> path = tokens(pointer);
        ObjectNode current = root;
        for (int index = 0; index < path.size() - 1; index++) {
            JsonNode next = current.get(path.get(index));
            if (!(next instanceof ObjectNode object)) {
                next = current.putObject(path.get(index));
            }
            current = (ObjectNode) next;
        }
        current.set(path.get(path.size() - 1), value);
    }

    private static void remove(ObjectNode root, String pointer) {
        List<String> path = tokens(pointer);
        ObjectNode parent = root;
        for (int index = 0; index < path.size() - 1; index++) {
            JsonNode next = parent.get(path.get(index));
            if (!(next instanceof ObjectNode object)) {
                return;
            }
            parent = object;
        }
        parent.remove(path.get(path.size() - 1));
    }

    private static List<String> tokens(String pointer) {
        List<String> tokens = new ArrayList<>();
        if (pointer == null || pointer.isBlank() || "/".equals(pointer)) {
            return tokens;
        }
        for (String token : pointer.split("/")) {
            if (!token.isEmpty()) {
                tokens.add(token.replace("~1", "/").replace("~0", "~"));
            }
        }
        return tokens;
    }

    private static String parentOf(String pointer) {
        int index = pointer.lastIndexOf('/');
        return index <= 0 ? "" : pointer.substring(0, index);
    }

    private static String lastToken(String pointer) {
        int index = pointer.lastIndexOf('/');
        return index < 0 ? pointer : pointer.substring(index + 1);
    }

    /** networknt 2.x 用 {@link NodePath} 报位置，这里转成 JSON Pointer。 */
    private static String pointerOf(Error error) {
        NodePath path = error.getInstanceLocation();
        if (path == null || path.getNameCount() == 0) {
            return "";
        }
        StringBuilder pointer = new StringBuilder();
        for (int index = 0; index < path.getNameCount(); index++) {
            pointer.append('/').append(escape(path.getName(index)));
        }
        return pointer.toString();
    }

    private static String join(String pointer, String property) {
        return (pointer == null || pointer.isEmpty() ? "" : pointer) + "/" + escape(property);
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static InputStream readSchemaStream() {
        InputStream stream = BotConfigDoctor.class.getResourceAsStream(SCHEMA_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("缺少 schema 资源 " + SCHEMA_RESOURCE);
        }
        return stream;
    }


    private static Map<String, String> renames() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("/summary/debounceMillis", "/messageBoard/summaryDebounceMillis");
        table.put("/summary/maxMessagesPerDigest", "/messageBoard/maxPreviewMessages");
        table.put("/summary/maxChars", "/messageBoard/summaryMaxChars");
        table.put("/napcat", "/accounts/main/oneBot");
        table.put("/outbox", "/messageBoard/outbox");
        table.put("/weighting", "/messageBoard/weighting");
        return Map.copyOf(table);
    }

    /** 允许出现在根、但只有医生会写的键（loader 侧同步放行）。 */
    public static Set<String> doctorOwnedRootKeys() {
        return Set.of(UNKNOWN_BUCKET);
    }
}
