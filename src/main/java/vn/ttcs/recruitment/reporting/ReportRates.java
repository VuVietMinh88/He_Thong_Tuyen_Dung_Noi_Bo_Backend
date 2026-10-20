package vn.ttcs.recruitment.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rates and averages of the reports: exact decimals, rounded half-up, null when there is nothing to divide by. */
final class ReportRates {
    private ReportRates() {
    }

    /** numerator / denominator with 4 decimals (0.4286 = 42.86 %), null when the denominator is 0. */
    static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    /** total / count with 1 decimal, null when count is 0. */
    static BigDecimal average(long total, long count) {
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    /** Whole VND per unit, rounded half-up; null when count is 0. */
    static Long perUnit(long total, long count) {
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
