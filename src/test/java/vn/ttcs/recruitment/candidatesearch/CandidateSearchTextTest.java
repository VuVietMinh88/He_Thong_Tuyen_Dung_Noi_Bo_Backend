package vn.ttcs.recruitment.candidatesearch;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSearchTextTest {
    private static final char NBSP = (char) 0x00A0;
    private static final char NARROW_NBSP = (char) 0x202F;
    private static final char FIGURE_SPACE = (char) 0x2007;
    private static final char ZERO_WIDTH = (char) 0x200B;

    @Test
    void foldTableMapsEveryVietnameseLetterToOneAsciiLetterAndSpacesToASpace() {
        // 67 lower case and 67 upper case letters, 18 space separators, then 6 formatting characters translate() deletes.
        assertThat(CandidateSearchText.FOLD_FROM).hasSize(134 + 18 + 6);
        assertThat(CandidateSearchText.FOLD_TO).hasSize(134 + 18);
        assertThat(CandidateSearchText.FOLD_FROM.codePointCount(0, CandidateSearchText.FOLD_FROM.length()))
                .isEqualTo(CandidateSearchText.FOLD_FROM.length());
        assertThat(CandidateSearchText.FOLD_FROM.chars().distinct().count())
                .isEqualTo(CandidateSearchText.FOLD_FROM.length());
        assertThat(CandidateSearchText.FOLD_TO.substring(0, 134)).matches("[adeiouy]+");
        assertThat(CandidateSearchText.FOLD_TO.substring(134)).matches(" +");
        assertThat(CandidateSearchText.FOLD_FROM).contains(String.valueOf(NBSP), String.valueOf(NARROW_NBSP),
                String.valueOf(ZERO_WIDTH));
    }

    @Test
    void foldIgnoresAccentsCaseNormalFormAndRepeatedSpaces() {
        assertThat(CandidateSearchText.fold("  Nguyễn   VĂN  Đạt ")).isEqualTo("nguyen van dat");
        assertThat(CandidateSearchText.fold("NGUYỄN")).isEqualTo(CandidateSearchText.fold("nguyen"));
        String decomposed = Normalizer.normalize("Trần Thị Hồng", Normalizer.Form.NFD);
        assertThat(CandidateSearchText.fold(decomposed)).isEqualTo("tran thi hong");
    }

    @Test
    void foldTreatsNoBreakSpacesAsSpacesAndDropsZeroWidthCharacters() {
        assertThat(CandidateSearchText.fold(NBSP + "Nguyễn" + NARROW_NBSP + FIGURE_SPACE + "Văn" + NBSP))
                .isEqualTo("nguyen van");
        assertThat(CandidateSearchText.fold("Ng" + ZERO_WIDTH + "uyễn")).isEqualTo("nguyen");
    }

    @Test
    void wildcardsAndTheEscapeCharacterAreSearchedAsThemselves() {
        assertThat(CandidateSearchText.escape("50%_off!")).isEqualTo("50!%!_off!!");
        assertThat(CandidateSearchText.contains("a_b")).isEqualTo("%a!_b%");
        assertThat(CandidateSearchText.escape("!%")).isEqualTo("!!!%");
    }

    @Test
    void onlyPhoneLikeKeywordsBecomeDigitPatterns() {
        assertThat(CandidateSearchText.phoneDigits("+84 (912) 345-678")).isEqualTo("84912345678");
        assertThat(CandidateSearchText.phoneDigits("0912.345")).isEqualTo("0912345");
        assertThat(CandidateSearchText.phoneDigits("0912" + NBSP + "345")).isEqualTo("0912345");
        assertThat(CandidateSearchText.phoneDigits("12")).isNull();
        assertThat(CandidateSearchText.phoneDigits("An")).isNull();
        assertThat(CandidateSearchText.phoneDigits("091a")).isNull();
    }

    @Test
    void phoneKeywordsAreAlsoComparedInTheirNationalForm() {
        assertThat(CandidateSearchText.nationalPhone("+84 912 345")).isEqualTo("0912345");
        assertThat(CandidateSearchText.nationalPhone("(+84) 912-345")).isEqualTo("0912345");
        assertThat(CandidateSearchText.nationalPhone("0912 345")).isEqualTo("0912345");
        assertThat(CandidateSearchText.nationalPhone("84912345")).isEqualTo("84912345");
        assertThat(CandidateSearchText.nationalPhone("+6591234")).isEqualTo("6591234");
        assertThat(CandidateSearchText.nationalPhone("Lan")).isNull();
    }
}
