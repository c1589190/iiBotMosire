package io.mosire.iibot.connect.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountInfoTest {

    @Test
    void exposesImmutableProtocolNeutralFieldsAndDisplayNameAlias() {
        AccountInfo info = new AccountInfo("123456", "小秘书");

        assertThat(info.accountId()).isEqualTo("123456");
        assertThat(info.nickname()).isEqualTo("小秘书");
        assertThat(info.displayName()).isEqualTo("小秘书");
    }

    @Test
    void rejectsNullAndBlankAccountIdAndNullNickname() {
        assertThatThrownBy(() -> new AccountInfo(null, "nick"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AccountInfo("  ", "nick"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
        assertThatThrownBy(() -> new AccountInfo("123456", null))
                .isInstanceOf(NullPointerException.class);
    }
}
