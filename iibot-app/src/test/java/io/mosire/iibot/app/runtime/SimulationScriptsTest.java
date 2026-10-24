package io.mosire.iibot.app.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8 simulation 脚本的静态/离线校验。
 *
 * <p>只执行 {@code bash -n} 与本地 stub 调用；不启动 iiBot app、NapCat、真实 LLM，
 * 也不连接任何外部 MCP。</p>
 */
class SimulationScriptsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void everySimulationScriptPassesBashSyntaxCheck() throws Exception {
        Path scripts = repoRoot().resolve("simulation/scripts");
        List<Path> shellScripts;
        try (Stream<Path> list = Files.list(scripts)) {
            shellScripts = list.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sh"))
                    .sorted()
                    .toList();
        }
        assertThat(shellScripts).isNotEmpty()
                .extracting(path -> path.getFileName().toString())
                .contains("00-check.sh", "01-prepare.sh", "02-start.sh", "03-stop.sh",
                        "04-wait-mcp.sh", "05-init-control.sh",
                        "05a-init-control-plane.sh", "05b-register-players.sh",
                        "05c-open-turn.sh", "06-mcp.sh", "07-opening.sh", "lib.sh");
        for (Path script : shellScripts) {
            Process process = new ProcessBuilder("bash", "-n", script.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("bash -n 超时: %s", script.getFileName()).isTrue();
            assertThat(process.exitValue())
                    .as("bash -n %s 输出: %s", script.getFileName(), output).isZero();
        }
    }

    @Test
    void openingScriptPassesArgumentsVia0600TempFileAndNeverInvokesPublish()
            throws Exception {
        Path simRoot = tempDir.resolve("simulation");
        Path scripts = simRoot.resolve("scripts");
        Files.createDirectories(scripts);
        Files.copy(repoRoot().resolve("simulation/scripts/lib.sh"),
                scripts.resolve("lib.sh"));
        Files.copy(repoRoot().resolve("simulation/scripts/07-opening.sh"),
                scripts.resolve("07-opening.sh"));
        writeExecutable(scripts.resolve("06-mcp.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
                printf '%s\\n' "$@" > "$ROOT/observed-args.txt"
                file="${2#@}"
                [ -f "$file" ]
                cp "$file" "$ROOT/observed-payload.json"
                stat -c '%a' "$file" > "$ROOT/observed-mode.txt"
                printf '%s\\n' "$file" > "$ROOT/observed-file.txt"
                """);

        String title = "离线标题-0f8a";
        String body = "离线正文不得打印-4b21";
        String key = "offline-opening-key";
        Process process = new ProcessBuilder("bash",
                scripts.resolve("07-opening.sh").toString(), title, body, key)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(15, TimeUnit.SECONDS))
                .as("07-opening.sh 超时").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).doesNotContain(title, body);

        List<String> observedArgs = Files.readAllLines(
                simRoot.resolve("observed-args.txt"), StandardCharsets.UTF_8);
        assertThat(observedArgs).hasSize(2);
        assertThat(observedArgs.get(0)).isEqualTo("tf_publication_opening");
        assertThat(observedArgs.get(1)).startsWith("@");
        assertThat(observedArgs).noneMatch(arg -> arg.contains("tf_post_publish")
                || arg.contains("tf_post_decide"));

        Path observedFile = Path.of(Files.readString(
                simRoot.resolve("observed-file.txt"), StandardCharsets.UTF_8).strip());
        assertThat(Files.notExists(observedFile))
                .as("trap 应删除 0600 临时参数文件").isTrue();
        assertThat(Files.readString(simRoot.resolve("observed-mode.txt"),
                StandardCharsets.UTF_8).strip()).isEqualTo("600");
        JsonNode payload = JSON.readTree(Files.readString(
                simRoot.resolve("observed-payload.json"), StandardCharsets.UTF_8));
        assertThat(payload.path("title").asText()).isEqualTo(title);
        assertThat(payload.path("body").asText()).isEqualTo(body);
        assertThat(payload.path("idempotencyKey").asText()).isEqualTo(key);

        String script = Files.readString(scripts.resolve("07-opening.sh"),
                StandardCharsets.UTF_8);
        assertThat(script).contains("umask 077", "chmod 600 \"$args_file\"");
        assertThat(count(script, "\"$SIM_ROOT/scripts/06-mcp.sh\""))
                .as("开场脚本只应调用一次 MCP 客户端").isEqualTo(1);
        assertThat(script).contains(
                "\"$SIM_ROOT/scripts/06-mcp.sh\" tf_publication_opening \"@$args_file\"");
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file,
                PosixFilePermissions.fromString("rwx------"));
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(
                    current.resolve("simulation/scripts/07-opening.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("无法定位仓库根（simulation/scripts/07-opening.sh）");
    }
}
