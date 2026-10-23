package vn.ttcs.recruitment.candidate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateTextTest {
    private static final String NBSP = String.valueOf((char) 0x00A0);
    private static final String FIGURE_SPACE = String.valueOf((char) 0x2007);
    private static final String NARROW_NBSP = String.valueOf((char) 0x202F);
    private static final String ZERO_WIDTH = String.valueOf((char) 0x200B);

    @Test
    void invisibleCharactersAtTheEndsAreTrimmedAndANothingValueIsNull() {
        assertThat(CandidateText.trim(NBSP + " Java" + NARROW_NBSP + ZERO_WIDTH)).isEqualTo("Java");
        assertThat(CandidateText.trim(NBSP + FIGURE_SPACE + NARROW_NBSP + ZERO_WIDTH + "\t")).isNull();
        assertThat(CandidateText.trim(null)).isNull();
        assertThat(CandidateText.trim("a" + NBSP + "b")).isEqualTo("a" + NBSP + "b");
    }

    @Test
    void spaceRunsCollapseButFormattingCharactersStay() {
        assertThat(CandidateText.collapseSpaces("Java" + NBSP + FIGURE_SPACE + " \tDev")).isEqualTo("Java Dev");
        assertThat(CandidateText.collapseSpaces("Ja" + ZERO_WIDTH + "va")).isEqualTo("Ja" + ZERO_WIDTH + "va");
        assertThat(CandidateText.invisible(0x00A0)).isTrue();
        assertThat(CandidateText.invisible(0x200B)).isTrue();
        assertThat(CandidateText.invisible('a')).isFalse();
    }
}
