package io.mosire.iibot.app.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotMediaUploadToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void storesUploadedBytesAndReturnsOnlyOpaqueAssetRef() throws Exception {
        BotMediaStore store = new BotMediaStore(tempDir);
        BotMediaUploadTool tool = new BotMediaUploadTool(store);
        String encoded = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        ToolResult result = execute(tool, Map.of(
                "fileName", "image.png",
                "mediaType", "image/png",
                "dataBase64", encoded));

        assertThat(result.success()).isTrue();
        JsonNode data = JSON.readTree(result.message());
        String assetRef = data.path("assetRef").asText();
        assertThat(assetRef).startsWith("asset_");
        assertThat(data.path("size").asInt()).isEqualTo(3);
        assertThat(result.message()).doesNotContain(tempDir.toString(), "dataBase64");
        assertThat(store.resolve(assetRef).orElseThrow().bytes())
                .containsExactly(1, 2, 3);
    }

    @Test
    void rejectsInvalidBase64AndOversizedEncodedInput() {
        BotMediaUploadTool tool = new BotMediaUploadTool(new BotMediaStore(tempDir));

        ToolResult invalid = execute(tool, Map.of(
                "fileName", "x.bin", "dataBase64", "not-base64"));
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.code()).isEqualTo("INVALID_ARGUMENTS");

        ToolResult tooLarge = execute(tool, Map.of(
                "fileName", "x.bin", "dataBase64", "A".repeat(15_000_000)));
        assertThat(tooLarge.success()).isFalse();
        assertThat(tooLarge.code()).isEqualTo("ASSET_TOO_LARGE");
    }

    private static ToolResult execute(BotMediaUploadTool tool, Map<String, Object> args) {
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();
        return tool.execute(new ToolContext(
                AccessToken.DEFAULT, permissions, Map.of(), args));
    }
}
