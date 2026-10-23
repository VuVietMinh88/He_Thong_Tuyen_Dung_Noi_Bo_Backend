package vn.ttcs.recruitment.candidatesearch;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.common.ApiException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateSearchCriteriaTest {
    private static CandidateSearchCriteria criteria(List<ApplicationStage> stages, List<UUID> sources, List<UUID> tags,
                                                    LocalDate from, LocalDate to, Integer min, Integer max,
                                                    String keyword) {
        return new CandidateSearchCriteria(null, null, stages, sources, tags, null, from, to, min, max, keyword);
    }

    @Test
    void emptyCriteriaAreValidAndLeaveWithdrawnApplicationsOut() {
        var empty = criteria(null, null, null, null, null, null, null, "   ");
        assertThat(empty.problems()).isEmpty();
        assertThat(empty.keyword()).isNull();
        assertThat(empty.tagMatch()).isEqualTo(TagMatch.ANY);
        assertThat(empty.effectiveStages()).doesNotContain("WITHDRAWN").contains("NEW", "REJECTED", "ONBOARDED")
                .hasSize(ApplicationStage.values().length - 1);
        var withdrawn = criteria(List.of(ApplicationStage.WITHDRAWN, ApplicationStage.WITHDRAWN), null, null, null,
                null, null, null, null);
        assertThat(withdrawn.effectiveStages()).containsExactly("WITHDRAWN");
    }

    @Test
    void everyInvalidFieldIsReportedAtOnce() {
        List<UUID> many = IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID()).toList();
        var invalid = criteria(Collections.singletonList(null), many, many, LocalDate.of(1999, 12, 31),
                LocalDate.of(2101, 1, 1), 0, 6, "a");
        assertThat(invalid.problems()).containsOnlyKeys("stages", "sourceIds", "tagIds", "submittedFrom",
                "submittedTo", "minRating", "maxRating", "keyword");
        assertThatThrownBy(invalid::requireValid).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.getFieldErrors()).hasSize(8);
        });
    }

    @Test
    void rangesMustBeOrderedAndKeywordsVisible() {
        var reversed = criteria(null, null, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), 4, 3, null);
        assertThat(reversed.problems()).containsOnlyKeys("submittedTo", "maxRating");
        assertThat(criteria(null, null, null, null, null, null, null, "ab" + (char) 0x200B + "cd").problems()).containsOnlyKeys("keyword");
        assertThat(criteria(null, null, null, null, null, null, null, "x".repeat(101)).problems()).containsOnlyKeys("keyword");
        assertThat(criteria(null, null, null, null, null, null, null, "x".repeat(100)).problems()).isEmpty();
        UUID same = UUID.randomUUID();
        assertThat(criteria(null, Collections.nCopies(40, same), null, null, null, null, null, null).problems()).isEmpty();
    }

    @Test
    void toStringNeverShowsTheKeyword() {
        assertThat(criteria(null, null, null, null, null, null, null, "0912345678").toString())
                .doesNotContain("0912345678").contains("redacted");
    }
}
