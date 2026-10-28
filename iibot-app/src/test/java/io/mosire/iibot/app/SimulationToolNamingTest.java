package io.mosire.iibot.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationToolNamingTest {

    private static final List<String> OLD_BOT_TOOL_NAMES = List.of(
            "bot_thread_list", "bot_thread_read", "bot_thread_history",
            "bot_thread_ack", "bot_message_get", "bot_message_thread",
            "bot_reply", "bot_forward_read", "bot_media_get",
            "bot_qq_recent", "bot_qq_history", "bot_friend_request_list",
            "bot_friend_request_decide", "bot_inbox_history",
            "bot_user_note_", "bot_source_weight_", "tf_inbox_binding_descriptor");

    @Test
    void descriptorScriptUsesUnifiedToolNameAndNoRawToken() throws Exception {
        Path script = repoRoot().resolve("simulation2/scripts/07-inbox-descriptor.sh");
        String text = Files.readString(script);

        assertThat(text).contains("qq_inbox_binding_descriptor");
        assertThat(text).doesNotContain("tf_inbox_binding_descriptor");
        assertThat(OLD_BOT_TOOL_NAMES).allSatisfy(oldName ->
                assertThat(text).doesNotContain(oldName));
        assertThat(text).contains("chmod 600", "adapterTokenRef");
    }

    @Test
    void simulationDocsOnlyReferenceUnifiedAgentToolNames() throws Exception {
        for (String file : List.of("simulation2/README.md",
                "simulation2/dsh/HANDOFF_PROMPT.md")) {
            String text = Files.readString(repoRoot().resolve(file));
            assertThat(OLD_BOT_TOOL_NAMES).allSatisfy(oldName ->
                    assertThat(text).doesNotContain(oldName));
            assertThat(text).contains("qq_conversation_list", "qq_conversation_read",
                    "qq_conversation_send", "qq_conversation_ack",
                    "qq_inbox_binding_descriptor", "inbox_rest");
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("simulation2"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位 iiBotMosire 仓库根");
    }
}
