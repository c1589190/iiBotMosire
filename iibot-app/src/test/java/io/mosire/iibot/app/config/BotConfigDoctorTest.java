package io.mosire.iibot.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置医生：未知键隔离、过时键改名、非法值只报告、写回前备份。 */
class BotConfigDoctorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BotConfigDoctor doctor = new BotConfigDoctor();

    @Test
    void movesUnknownKeysIntoTheQuarantineBucketInsteadOfDroppingThem() throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {
                  "dataDir": "data",
                  "media": {"retentionDays": 3, "ttlDays": 9},
                  "napcatX": {"anything": [1, 2, 3]}
                }
                """);

        BotConfigDoctor.Diagnosis diagnosis = doctor.diagnose(root);

        assertThat(diagnosis.findings())
                .extracting(BotConfigDoctor.Finding::path)
                .contains("/media/ttlDays", "/napcatX");
        JsonNode repaired = diagnosis.repaired();
        assertThat(repaired.at("/media/ttlDays").isMissingNode()).isTrue();
        assertThat(repaired.at("/x-unknown/media.ttlDays").asInt()).isEqualTo(9);
        assertThat(repaired.at("/x-unknown/napcatX/anything/2").asInt()).isEqualTo(3);
        // 原来的键不留空壳
        assertThat(diagnosis.repairable()).isTrue();
    }

    @Test
    void renamesDeprecatedKeysFromTheMigrationTable() throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {
                  "dataDir": "data",
                  "summary": {"debounceMillis": 9000, "maxMessagesPerDigest": 12}
                }
                """);

        BotConfigDoctor.Diagnosis diagnosis = doctor.diagnose(root);
        JsonNode repaired = diagnosis.repaired();

        assertThat(repaired.at("/messageBoard/summaryDebounceMillis").asInt()).isEqualTo(9000);
        assertThat(repaired.at("/messageBoard/maxPreviewMessages").asInt()).isEqualTo(12);
        assertThat(repaired.at("/summary").isMissingNode()).isTrue();
        assertThat(diagnosis.findings())
                .filteredOn(finding -> finding.kind() == BotConfigDoctor.Finding.Kind.DEPRECATED_KEY)
                .hasSize(2);
    }

    @Test
    void reportsInvalidValuesButNeverRepairsThem() throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {"dataDir": "data", "media": {"retentionDays": 400}}
                """);

        BotConfigDoctor.Diagnosis diagnosis = doctor.diagnose(root);

        assertThat(diagnosis.hasInvalidValues()).isTrue();
        assertThat(diagnosis.findings())
                .filteredOn(finding -> finding.kind() == BotConfigDoctor.Finding.Kind.INVALID_VALUE)
                .isNotEmpty();
        // 400 天明显是配置错误：医生不猜，原样留给 loader 报错。
        assertThat(diagnosis.repaired().at("/media/retentionDays").asInt()).isEqualTo(400);
    }

    @Test
    void applyBacksUpBeforeRewriting(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("bot.json");
        String original = """
                {"dataDir": "data", "media": {"ttlDays": 9}}
                """;
        Files.writeString(file, original);

        BotConfigDoctor.Report report = doctor.repairFiles(List.of(file), true);

        assertThat(report.changed()).hasSize(1);
        Path backup = report.changed().get(0).backup();
        assertThat(backup).exists();
        assertThat(Files.readString(backup)).isEqualTo(original);
        JsonNode written = JSON.readTree(file.toFile());
        assertThat(written.at("/x-unknown/media.ttlDays").asInt()).isEqualTo(9);
        assertThat(written.at("/media/ttlDays").isMissingNode()).isTrue();
    }

    @Test
    void reportOnlyModeLeavesTheFileUntouched(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("bot.json");
        String original = "{\"dataDir\": \"data\", \"media\": {\"ttlDays\": 9}}\n";
        Files.writeString(file, original);

        BotConfigDoctor.Report report = doctor.repairFiles(List.of(file), false);

        assertThat(report.findings()).isNotEmpty();
        assertThat(report.changed()).isEmpty();
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    /**
     * 拿仓里真实运行的 bot2 配置做回归：schema 必须覆盖它的每一个键。
     *
     * <p>这条测试的价值不在于"配置对不对"，而在于**schema 与 loader 不会各说各话**——
     * 新增一个配置键却忘了加进 schema 时，这里会红。</p>
     */
    @Test
    void liveBot2ConfigIsFullyDescribedByTheSchema() throws IOException {
        Path live = Path.of("..", "..", "bot2", "config", "bot", "bot.json");
        if (!Files.exists(live)) {
            return; // 运行目录不随仓库走
        }
        BotConfigDoctor.Diagnosis diagnosis = doctor.diagnose(JSON.readTree(live.toFile()));
        assertThat(diagnosis.findings())
                .describedAs("bot2 配置里出现了 schema 没描述的键：%s", diagnosis.findings())
                .isEmpty();
    }

    @Test
    void schemaShippedInJarMatchesTheRepositoryCopy() throws IOException {
        Path repository = Path.of("..", "config", "schema", "bot-config.schema.json");
        if (!Files.exists(repository)) {
            return; // 单独发布 jar 时没有仓库副本，跳过
        }
        JsonNode jarCopy = JSON.readTree(
                BotConfigDoctor.class.getResourceAsStream(BotConfigDoctor.SCHEMA_RESOURCE));
        JsonNode repositoryCopy = JSON.readTree(repository.toFile());
        assertThat(jarCopy).isEqualTo(repositoryCopy);
    }
}
