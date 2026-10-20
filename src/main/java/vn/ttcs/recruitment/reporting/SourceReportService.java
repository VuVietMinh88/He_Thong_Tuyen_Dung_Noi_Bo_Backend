package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tasks 580-581 (story S8-09): candidate sources and their pipeline outcomes, for the applications received in a period
 * inside the viewer's scope. The cohort and the furthest stage are exactly the funnel's (ApplicationReach), so the
 * source report and the funnel always add up to the same totals.
 * <p>
 * The effective source of an application: a staff referral (an application_referrals row, V36) is the source
 * "Giới thiệu nội bộ" (key INTERNAL_REFERRAL), whatever the catalog says, as the unified profile labels it (a referred
 * application is stored without a catalog source); otherwise the catalog source it was received with
 * (job_applications.source_id; a source made inactive later keeps its history); otherwise the unknown bucket.
 */
@Service
public class SourceReportService {
    static final int DEFAULT_DAYS = 90;
    static final String UNKNOWN_SOURCE = "Không rõ nguồn";
    static final String REFERRAL_SOURCE = "Giới thiệu nội bộ";
    static final String CATALOG = "CATALOG";
    static final String INTERNAL_REFERRAL = "INTERNAL_REFERRAL";
    static final String UNKNOWN = "UNKNOWN";
    static final int DEFAULT_MIN_APPLICATIONS = 5;
    static final int MAX_MIN_APPLICATIONS = 1_000;

    private final ReportingAccess access;
    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public SourceReportService(ReportingAccess access, NamedParameterJdbcTemplate jdbc, BusinessCalendar calendar,
                               Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * Task 581: the report with rates and the quality ranking (SourceRanking), narrowed by requisition, department and
     * position (404 for an unknown target, 403 outside the viewer's scope). minApplications (1..1000, default 5) is the
     * smallest sample a source needs to be ranked.
     */
    public SourceReportView report(Jwt jwt, LocalDate from, LocalDate to, UUID requisitionId, UUID departmentId,
                                   UUID positionId, Integer minApplications) {
        ReportPeriod period = ReportPeriod.resolve(from, to, calendar.today(), DEFAULT_DAYS);
        int minimum = minApplications == null ? DEFAULT_MIN_APPLICATIONS : minApplications;
        if (minimum < 1 || minimum > MAX_MIN_APPLICATIONS) {
            throw ReportPaging.invalid(Map.of("minApplications", "Cỡ mẫu tối thiểu phải từ 1 đến " + MAX_MIN_APPLICATIONS + "."));
        }
        ReportFilters filters = new ReportFilters(requisitionId, departmentId, positionId);
        return access.read(jwt, viewer -> {
            access.requireFilters(viewer, filters);
            return report(viewer, filters, period, minimum);
        });
    }

    private record Row(UUID sourceId, String key, String kind, String code, String name, Boolean active,
                       long applications, long passed, long hired, long rejected, long withdrawn, long inProgress) { }

    SourceReportView report(ReportViewer viewer, ReportFilters filters, ReportPeriod period, int minApplications) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReportQuery query = ReportQuery.of(viewer, filters)
                .with("fromTs", Timestamp.from(calendar.startOf(period.from())))
                .with("toTs", Timestamp.from(calendar.startOf(period.to().plusDays(1))));
        List<Row> rows = jdbc.query("""
                WITH cohort AS (
                    SELECT a.id, a.stage, a.source_id, ar.application_id IS NOT NULL AS referred
                    FROM job_applications a
                    JOIN recruitment_requisitions r ON r.id = a.requisition_id
                    LEFT JOIN application_referrals ar ON ar.application_id = a.id
                    WHERE a.received_at >= :fromTs AND a.received_at < :toTs AND\s""" + query.rows() + """
                ), reach AS (
                """ + ApplicationReach.COHORT_REACH + """
                )
                SELECT c.referred, s.id AS source_id, s.code, s.name, s.active,
                       count(*) AS applications,
                       count(*) FILTER (WHERE reach.max_rank >= 3) AS passed,
                       count(*) FILTER (WHERE reach.max_rank >= 5) AS hired,
                       count(*) FILTER (WHERE reach.stage = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE reach.stage = 'WITHDRAWN') AS withdrawn,
                       count(*) FILTER (WHERE reach.stage IN ('NEW', 'SCREENING', 'INTERVIEW', 'OFFER')) AS in_progress
                FROM reach
                JOIN cohort c ON c.id = reach.id
                LEFT JOIN recruitment_catalog_items s ON s.id = c.source_id AND NOT c.referred
                GROUP BY c.referred, s.id, s.code, s.name, s.active
                """, query.parameters(), (row, number) -> row(row.getBoolean("referred"),
                row.getObject("source_id", UUID.class), row.getString("code"), row.getString("name"),
                (Boolean) row.getObject("active"), row.getLong("applications"), row.getLong("passed"),
                row.getLong("hired"), row.getLong("rejected"), row.getLong("withdrawn"), row.getLong("in_progress")));
        Map<String, Row> byKey = new HashMap<>();
        rows.forEach(row -> byKey.put(row.key(), row));
        List<SourceReportView.Source> sources = SourceRanking.rank(rows.stream()
                        .map(row -> new SourceRanking.Counts(row.key(), row.name(), !UNKNOWN.equals(row.kind()),
                                row.applications(), row.passed(), row.hired())).toList(), minApplications).stream()
                .map(ranked -> {
                    Row row = byKey.get(ranked.counts().key());
                    return new SourceReportView.Source(row.sourceId(), row.key(), row.kind(), row.code(), row.name(),
                            row.active(), row.applications(), row.passed(), row.hired(), row.rejected(),
                            row.withdrawn(), row.inProgress(), ranked.screeningPassRate(), ranked.hireRate(),
                            ranked.meetsMinimumSample(), ranked.qualityRank());
                }).toList();
        long cohort = sources.stream().mapToLong(SourceReportView.Source::applications).sum();
        SourceReportView.Source best = sources.stream().filter(source -> Integer.valueOf(1).equals(source.qualityRank()))
                .findFirst().orElse(null);
        SourceReportView.Source most = sources.stream().filter(source -> !UNKNOWN.equals(source.kind()))
                .max(Comparator.comparingLong(SourceReportView.Source::applications)
                        .thenComparing(SourceReportView.Source::name, Comparator.reverseOrder()))
                .orElse(null);
        return new SourceReportView(now, period.from(), period.to(), viewer.scopeName(), filters, minApplications,
                SourceRanking.RULE, cohort, best, most, sources);
    }

    // The effective source of a group: a referral, a catalog source, or the unknown bucket.
    private static Row row(boolean referred, UUID sourceId, String code, String name, Boolean active, long applications,
                           long passed, long hired, long rejected, long withdrawn, long inProgress) {
        if (referred) {
            return new Row(null, INTERNAL_REFERRAL, INTERNAL_REFERRAL, INTERNAL_REFERRAL, REFERRAL_SOURCE, null,
                    applications, passed, hired, rejected, withdrawn, inProgress);
        }
        if (sourceId == null) {
            return new Row(null, UNKNOWN, UNKNOWN, null, UNKNOWN_SOURCE, null, applications, passed, hired, rejected,
                    withdrawn, inProgress);
        }
        return new Row(sourceId, sourceId.toString(), CATALOG, code, name, active, applications, passed, hired, rejected,
                withdrawn, inProgress);
    }
}
