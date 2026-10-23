package io.mosire.iibot.connect.napcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextSplitterTest {

    @Test
    void neverSplitsUnicodeSurrogatePairs() {
        List<String> chunks = TextSplitter.split(List.of("a😀b😀c"), 2);

        assertThat(chunks).containsExactly("a😀", "b😀", "c");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).doesNotContain("\uFFFD"));
    }

    @Test
    void prefersNewlineBoundary() {
        List<String> chunks = TextSplitter.split(List.of("abc\ndefgh"), 5);

        assertThat(chunks).containsExactly("abc\n", "defgh");
    }

    @Test
    void keepsMultipleInputSegmentsInOrder() {
        List<String> chunks = TextSplitter.split(List.of("one", "two"), 100);

        assertThat(chunks).containsExactly("one", "two");
    }
}
