package vn.ttcs.recruitment.reporting;

import java.time.LocalDate;
import java.util.List;

/**
 * Task 556 (story S8-05): the dashboard charts. Each series covers whole weeks or months (from = first day of the
 * first bucket, to = last day of the last bucket) and has one point per bucket, zero included.
 */
public record DashboardChartsView(String scope, WeeklySeries weeklyApplications,
                                  MonthlySeries monthlyClosedRequisitions) {

    public record WeeklySeries(LocalDate from, LocalDate to, List<WeekPoint> points) { }

    public record WeekPoint(LocalDate weekStart, LocalDate weekEnd, long applications) { }

    public record MonthlySeries(LocalDate from, LocalDate to, List<MonthPoint> points) { }

    /** month is yyyy-MM; closed = filled + cancelled. */
    public record MonthPoint(String month, LocalDate monthStart, LocalDate monthEnd, long filled, long cancelled,
                             long closed) { }
}
