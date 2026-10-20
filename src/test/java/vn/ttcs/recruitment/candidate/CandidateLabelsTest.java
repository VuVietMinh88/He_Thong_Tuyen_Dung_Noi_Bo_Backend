package vn.ttcs.recruitment.candidate;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.common.ApiException;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateLabelsTest {
    @Test
    void theKeyIgnoresCaseAccentsNormalFormAndSpaces() {
        assertThat(CandidateLabels.key("Kế  Toán")).isEqualTo("ke toan");
        assertThat(CandidateLabels.key("KẾ TOÁN")).isEqualTo(CandidateLabels.key("ke toan"));
        assertThat(CandidateLabels.key(Normalizer.normalize("Điều phối", Normalizer.Form.NFD))).isEqualTo("dieu phoi");
        assertThat(CandidateLabels.key(" Spring Boot ")).isEqualTo("spring boot");
    }

    @Test
    void labelsAreDistinctByKeyAndKeepTheFirstSpelling() {
        var labels = CandidateLabels.labels(List.of(" Java ", "Spring   Boot", "JAVA", "kế toán", "Kế Toán"), "skills", 20, 60);
        assertThat(labels).extracting(CandidateLabels.Label::display).containsExactly("Java", "Spring Boot", "kế toán");
        assertThat(labels).extracting(CandidateLabels.Label::key).containsExactly("java", "spring boot", "ke toan");
        assertThat(CandidateLabels.labels(null, "skills", 20, 60)).isEmpty();
        // Duplicates do not count towards the limit.
        assertThat(CandidateLabels.labels(Collections.nCopies(30, "Java"), "skills", 20, 60)).hasSize(1);
    }

    @Test
    void noBreakAndZeroWidthSpacesAreInvisible() {
        String nbsp = String.valueOf((char) 0x00A0);
        String narrow = String.valueOf((char) 0x202F);
        String figure = String.valueOf((char) 0x2007);
        String zeroWidth = String.valueOf((char) 0x200B);
        assertThat(CandidateLabels.key("Java" + nbsp)).isEqualTo("java");
        assertThat(CandidateLabels.key("Kế" + narrow + figure + "toán")).isEqualTo("ke toan");
        var labels = CandidateLabels.labels(List.of("Java" + nbsp, zeroWidth + "java", "Spring" + nbsp + nbsp + "Boot"),
                "skills", 20, 60);
        assertThat(labels).extracting(CandidateLabels.Label::display).containsExactly("Java", "Spring Boot");
        for (String invisible : List.of(nbsp + narrow + figure, zeroWidth + nbsp)) {
            assertThatThrownBy(() -> CandidateLabels.labels(List.of(invisible), "skills", 20, 60))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void invalidLabelsAreRefusedNamingTheField() {
        List<String> many = IntStream.range(0, 21).mapToObj(index -> "Skill " + index).toList();
        for (List<String> invalid : List.of(many, Arrays.asList("Java", null), List.of("   "), List.of("<b>Java</b>"),
                List.of("x".repeat(61)), List.of("Ja" + (char) 0x200B + "va"), List.of("a" + (char) 7))) {
            assertThatThrownBy(() -> CandidateLabels.labels(invalid, "skills", 20, 60))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
                        assertThat(error.getFieldErrors()).containsOnlyKeys("skills");
                    });
        }
        assertThat(CandidateLabels.labels(List.of("x".repeat(60)), "skills", 20, 60)).hasSize(1);
    }
}
