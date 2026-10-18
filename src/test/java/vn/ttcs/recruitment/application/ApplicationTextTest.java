package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 345 (story S4-06): the text rules of the withdrawal reason (and, from task 350, the referral note), the copies of
// JobPostingText's. The special characters are built from code points.
class ApplicationTextTest {

    // Only spaces of every kind (NBSP, figure space, narrow NBSP, NEL, Mongolian vowel separator, which PostgreSQL also
    // counts as spaces), zero-width and format characters (ZWSP, BOM, word joiner), line breaks, tabs and other
    // control characters: no visible character, so no text.
    @ParameterizedTest
    @ValueSource(ints = {0x00A0, 0x2007, 0x202F, 0x0085, 0x180E, 0x200B, 0xFEFF, 0x2060, 0x3000, 0x0000, 0x0007})
    void aTextWithoutAVisibleCharacterIsNoText(int invisible) {
        String character = Character.toString(invisible);
        assertThat(ApplicationText.invisibleToNull(character)).isNull();
        assertThat(ApplicationText.invisibleToNull(" " + character + "\n\t" + character + "\r\n")).isNull();
    }

    @Test
    void nullAndEmptyAreNoText() {
        assertThat(ApplicationText.invisibleToNull(null)).isNull();
        assertThat(ApplicationText.invisibleToNull("")).isNull();
        assertThat(ApplicationText.invisibleToNull("   \n ")).isNull();
    }

    // One visible character keeps the whole text exactly as sent: edges, inner spaces, line breaks and a format
    // character that joins an emoji included.
    @Test
    void aVisibleTextIsKeptAsSent() {
        String nbsp = Character.toString(0x00A0);
        String family = Character.toString(0x1F468) + Character.toString(0x200D) + Character.toString(0x1F469);
        for (String text : new String[] {"Đã tìm được công việc khác", "  Lý do\n\n  thứ hai  ", nbsp + "a" + nbsp,
                family, "<3 lương < 20 triệu"}) {
            assertThat(ApplicationText.invisibleToNull(text)).isSameAs(text);
        }
    }

    @Test
    void windowsAndOldMacLineBreaksBecomeUnixLineBreaks() {
        assertThat(ApplicationText.normalizeLineBreaks("Dòng 1\r\nDòng 2\rDòng 3\nDòng 4"))
                .isEqualTo("Dòng 1\nDòng 2\nDòng 3\nDòng 4");
        assertThat(ApplicationText.normalizeLineBreaks("\r\n\r\r\n")).isEqualTo("\n\n\n");
        assertThat(ApplicationText.normalizeLineBreaks("  thụt lề\n\n")).isEqualTo("  thụt lề\n\n");
        assertThat(ApplicationText.normalizeLineBreaks(null)).isNull();
    }

    // The withdrawal request applies both, line breaks first: a reason of only line breaks and spaces is no reason.
    @Test
    void theWithdrawalRequestNormalizesThenDropsAnInvisibleReason() {
        String code = "A".repeat(43);
        assertThat(new ApplicationWithdrawalRequest(code, "a@example.test", "Một\r\nHai", true).reason())
                .isEqualTo("Một\nHai");
        assertThat(new ApplicationWithdrawalRequest(code, "a@example.test",
                "\r\n" + Character.toString(0x200B) + "\r", true).reason()).isNull();
        var padded = new ApplicationWithdrawalRequest(Character.toString(0x00A0) + code + " ",
                Character.toString(0xFEFF) + "a@example.test ", null, true);
        assertThat(padded.trackingCode()).isEqualTo(code);
        assertThat(padded.email()).isEqualTo("a@example.test");
        assertThat(padded.toString()).doesNotContain(code, "a@example.test");
    }
}
