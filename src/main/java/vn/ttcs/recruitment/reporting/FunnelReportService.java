package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.reporting.FunnelCalculator.Group;
import vn.ttcs.recruitment.reporting.FunnelCalculator.StageNumbers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 567 (story S8-07): the funnel of the applications received in a period, inside the viewer's scope. The cohort
 * is chosen by received_at (business dates, half-open timestamps); each application then counts with the furthest
 * stage its history shows now (ApplicationReach), so the funnel of a past period keeps filling in as its candidates
 * move on. One aggregate statement; no application is loaded one by one.
 */
@Service
public class FunnelReportService {
    static final int DEFAULT_DAYS = 90;

    private final ReportingAccess access;
    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public FunnelReportService(ReportingAccess access, NamedParameterJdbcTemplate jdbc, BusinessCalendar calendar,
                               Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.clock = clock;
    }

    public FunnelReportView funnel(Jwt jwt, LocalDate from, LocalDate to) {
        ReportPeriod period = ReportPeriod.resolve(from, to, calendar.today(), DEFAULT_DAYS);
        return access.read(jwt, viewer -> funnel(viewer, ReportFilters.NONE, period));
    }

    FunnelReportView funnel(ReportViewer viewer, ReportFilters filters, ReportPeriod period) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReportQuery query = ReportQuery.of(viewer, filters)
                .with("fromTs", Timestamp.from(calendar.startOf(period.from())))
                .with("toTs", Timestamp.from(calendar.startOf(period.to().plusDays(1))));
        List<Group> groups = jdbc.query("""
                WITH cohort AS (
                    SELECT a.id, a.stage
                    FROM job_applications a
                    JOIN recruitment_requisitions r ON r.id = a.requisition_id
                    WHERE a.received_at >= :fromTs AND a.received_at < :toTs AND\s""" + query.rows() + """
                ), reach AS (
                """ + ApplicationReach.COHORT_REACH + """
                )
                SELECT max_rank, stage, skipped, count(*) AS total FROM reach GROUP BY 1, 2, 3
                """, query.parameters(), (row, number) -> new Group(row.getInt("max_rank"), row.getString("stage"),
                row.getBoolean("skipped"), row.getLong("total")));
        var result = FunnelCalculator.compute(groups);
        List<FunnelReportView.Stage> stages = new ArrayList<>();
        for (int index = 0; index < result.stages().size(); index++) {
            StageNumbers numbers = result.stages().get(index);
            String next = index + 1 < result.stages().size() ? result.stages().get(index + 1).stage().name() : null;
            stages.add(new FunnelReportView.Stage(numbers.stage().name(), numbers.stage().displayName(), next,
                    numbers.reached(), numbers.advanced(), next == null ? null : ReportRates.rate(numbers.advanced(), numbers.reached()),
                    numbers.rejected(), numbers.withdrawn(), numbers.inProgress(), numbers.dropped(),
                    ReportRates.rate(numbers.dropped(), numbers.reached())));
        }
        return new FunnelReportView(now, period.from(), period.to(), viewer.scopeName(), result.cohortSize(),
                result.hired(), ReportRates.rate(result.hired(), result.cohortSize()), result.interviewSkipped(),
                List.copyOf(stages));
    }
}
