package io.mosire.iibot.app.media;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.iibot.connect.core.MediaAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotMediaNoteToolTest {

    @TempDir
    Path tempDir;

    @Test
    void notePersistsAcrossStoreInstancesAndCanBeCleared() {
        BotMediaStore media = new BotMediaStore(tempDir);
        String assetId = media.store(new MediaAsset("a.png", "image/png", new byte[]{1, 2, 3}));
        BotMediaNoteTool tool = new BotMediaNoteTool(media, new BotMediaNoteStore(tempDir));
        AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allow(tool.name())
                .build();

        ToolResult set = tool.execute(new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), Map.of("assetId", assetId, "note", "这是同一张图")));
        assertThat(set.success()).isTrue();

        BotMediaNoteTool reopened = new BotMediaNoteTool(
                media, new BotMediaNoteStore(tempDir));
        ToolResult get = reopened.execute(new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), Map.of("assetId", assetId)));
        assertThat(get.success()).isTrue();
        assertThat(get.message()).contains("这是同一张图");

        ToolResult clear = reopened.execute(new ToolContext(AccessToken.DEFAULT, permissions,
                Map.of(), Map.of("assetId", assetId, "note", "")));
        assertThat(clear.success()).isTrue();
        assertThat(clear.message()).contains("\"cleared\":true");
    }
}
