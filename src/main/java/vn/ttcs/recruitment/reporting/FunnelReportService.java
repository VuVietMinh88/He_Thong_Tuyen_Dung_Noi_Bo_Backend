package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.offer.OfferDeclineReason;
import vn.ttcs.recruitment.reporting.FunnelCalculator.Group;
import vn.ttcs.recruitment.reporting.FunnelCalculator.StageNumbers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * Tasks 567-569 (story S8-07): the funnel of the applications received in a period, inside the viewer's scope. The cohort
 * is chosen by received_at (business dates, half-open timestamps); each application then counts with the furthest
 * stage its history shows now (ApplicationReach), so the funnel of a past period keeps filling in as its candidates
 * move on. One aggregate statement; no application is loaded one by one.
 */
@Service
public class FunnelReportService {
    static final int DEFAULT_DAYS = 90;
    static final int TOP_REASONS = 5;
    static final String UNKNOWN_REASON = "Không rõ lý do";
    static final String DECLINE_PREFIX = "Ứng viên từ chối offer: ";
    static final String DECLINE_WITHOUT_REASON = "Ứng viên từ chối offer (không nêu lý do)";

    // The applications of the cohort that are REJECTED now, each with its latest REJECTED event only (an application can
    // be rejected, reopened by a renegotiated offer and declined again): declined when that event is the candidate's
    // answer to an offer, with the offer's decline reason; otherwise the company's rejection reason.
    private static final String ENDING = """
            SELECT reach.id, reach.max_rank, coalesce(last.actor_type = 'CANDIDATE', FALSE) AS declined,
                   last.rejection_reason_name, o.decline_reason
            FROM reach
            LEFT JOIN LATERAL (
                SELECT e.actor_type, e.rejection_reason_name, e.offer_id FROM application_stage_events e
                WHERE e.application_id = reach.id AND e.stage_after = 'REJECTED'
                ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1) last ON TRUE
            LEFT JOIN offers o ON o.id = last.offer_id
            WHERE reach.stage = 'REJECTED'
            """;

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

    /**
     * Task 568: the funnel narrowed to one requisition, department or position (each optional, combined with AND),
     * after the filters were checked: 404 for an unknown target, 403 for one outside the viewer's scope
     * (ReportingAccess.requireFilters).
     */
    public FunnelReportView funnel(Jwt jwt, LocalDate from, LocalDate to, UUID requisitionId, UUID departmentId,
                                   UUID positionId) {
        ReportPeriod period = ReportPeriod.resolve(from, to, calendar.today(), DEFAULT_DAYS);
        ReportFilters filters = new ReportFilters(requisitionId, departmentId, positionId);
        return access.read(jwt, viewer -> {
            access.requireFilters(viewer, filters);
            return funnel(viewer, filters, period);
        });
    }

    FunnelReportView funnel(ReportViewer viewer, ReportFilters filters, ReportPeriod period) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReportQuery query = ReportQuery.of(viewer, filters)
                .with("fromTs", Timestamp.from(calendar.startOf(period.from())))
                .with("toTs", Timestamp.from(calendar.startOf(period.to().plusDays(1))));
        String reach = """
                WITH cohort AS (
                    SELECT a.id, a.stage
                    FROM job_applications a
                    JOIN recruitment_requisitions r ON r.id = a.requisition_id
                    WHERE a.received_at >= :fromTs AND a.received_at < :toTs AND\s""" + query.rows() + """
                ), reach AS (
                """ + ApplicationReach.COHORT_REACH + """
                ), ending AS (
                """ + ENDING + ") ";
        List<Group> groups = jdbc.query(reach + """
                SELECT reach.max_rank, reach.stage, reach.skipped, coalesce(ending.declined, FALSE) AS declined,
                       count(*) AS total
                FROM reach LEFT JOIN ending ON ending.id = reach.id
                GROUP BY 1, 2, 3, 4
                """, query.parameters(), (row, number) -> new Group(row.getInt("max_rank"), row.getString("stage"),
                row.getBoolean("skipped"), row.getBoolean("declined"), row.getLong("total")));
        var result = FunnelCalculator.compute(groups);
        List<FunnelReportView.Stage> stages = new ArrayList<>();
        for (int index = 0; index < result.stages().size(); index++) {
            StageNumbers numbers = result.stages().get(index);
            String next = index + 1 < result.stages().size() ? result.stages().get(index + 1).stage().name() : null;
            stages.add(new FunnelReportView.Stage(numbers.stage().name(), numbers.stage().displayName(), next,
                    numbers.reached(), numbers.advanced(), next == null ? null : ReportRates.rate(numbers.advanced(), numbers.reached()),
                    numbers.rejected(), numbers.declined(), numbers.withdrawn(), numbers.inProgress(),
                    numbers.dropped(),
                    ReportRates.rate(numbers.dropped(), numbers.reached())));
        }
        long totalDropped = result.stages().stream().mapToLong(StageNumbers::dropped).sum();
        FunnelReportView.LargestDrop largest = FunnelCalculator.largestDrop(result).map(drop -> {
            int rank = FunnelCalculator.PIPELINE.indexOf(drop.stage()) + 1;
            String next = rank < FunnelCalculator.PIPELINE.size() ? FunnelCalculator.PIPELINE.get(rank).name() : null;
            query.with("dropRank", rank);
            List<FunnelReportView.ReasonCount> rows = jdbc.query(reach + """
                    SELECT ending.declined, ending.rejection_reason_name, ending.decline_reason, count(*) AS total
                    FROM ending WHERE ending.max_rank = :dropRank
                    GROUP BY 1, 2, 3
                    """, query.parameters(), (row, number) -> reason(row.getBoolean("declined"),
                    row.getString("rejection_reason_name"), row.getString("decline_reason"), row.getLong("total")));
            List<FunnelReportView.ReasonCount> reasons = topReasons(rows);
            return new FunnelReportView.LargestDrop(drop.stage().name(), drop.stage().displayName(), next,
                    drop.reached(), drop.advanced(), next == null ? null : ReportRates.rate(drop.advanced(), drop.reached()),
                    drop.dropped(), drop.rejected(), drop.declined(), drop.withdrawn(),
                    ReportRates.rate(drop.dropped(), drop.reached()),
                    ReportRates.rate(drop.dropped(), totalDropped), List.copyOf(reasons), FunnelCalculator.LARGEST_DROP_RULE);
        }).orElse(null);
        return new FunnelReportView(now, period.from(), period.to(), viewer.scopeName(), filters, result.cohortSize(),
                result.hired(), ReportRates.rate(result.hired(), result.cohortSize()), result.interviewSkipped(),
                List.copyOf(stages), totalDropped, largest);
    }

    private static FunnelReportView.ReasonCount reason(boolean declined, String rejectionReason, String declineReason,
                                                       long count) {
        if (!declined) {
            return new FunnelReportView.ReasonCount("STAFF_REJECTION",
                    rejectionReason == null ? UNKNOWN_REASON : rejectionReason, count);
        }
        String label = declineReason == null ? DECLINE_WITHOUT_REASON
                : DECLINE_PREFIX + OfferDeclineReason.valueOf(declineReason).label();
        return new FunnelReportView.ReasonCount("CANDIDATE_DECLINE", label, count);
    }

    // The same kind and wording counted once, the most frequent first, then by wording.
    private static List<FunnelReportView.ReasonCount> topReasons(List<FunnelReportView.ReasonCount> rows) {
        Map<List<String>, Long> merged = new LinkedHashMap<>();
        rows.forEach(row -> merged.merge(List.of(row.kind(), row.reason()), row.count(), Long::sum));
        return merged.entrySet().stream()
                .map(entry -> new FunnelReportView.ReasonCount(entry.getKey().get(0), entry.getKey().get(1),
                        entry.getValue()))
                .sorted(Comparator.comparingLong(FunnelReportView.ReasonCount::count).reversed()
                        .thenComparing(FunnelReportView.ReasonCount::reason))
                .limit(TOP_REASONS).toList();
    }
}
