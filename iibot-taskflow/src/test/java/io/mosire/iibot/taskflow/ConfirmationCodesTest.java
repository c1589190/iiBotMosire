package io.mosire.iibot.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfirmationCodesTest {

    @Test
    void derivationIsStableAndSeparatesEntityTypes() {
        String decision = ConfirmationCodes.forDecisionDraft("same-id");
        String ticket = ConfirmationCodes.forActionTicket("same-id");

        assertThat(decision).hasSize(8).isEqualTo(
                ConfirmationCodes.forDecisionDraft("same-id"));
        assertThat(ticket).hasSize(8).isNotEqualTo(decision);
        assertThat(ConfirmationCodes.matches(decision.toLowerCase(), decision)).isTrue();
        assertThat(ConfirmationCodes.looksLikeShortCode(decision)).isTrue();
    }
}
