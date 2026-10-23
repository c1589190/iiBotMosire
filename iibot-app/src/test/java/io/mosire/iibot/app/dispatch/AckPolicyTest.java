package io.mosire.iibot.app.dispatch;

import io.mosire.iibot.app.support.TestSupport;
import io.mosire.iibot.connect.core.InboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AckPolicyTest {

    private final AckPolicy policy = AckPolicy.configured(Map.of(
            "qq-player", AckPolicy.PLAYER_ACK_TEXT,
            "qq-director", AckPolicy.DIRECTOR_ACK_TEXT));

    @Test
    void onlyRegisteredPrivateNonSelfMessagesResolveAck() {
        assertThat(policy.ackFor(TestSupport.privateMessage("qq-player", "hello", "p")))
                .hasValue(AckPolicy.PLAYER_ACK_TEXT);
        assertThat(policy.ackFor(TestSupport.privateMessage("qq-director", "hello", "d")))
                .hasValue(AckPolicy.DIRECTOR_ACK_TEXT);
        assertThat(policy.ackFor(TestSupport.privateMessage("qq-unknown", "hello", "u")))
                .isEmpty();
        assertThat(policy.ackFor(TestSupport.groupMessage("qq-player", "g", "hello", "g1")))
                .isEmpty();
        assertThat(policy.ackFor(new InboundMessage("adapter-1", "bot-1", "m-self",
                "bot-1", InboundMessage.SessionType.PRIVATE, null, "hello",
                TestSupport.BASE, "self")))
                .isEmpty();
        assertThat(policy.ackFor(null)).isEmpty();
    }

    @Test
    void configuredPolicyIsFrozenAtConstruction() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("qq-player", AckPolicy.PLAYER_ACK_TEXT);
        AckPolicy frozen = AckPolicy.configured(mutable);
        mutable.put("qq-new", "不该出现");
        mutable.remove("qq-player");

        assertThat(frozen.ackFor(TestSupport.privateMessage("qq-player", "x", "k")))
                .hasValue(AckPolicy.PLAYER_ACK_TEXT);
        assertThat(frozen.ackFor(TestSupport.privateMessage("qq-new", "x", "k2")))
                .isEmpty();
    }
}
