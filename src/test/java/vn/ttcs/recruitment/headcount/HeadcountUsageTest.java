package vn.ttcs.recruitment.headcount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// Task 272: which plan year a requisition counts in and how much yearly salary it costs.
class HeadcountUsageTest {

    @Test
    void theNeededByDateDecidesThePlanYearAndTheCreationDateOnlyWhenThereIsNoDate() {
        LocalDate createdOn = LocalDate.of(2026, 12, 20);

        // Asked in December 2026 for people who start in January 2027: counted in the 2027 plan.
        assertThat(HeadcountUsage.planYear(LocalDate.of(2027, 1, 5), createdOn)).isEqualTo(2027);
        assertThat(HeadcountUsage.planYear(LocalDate.of(2026, 12, 31), createdOn)).isEqualTo(2026);
        assertThat(HeadcountUsage.planYear(null, createdOn)).isEqualTo(2026);
        assertThat(HeadcountUsage.planYear(null, LocalDate.of(2027, 1, 1))).isEqualTo(2027);
    }

    @ParameterizedTest(name = "{0} people, min {1}, max {2} -> {3}")
    @CsvSource(nullValues = "null", value = {
            // The upper end of the proposal: 2 × 25 000 000 × 12.
            "2, 15000000, 25000000, 600000000",
            // Only one end filled in: that end is used.
            "2, null, 25000000, 600000000",
            "3, 15000000, null, 540000000",
            // No proposed salary yet: nothing is counted.
            "4, null, null, 0",
            // A salary of 0 is a valid proposal and costs nothing.
            "1, 0, 0, 0",
            // The API limits: 999 people at 1.000 tỷ đồng a month still fit in a long.
            "999, null, 1000000000000, 11988000000000000"
    })
    void yearlySalaryCostIsHeadcountTimesTheMonthlyProposalTimesTwelve(long headcount, Long min, Long max,
                                                                        long expected) {
        assertThat(HeadcountUsage.salaryCost(headcount, min, max)).isEqualTo(expected);
    }

    @Test
    void costOfRowsWrittenOutsideTheApiStopsAtTheLargestLongInsteadOfOverflowing() {
        assertThat(HeadcountUsage.salaryCost(Integer.MAX_VALUE, null, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(HeadcountUsage.salaryCost(1, null, Long.MAX_VALUE / 12)).isEqualTo(Long.MAX_VALUE / 12 * 12);
    }
}
