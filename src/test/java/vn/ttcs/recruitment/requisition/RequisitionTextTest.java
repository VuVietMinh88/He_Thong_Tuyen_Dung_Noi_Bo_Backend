package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// RequisitionText.invisibleToNull decides which texts of a request body count as not given.
class RequisitionTextTest {
    // The 26 code points the team's PostgreSQL test database counts as [[:space:]], so the V13, V15 and V17 CHECKs
    // refuse a text made only of them. String.isBlank() misses U+0085, U+00A0, U+180E, U+2007 and U+202F.
    private static final int[] POSTGRES_SPACES = IntStream.concat(
            IntStream.of(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x180E),
            IntStream.concat(IntStream.rangeClosed(0x2000, 0x200A),
                    IntStream.of(0x2028, 0x2029, 0x202F, 0x205F, 0x3000))).toArray();
    // Show nothing either: other control characters and format characters (Unicode type Cf), zero-width ones included.
    private static final int[] OTHER_INVISIBLES = {0x01, 0x1F, 0x7F, 0x9F, 0xAD, 0x061C, 0x200B, 0x200C, 0x200D,
            0x200E, 0x2060, 0x2066, 0xFEFF};

    @Test
    void aTextWithoutAVisibleCharacterIsNull() {
        assertThat(POSTGRES_SPACES).hasSize(26);
        assertThat(RequisitionText.invisibleToNull(null)).isNull();
        assertThat(RequisitionText.invisibleToNull("")).isNull();
        for (int[] characters : List.of(POSTGRES_SPACES, OTHER_INVISIBLES)) {
            for (int character : characters) {
                assertThat(RequisitionText.invisibleToNull(codePoints(character, character)))
                        .as(Integer.toHexString(character)).isNull();
            }
            assertThat(RequisitionText.invisibleToNull(codePoints(characters))).isNull();
        }
        assertThat(RequisitionText.invisibleToNull(codePoints(0xA0, '\n', 0x200B, ' ', 0x202F, 0xFEFF, '\t'))).isNull();
    }

    @Test
    void aTextWithOneVisibleCharacterIsKeptExactly() {
        for (String text : List.of("a", ".", "0", "Đ", " \n  Dòng 2\t\n", codePoints(0xA0, 'x', 0xA0),
                codePoints(0x200B, '-', 0xFEFF), codePoints(0x2007, 0x1F600, '\n'), codePoints(0x3000, 0x4E2D))) {
            assertThat(RequisitionText.invisibleToNull(text)).as(text).isSameAs(text);
        }
    }

    private static String codePoints(int... characters) {
        return new String(characters, 0, characters.length);
    }
}
