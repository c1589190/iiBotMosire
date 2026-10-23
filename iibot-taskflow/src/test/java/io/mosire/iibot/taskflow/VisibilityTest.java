package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 可见性判断是纯函数，不依赖聊天协议或提示词。 */
class VisibilityTest {

    @Test
    void publicPlayerSetAndDirectorOnlyHaveDistinctVisibilityRules() {
        Visibility players = Visibility.forPlayers(Set.of("p1"));
        assertThat(Visibility.PUBLIC.canSee("anyone", ActorRole.PLAYER)).isTrue();
        assertThat(players.canSee("p1", ActorRole.PLAYER)).isTrue();
        assertThat(players.canSee("p2", ActorRole.PLAYER)).isFalse();
        assertThat(Visibility.DIRECTOR_ONLY.canSee("p1", ActorRole.PLAYER)).isFalse();
        assertThat(Visibility.DIRECTOR_ONLY.canSee("director", ActorRole.DIRECTOR)).isTrue();
    }
}
