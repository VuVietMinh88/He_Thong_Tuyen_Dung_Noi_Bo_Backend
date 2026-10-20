package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 558 (tests only): the PostgreSQL plans (EXPLAIN, FORMAT JSON) of the dashboard's real statements, built by
 * DashboardReportRepository and DashboardReportService with their real parameters, for an unrestricted viewer (HR).
 * Lives in the reporting package because those builders are package-private.
 */
public final class DashboardQueryPlans {
    private DashboardQueryPlans() {
    }

    /**
     * Statement label to its JSON plan: the week card, a four-week chart, the overdue count and page, and one month of
     * closures.
     */
    public static Map<String, String> plans(DashboardReportService service, NamedParameterJdbcTemplate jdbc,
                                            Instant now, LocalDate today) {
        ReportViewer hr = ReportViewer.of(UUID.randomUUID(), Set.of("REPORTS_READ_ALL", "REQUISITIONS_READ_ALL",
                "CANDIDATES_READ_ALL", "INTERVIEWS_READ_ALL", "OFFERS_READ_ALL"));
        Map<String, String> plans = new LinkedHashMap<>();
        ReportQuery cards = service.summaryQuery(hr, ReportFilters.NONE, now);
        plans.put("week card", explain(jdbc, DashboardReportRepository.applicationsReceivedSql(cards), cards));
        ReportQuery count = service.summaryQuery(hr, ReportFilters.NONE, now);
        plans.put("overdue count", explain(jdbc, DashboardReportRepository.overdueCountSql(count), count));
        ReportQuery overdue = service.summaryQuery(hr, ReportFilters.NONE, now);
        plans.put("overdue page", explain(jdbc,
                DashboardReportRepository.overduePageSql(overdue, ReportPaging.of(0, 10, 10, 50)), overdue));
        ReportQuery weeks = service.bucketQuery(hr, ReportFilters.NONE,
                ReportBuckets.weeks(today.minusWeeks(3), today));
        plans.put("weekly chart", explain(jdbc, DashboardReportRepository.applicationsPerBucketSql(weeks), weeks));
        ReportQuery month = service.bucketQuery(hr, ReportFilters.NONE, ReportBuckets.months(today, today));
        plans.put("monthly closures", explain(jdbc, DashboardReportRepository.closedPerBucketSql(month), month));
        return plans;
    }

    private static String explain(NamedParameterJdbcTemplate jdbc, String sql, ReportQuery query) {
        List<String> plan = jdbc.queryForList("EXPLAIN (FORMAT JSON) " + sql, query.parameters(), String.class);
        return String.join("\n", plan);
    }
}
