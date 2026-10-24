package io.mosire.iibot.taskflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlayerFacingTextGuardTest {

    @Test
    void stripsMarkdownAndBackstageFieldsFromPlayerChat() {
        String raw = """
                ## 当前情况
                **你的位置**：卡普亚北隘。
                draftId: 11111111-1111-1111-1111-111111111111
                turnId: 0b7ffb98-eabc-46a3-979f-38eccfef0188
                你的选择：
                - 查验车队
                - 先请示上级
                请发送 `/确认 K7Q2M9`
                """;

        String clean = PlayerFacingTextGuard.normalizeForChat(raw);

        assertThat(clean).contains("你的位置：卡普亚北隘");
        assertThat(clean).contains("· 查验车队");
        assertThat(clean).contains("· 先请示上级");
        assertThat(clean).contains("/确认 K7Q2M9");
        assertThat(clean).doesNotContain("##", "**", "draftId", "turnId", "11111111");
        assertThat(PlayerFacingTextGuard.remainingBackstageLeaks(clean)).isEmpty();
    }

    @Test
    void removesAnEntireLineThatOnlyLeaksToolsOrPaths() {
        String raw = """
                你先留在城里观察粮价。
                workspace: /home/cna/iiBotMosire/simulation/runtime
                请打开 player/registration-form-v2.md 并阅读。
                粮商今天没有开门。
                """;

        String clean = PlayerFacingTextGuard.normalizeForChat(raw);

        assertThat(clean).contains("你先留在城里观察粮价");
        assertThat(clean).contains("粮商今天没有开门");
        assertThat(clean).doesNotContain("workspace", "registration-form", "/home/");
    }

    @Test
    void preservesNormalChineseNarrative() {
        String raw = "冬雨落在石阶上，税关外排着三支车队。你可以先查验文书，或让书吏去问军需官。";

        assertThat(PlayerFacingTextGuard.normalizeForChat(raw)).isEqualTo(raw);
    }

    @Test
    void dropCodeFenceMarkersButKeepReadableInnerText() {
        String raw = """
                ```text
                这是给玩家看的结论。
                ```
                """;

        String clean = PlayerFacingTextGuard.normalizeForChat(raw);

        assertThat(clean).isEqualTo("这是给玩家看的结论。");
        assertThat(clean).doesNotContain("```");
    }

    @Test
    void emptyUnsafeInputFallsBackToReadableChinese() {
        String clean = PlayerFacingTextGuard.normalizeForChat("""
                draftId: 11111111-1111-1111-1111-111111111111
                workspace: /home/cna/secret
                """);

        assertThat(clean).contains("你再说一次");
        assertThat(clean).doesNotContain("draftId", "/home/");
    }
}
