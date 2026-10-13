package vn.ttcs.recruitment.headcount;

import java.math.BigInteger;
import java.time.LocalDate;

/**
 * Task 272: what requisitions already use of a department's plan for one year: the people they ask for and their
 * yearly salary cost in VND. The rules below decide which plan a requisition counts against and how much salary it
 * costs; HeadcountPlanRepository.usage applies the same rules in SQL when it sums the saved requisitions.
 */
public record HeadcountUsage(long headcount, long salaryCost) {
    // Proposed salaries are monthly amounts, while a plan's salary budget covers a whole year.
    public static final int MONTHS_PER_YEAR = 12;

    public static final HeadcountUsage NONE = new HeadcountUsage(0, 0);

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * The plan year of a requisition: the year of its needed-by date, because the people are planned for the year
     * they start. A draft without a date yet counts in the year it was created (business date, see BusinessCalendar).
     */
    public static int planYear(LocalDate neededBy, LocalDate createdOn) {
        return (neededBy != null ? neededBy : createdOn).getYear();
    }

    /**
     * The yearly salary cost of a requisition: headcount × monthly salary × 12. The upper end of the proposal is used,
     * because it is the most the requisition may cost; the lower end when only that is filled in; 0 while the draft
     * has no proposed salary yet. The API limits (999 people, 1.000 tỷ đồng) keep the result far below Long.MAX_VALUE;
     * rows written outside the API stop at Long.MAX_VALUE instead of overflowing, like the SQL sum.
     */
    public static long salaryCost(long headcount, Long proposedSalaryMin, Long proposedSalaryMax) {
        Long monthly = proposedSalaryMax != null ? proposedSalaryMax : proposedSalaryMin;
        if (monthly == null) {
            return 0;
        }
        return BigInteger.valueOf(headcount).multiply(BigInteger.valueOf(monthly))
                .multiply(BigInteger.valueOf(MONTHS_PER_YEAR)).min(LONG_MAX).longValueExact();
    }
}
