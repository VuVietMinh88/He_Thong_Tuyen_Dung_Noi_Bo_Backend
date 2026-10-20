package vn.ttcs.recruitment.fit;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FitEvaluatorTest {
    private static final Instant AT = Instant.parse("2026-10-15T03:00:00Z");

    private static RequiredCriteria criteria(Integer years, EducationLevel education, String... skills) {
        return new RequiredCriteria(UUID.randomUUID(), years, education,
                CandidateLabels.labels(List.of(skills), "skills", 20, 60), 1, AT, UUID.randomUUID(), "HR");
    }

    private static Qualifications qualifications(String years, EducationLevel education, String... skills) {
        return new Qualifications(years == null ? null : new BigDecimal(years), education,
                CandidateLabels.labels(List.of(skills), "skills", 50, 60), true, 1, AT, UUID.randomUUID(), "Recruiter");
    }

    // Experience and education recorded, skills never recorded.
    private static Qualifications withoutSkills(String years, EducationLevel education) {
        return new Qualifications(years == null ? null : new BigDecimal(years), education, List.of(), false, 1, AT,
                UUID.randomUUID(), "Recruiter");
    }

    @Test
    void aPositionWithoutCriteriaGivesNoScore() {
        for (RequiredCriteria none : new RequiredCriteria[] {null, criteria(null, null)}) {
            FitResult result = FitEvaluator.evaluate(none, qualifications("5", EducationLevel.MASTER, "Java"));
            assertThat(result.status()).isEqualTo(FitStatus.NO_CRITERIA);
            assertThat(result.score()).isNull();
            assertThat(result.requiredMet()).isFalse();
            assertThat(result.criteria()).isEmpty();
        }
    }

    @Test
    void unrecordedDataIsUnknownEarnsNothingAndIsNeverMet() {
        FitResult result = FitEvaluator.evaluate(criteria(3, EducationLevel.BACHELOR, "Java", "SQL"), null);
        assertThat(result.status()).isEqualTo(FitStatus.UNKNOWN);
        assertThat(result.requiredMet()).isFalse();
        assertThat(result.score()).isZero();
        assertThat(result.criteria()).extracting(CriterionResult::status).containsOnly(CriterionStatus.UNKNOWN);
        assertThat(result.criteria()).extracting(CriterionResult::actual).containsOnlyNulls();
        // Recorded skills but no experience or education: those two stay unknown, skills are decided.
        FitResult partial = FitEvaluator.evaluate(criteria(3, EducationLevel.BACHELOR, "Java", "SQL"),
                qualifications(null, null, "java", "sql"));
        assertThat(partial.status()).isEqualTo(FitStatus.UNKNOWN);
        assertThat(partial.score()).isEqualTo(33);
    }

    @Test
    void skillsThatWereNeverRecordedAreUnknownNotMissing() {
        FitResult result = FitEvaluator.evaluate(criteria(3, null, "Java"), withoutSkills("5", null));
        assertThat(result.status()).isEqualTo(FitStatus.UNKNOWN);
        assertThat(result.requiredMet()).isFalse();
        assertThat(result.score()).isEqualTo(50);
        CriterionResult skills = result.criteria().get(1);
        assertThat(skills.status()).isEqualTo(CriterionStatus.UNKNOWN);
        assertThat(skills.actual()).isNull();
        // The same application with skills recorded as none: a known absence.
        assertThat(FitEvaluator.evaluate(criteria(3, null, "Java"), qualifications("5", null)).status())
                .isEqualTo(FitStatus.NOT_MET);
    }

    @Test
    void everyCriterionMetGivesTheFullScoreAndTheFlag() {
        FitResult result = FitEvaluator.evaluate(criteria(3, EducationLevel.BACHELOR, "Java", "Kế toán"),
                qualifications("3.0", EducationLevel.MASTER, "JAVA", "ke toan", "Docker"));
        assertThat(result.status()).isEqualTo(FitStatus.MET);
        assertThat(result.requiredMet()).isTrue();
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.criteria()).extracting(CriterionResult::maxPoints).containsExactly(34, 33, 33);
        assertThat(result.criteria().get(0).actual()).isEqualTo("3");
        assertThat(result.criteria().get(2).matchedSkills()).containsExactly("Java", "Kế toán");
        assertThat(result.criteria().get(2).missingSkills()).isEmpty();
    }

    @Test
    void aFailedCriterionMakesTheResultNotMetWithPartialSkillPoints() {
        FitResult result = FitEvaluator.evaluate(criteria(5, EducationLevel.MASTER, "Java", "SQL", "Docker", "Kafka"),
                qualifications("4.5", EducationLevel.BACHELOR, "java"));
        assertThat(result.status()).isEqualTo(FitStatus.NOT_MET);
        assertThat(result.requiredMet()).isFalse();
        // Experience 0/34, education 0/33, skills 1 of 4 of 33 = 8.25 rounded to 8.
        assertThat(result.score()).isEqualTo(8);
        assertThat(result.criteria().get(2).missingSkills()).containsExactly("SQL", "Docker", "Kafka");
        // Recorded but empty skills are a known absence.
        FitResult noSkills = FitEvaluator.evaluate(criteria(null, null, "Java"), qualifications("1", null));
        assertThat(noSkills.status()).isEqualTo(FitStatus.NOT_MET);
        assertThat(noSkills.score()).isZero();
        assertThat(noSkills.criteria().getFirst().maxPoints()).isEqualTo(100);
    }

    @Test
    void sharesAlwaysAddUpToOneHundred() {
        assertThat(FitEvaluator.evaluate(criteria(1, null), qualifications("1", null)).score()).isEqualTo(100);
        assertThat(FitEvaluator.evaluate(criteria(1, EducationLevel.COLLEGE), qualifications("1", EducationLevel.COLLEGE))
                .criteria()).extracting(CriterionResult::maxPoints).containsExactly(50, 50);
    }

    @Test
    void everyStatusHasAVietnameseNameAndResultsAreConsistent() {
        for (FitStatus status : FitStatus.values()) {
            String name = switch (status) {
                case MET -> "Đạt tiêu chí bắt buộc";
                case NOT_MET -> "Không đạt tiêu chí bắt buộc";
                case UNKNOWN -> "Chưa đủ dữ liệu để đánh giá";
                case NO_CRITERIA -> "Vị trí chưa có tiêu chí bắt buộc";
            };
            assertThat(status.displayName()).isEqualTo(name);
        }
        assertThatThrownBy(() -> new FitResult(FitStatus.MET, false, 100, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FitResult(FitStatus.UNKNOWN, false, 101, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FitResult(FitStatus.NO_CRITERIA, false, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
