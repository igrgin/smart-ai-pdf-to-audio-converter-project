package dev.audiobook.platform.library.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HttpByteRangeTest {

    @Test
    void parsesClosedOpenAndSuffixByteRangesAgainstTheKnownEntityLength() {
        assertThat(HttpByteRange.parse("bytes=2-5", 10)).isEqualTo(new HttpByteRange(2, 5));
        assertThat(HttpByteRange.parse("bytes=7-", 10)).isEqualTo(new HttpByteRange(7, 9));
        assertThat(HttpByteRange.parse("bytes=-4", 10)).isEqualTo(new HttpByteRange(6, 9));
        assertThat(HttpByteRange.parse("bytes=7-99", 10)).isEqualTo(new HttpByteRange(7, 9));
    }

    @Test
    void rejectsMultipleMalformedAndUnsatisfiedByteRanges() {
        for (String range : java.util.List.of(
                "bytes=0-1,4-5", "items=0-1", "bytes=10-11", "bytes=4-2", "bytes=-0")) {
            assertThatThrownBy(() -> HttpByteRange.parse(range, 10))
                    .isInstanceOf(UnsatisfiedRangeException.class);
        }
    }
}
