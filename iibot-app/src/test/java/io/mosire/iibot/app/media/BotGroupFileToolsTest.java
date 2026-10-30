package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.GroupFileCapability;
import io.mosire.iibot.connect.core.MediaAsset;
import io.mosire.iibot.connect.core.QqGroupFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BotGroupFileToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final QqGroupFile JSON_FILE = new QqGroupFile(
            "file-json", "三国.json", 9, "qq-uploader", "上传者",
            Instant.parse("2026-10-29T01:00:00Z"), "102");
    private static final QqGroupFile IMAGE_FILE = new QqGroupFile(
            "file-image-secret", "地图.png", 3, "qq-uploader", "上传者",
            Instant.parse("2026-10-29T01:01:00Z"), "102");
    private static final QqGroupFile BINARY_FILE = new QqGroupFile(
            "file-bin-secret", "archive.bin", 4, "qq-uploader", "上传者",
            Instant.parse("2026-10-29T01:02:00Z"), "102");

    @TempDir
    Path tempDir;

    @Test
    void listsFilesThenReadsTextImageAndBinaryThroughShortRefs() throws Exception {
        GroupFileCapability provider = fakeProvider();
        BotGroupFileHandles handles = new BotGroupFileHandles();
        BotGroupFilesTool listTool = new BotGroupFilesTool(
                Map.of("bot-1", provider), handles);
        BotGroupFileGetTool getTool = new BotGroupFileGetTool(
                Map.of("bot-1", provider), handles, new BotMediaStore(tempDir));

        ToolResult listed = execute(listTool, Map.of(
                "groupId", "30003", "folderId", "/", "limit", 10));
        assertThat(listed.success()).isTrue();
        assertThat(listed.message())
                .contains("三国.json", "地图.png", "archive.bin", "上传者")
                .doesNotContain("file-json", "file-image-secret", "file-bin-secret",
                        "busid");
        JsonNode listJson = JSON.readTree(listed.message());
        assertThat(listJson.path("count").asInt()).isEqualTo(3);
        String jsonRef = refFor(listJson, "三国.json");
        String imageRef = refFor(listJson, "地图.png");
        String binaryRef = refFor(listJson, "archive.bin");

        ToolResult text = execute(getTool, Map.of("fileRef", jsonRef));
        assertThat(text.success()).isTrue();
        assertThat(text.message())
                .contains("\"contentKind\":\"text\"", "\"text\":\"{\\\"ok\\\":true}\"")
                .doesNotContain("file-json", "busid", "https://");
        assertThat(text.assetDocIds()).isEmpty();

        ToolResult image = execute(getTool, Map.of("fileRef", imageRef));
        assertThat(image.success()).isTrue();
        assertThat(image.assetDocIds()).hasSize(1);
        assertThat(image.message())
                .contains("\"contentKind\":\"image\"")
                .doesNotContain("file-image-secret", "https://");
        ToolAsset asset = new BotMediaStore(tempDir)
                .resolve(image.assetDocIds().getFirst()).orElseThrow();
        assertThat(asset.mediaType()).isEqualTo("image/png");
        assertThat(asset.bytes()).containsExactly(1, 2, 3);

        ToolResult binary = execute(getTool, Map.of("fileRef", binaryRef));
        assertThat(binary.success()).isTrue();
        assertThat(binary.message())
                .contains("\"contentKind\":\"binary\"", "\"contentOmitted\":true")
                .doesNotContain("file-bin-secret", "https://");
        assertThat(binary.assetDocIds()).isEmpty();
    }

    @Test
    void requiresAccountIdWhenMultipleGroupFileProvidersAreAvailable() {
        GroupFileCapability provider = fakeProvider();
        BotGroupFileHandles handles = new BotGroupFileHandles();
        BotGroupFilesTool tool = new BotGroupFilesTool(
                Map.of("bot-a", provider, "bot-b", provider), handles);

        ToolResult result = execute(tool, Map.of("groupId", "30003"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ACCOUNT_REQUIRED");
    }

    @Test
    void rejectsUnknownFileRef() {
        BotGroupFileGetTool tool = new BotGroupFileGetTool(
                Map.of("bot-1", fakeProvider()), new BotGroupFileHandles(),
                new BotMediaStore(tempDir));

        ToolResult result = execute(tool, Map.of("fileRef", "not-a-real-ref"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("FILE_REF_UNKNOWN");
    }

    private static String refFor(JsonNode listJson, String name) {
        for (JsonNode file : listJson.path("files")) {
            if (name.equals(file.path("name").asText())) {
                return file.path("fileRef").asText();
            }
        }
        throw new AssertionError("missing file " + name);
    }

    private static GroupFileCapability fakeProvider() {
        return new GroupFileCapability() {
            @Override
            public CompletableFuture<List<QqGroupFile>> groupFiles(String groupId,
                                                                    String folderId) {
                assertThat(groupId).isEqualTo("30003");
                assertThat(folderId).isEqualTo("/");
                return CompletableFuture.completedFuture(
                        List.of(JSON_FILE, IMAGE_FILE, BINARY_FILE));
            }

            @Override
            public CompletableFuture<MediaAsset> downloadGroupFile(String groupId,
                                                                    QqGroupFile file) {
                assertThat(groupId).isEqualTo("30003");
                if (file == JSON_FILE) {
                    return CompletableFuture.completedFuture(new MediaAsset(
                            "三国.json", "application/json",
                            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
                }
                if (file == IMAGE_FILE) {
                    return CompletableFuture.completedFuture(new MediaAsset(
                            "地图.png", "image/png", new byte[]{1, 2, 3}));
                }
                return CompletableFuture.completedFuture(new MediaAsset(
                        "archive.bin", "application/octet-stream",
                        new byte[]{4, 5, 6, 7}));
            }
        };
    }

    private static ToolResult execute(Object tool, Map<String, Object> args) {
        String name = ((io.mosire.agentlib.tool.AgentTool) tool).name();
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(name)
                .build();
        return ((io.mosire.agentlib.tool.AgentTool) tool).execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
