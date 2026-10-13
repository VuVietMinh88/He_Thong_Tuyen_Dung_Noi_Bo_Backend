package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 297: the filters of GET /requisitions after binding, without a database.
class RequisitionListFilterTest {
    private static final LocalDate NOVEMBER_1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate NOVEMBER_30 = LocalDate.of(2026, 11, 30);

    @Test
    void emptyStatusElementsAreDroppedAndNoStatusMeansEveryStatus() {
        // "status=DRAFT," and "status=DRAFT&status=" bind to [DRAFT, null].
        var filter = RequisitionListFilter.of(Arrays.asList(RequisitionStatus.DRAFT, null, RequisitionStatus.APPROVED,
                RequisitionStatus.DRAFT), null, null, null, null);
        assertThat(filter.statuses()).containsExactly(RequisitionStatus.DRAFT, RequisitionStatus.APPROVED);
        assertThat(RequisitionListFilter.of(Arrays.asList((RequisitionStatus) null), null, null, null, null).statuses())
                .isEmpty();
        assertThat(RequisitionListFilter.of(null, null, null, null, null).statuses()).isEmpty();
        assertThat(RequisitionListFilter.of(List.of(), null, null, null, null).statuses()).isEmpty();
        // The other filters are kept as they were bound; the set cannot be changed afterwards.
        UUID department = UUID.randomUUID();
        UUID recruiter = UUID.randomUUID();
        var all = RequisitionListFilter.of(List.of(RequisitionStatus.REJECTED), department, recruiter, NOVEMBER_1,
                NOVEMBER_30);
        assertThat(all).isEqualTo(new RequisitionListFilter(all.statuses(), department, recruiter, NOVEMBER_1,
                NOVEMBER_30));
        assertThatThrownBy(() -> all.statuses().add(RequisitionStatus.DRAFT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNeededByRangeIsValidWithEitherBoundOrBothInOrder() {
        assertThat(range(null, null).neededByRangeValid()).isTrue();
        assertThat(range(NOVEMBER_1, null).neededByRangeValid()).isTrue();
        assertThat(range(null, NOVEMBER_30).neededByRangeValid()).isTrue();
        assertThat(range(NOVEMBER_1, NOVEMBER_30).neededByRangeValid()).isTrue();
        assertThat(range(NOVEMBER_1, NOVEMBER_1).neededByRangeValid()).isTrue();
        assertThat(range(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)).neededByRangeValid()).isTrue();
    }

    @Test
    void aStartAfterTheEndOrAYearOutsideOneToNineThousandNineHundredNinetyNineIsInvalid() {
        assertThat(range(NOVEMBER_30, NOVEMBER_1).neededByRangeValid()).isFalse();
        assertThat(range(NOVEMBER_1.plusDays(1), NOVEMBER_1).neededByRangeValid()).isFalse();
        for (LocalDate outside : List.of(LocalDate.of(0, 1, 1), LocalDate.of(10000, 1, 1), LocalDate.of(-1, 12, 31))) {
            assertThat(range(outside, null).neededByRangeValid()).as("start %s", outside).isFalse();
            assertThat(range(null, outside).neededByRangeValid()).as("end %s", outside).isFalse();
        }
        assertThat(range(LocalDate.of(0, 12, 31), NOVEMBER_1).neededByRangeValid()).isFalse();
        assertThat(range(NOVEMBER_1, LocalDate.of(10000, 1, 1)).neededByRangeValid()).isFalse();
    }

    private static RequisitionListFilter range(LocalDate from, LocalDate to) {
        return RequisitionListFilter.of(null, null, null, from, to);
    }
}
