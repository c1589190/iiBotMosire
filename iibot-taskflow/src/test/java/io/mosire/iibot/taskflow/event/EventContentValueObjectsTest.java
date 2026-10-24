package io.mosire.iibot.taskflow.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 事件内容值对象的严格校验：JSON 与 MARKDOWN/TEXT 载体二选一，版本/必填字段 fail-closed。
 */
class EventContentValueObjectsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T05:00:00Z");

    @Test
    void jsonContentAcceptsOnlyJsonCarrier() {
        EventContent json = new EventContent(EventContentType.JSON, "{\"a\":1}", null);
        assertThat(json.body()).isEqualTo("{\"a\":1}");

        assertThatThrownBy(() -> new EventContent(null, "{}", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventContent(EventContentType.JSON, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 事件正文不能为空");
        assertThatThrownBy(() -> new EventContent(EventContentType.JSON, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 事件正文不能为空");
        assertThatThrownBy(() -> new EventContent(EventContentType.JSON, "{}", "text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能同时提供 text");
    }

    @Test
    void textAndMarkdownContentAcceptOnlyTextCarrier() {
        EventContent markdown = new EventContent(EventContentType.MARKDOWN, null, "# hi");
        assertThat(markdown.body()).isEqualTo("# hi");
        EventContent text = new EventContent(EventContentType.TEXT, null, "plain");
        assertThat(text.body()).isEqualTo("plain");

        assertThatThrownBy(() -> new EventContent(EventContentType.MARKDOWN, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件正文不能为空");
        assertThatThrownBy(() -> new EventContent(EventContentType.TEXT, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件正文不能为空");
        assertThatThrownBy(() -> new EventContent(EventContentType.TEXT, "{}", "plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能同时提供 json");
    }

    @Test
    void appendRequestRequiresPositiveVersionAndRequiredHeaders() {
        EventAppendRequest valid = request(1);
        assertThat(valid.eventVersion()).isEqualTo(1);
        assertThat(valid.correlationId()).isEqualTo("corr");
        assertThat(valid.turnId()).isEqualTo("turn");

        assertThatThrownBy(() -> request(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion 必须为正");
        assertThatThrownBy(() -> request(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion 必须为正");
        assertThatThrownBy(() -> new EventAppendRequest(
                null, "TYPE", "agent", "corr", "turn", 1, EventVisibility.GM,
                new EventContent(EventContentType.TEXT, null, "body"), "{}"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventAppendRequest(
                NOW, " ", "agent", "corr", "turn", 1, EventVisibility.GM,
                new EventContent(EventContentType.TEXT, null, "body"), "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType 不能为空");
        assertThatThrownBy(() -> new EventAppendRequest(
                NOW, "TYPE", null, "corr", "turn", 1, EventVisibility.GM,
                new EventContent(EventContentType.TEXT, null, "body"), "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent 不能为空");
        assertThatThrownBy(() -> new EventAppendRequest(
                NOW, "TYPE", "agent", "corr", "turn", 1, null,
                new EventContent(EventContentType.TEXT, null, "body"), "{}"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventAppendRequest(
                NOW, "TYPE", "agent", "corr", "turn", 1, EventVisibility.GM,
                null, "{}"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventAppendRequest(
                NOW, "TYPE", "agent", "corr", "turn", 1, EventVisibility.GM,
                new EventContent(EventContentType.TEXT, null, "body"), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadJson 不能为空");
    }

    @Test
    void appendRequestNormalizesOptionalHeaders() {
        EventAppendRequest request = new EventAppendRequest(
                NOW, "TYPE", "agent", null, "  ", 1, EventVisibility.GM,
                new EventContent(EventContentType.TEXT, null, "body"), "{}");
        assertThat(request.correlationId()).isEmpty();
        assertThat(request.turnId()).isNull();
    }

    @Test
    void storedEventEnforcesContentFlagConsistencyAndNonNegativeVersion() {
        EventContent content = new EventContent(EventContentType.TEXT, null, "body");
        StoredEvent complete = new StoredEvent(1, NOW, "TYPE", "agent", "", null,
                0, EventVisibility.GM, "hash", false, "{}", content);
        assertThat(complete.eventVersion()).isZero();
        assertThat(complete.content()).isSameAs(content);

        StoredEvent legacy = new StoredEvent(1, NOW, "TYPE", "agent", "", null,
                0, EventVisibility.GM, "", true, "{}", null);
        assertThat(legacy.contentMissing()).isTrue();
        assertThat(legacy.content()).isNull();

        assertThatThrownBy(() -> new StoredEvent(0, NOW, "TYPE", "agent", "", null,
                0, EventVisibility.GM, "", true, "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq 必须为正");
        assertThatThrownBy(() -> new StoredEvent(1, NOW, "TYPE", "agent", "", null,
                -1, EventVisibility.GM, "", true, "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion 不能为负");
        assertThatThrownBy(() -> new StoredEvent(1, NOW, "TYPE", "agent", "", null,
                0, EventVisibility.GM, "hash", true, "{}", content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentMissing 事件不能携带正文");
        assertThatThrownBy(() -> new StoredEvent(1, NOW, "TYPE", "agent", "", null,
                0, EventVisibility.GM, "hash", false, "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完整事件必须携带正文");
    }

    private static EventAppendRequest request(long eventVersion) {
        return new EventAppendRequest(
                NOW, "TYPE", "GM:gm", "corr", "turn", eventVersion, EventVisibility.GM,
                new EventContent(EventContentType.TEXT, null, "body"), "{}");
    }
}
