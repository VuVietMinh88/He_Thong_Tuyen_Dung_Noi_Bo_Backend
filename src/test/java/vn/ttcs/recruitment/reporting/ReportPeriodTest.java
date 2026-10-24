package vn.ttcs.recruitment.reporting;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.common.ApiException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 556: report date ranges and the whole weeks and months that cover them. */
class ReportPeriodTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Test
    void bothEndsOrNeitherAndAtMostOneYear() {
        assertThat(ReportPeriod.explicit(null, null)).isNull();
        assertThat(ReportPeriod.resolve(null, null, TODAY, 90))
                .isEqualTo(new ReportPeriod(LocalDate.of(2026, 7, 18), TODAY));
        assertThat(ReportPeriod.explicit(TODAY, TODAY)).isEqualTo(new ReportPeriod(TODAY, TODAY));
        assertThat(ReportPeriod.explicit(LocalDate.of(2025, 10, 15), TODAY)).isNotNull();   // 366 days
        invalid(LocalDate.of(2025, 10, 14), TODAY, "to");                                   // 367 days
        invalid(TODAY, null, "to");
        invalid(null, TODAY, "from");
        invalid(TODAY, TODAY.minusDays(1), "to");
        invalid(LocalDate.of(1999, 12, 31), LocalDate.of(2000, 1, 2), "from");
        invalid(LocalDate.of(2100, 12, 31), LocalDate.of(2101, 1, 1), "to");
    }

    @Test
    void weeksRunMondayToSundayAndCoverBothEnds() {
        var weeks = ReportBuckets.weeks(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 10, 15));
        assertThat(weeks).hasSize(7);
        assertThat(weeks.getFirst()).isEqualTo(new ReportBuckets.Bucket(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6)));
        assertThat(weeks.getLast()).isEqualTo(new ReportBuckets.Bucket(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
        assertThat(weeks.getLast().dayAfter()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(ReportBuckets.weeks(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12))).hasSize(1);
        // A Sunday alone is the last day of its week.
        assertThat(ReportBuckets.weeks(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 18)).getFirst().start())
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ReportBuckets.weeks(LocalDate.of(2025, 10, 15), TODAY)).hasSizeLessThanOrEqualTo(54);
    }

    @Test
    void monthsAreWholeCalendarMonthsIncludingLeapFebruary() {
        var months = ReportBuckets.months(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 1));
        assertThat(months).extracting(ReportBuckets.Bucket::start)
                .containsExactly(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 1));
        assertThat(months.get(1).end()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ReportBuckets.months(LocalDate.of(2025, 10, 15), TODAY)).hasSize(13);
    }

    private static void invalid(LocalDate from, LocalDate to, String field) {
        assertThatThrownBy(() -> ReportPeriod.explicit(from, to)).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(exception.getFieldErrors()).containsKey(field);
        });
    }
}
