package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.reporting.DashboardChartsView.MonthPoint;
import vn.ttcs.recruitment.reporting.DashboardChartsView.MonthlySeries;
import vn.ttcs.recruitment.reporting.DashboardChartsView.WeekPoint;
import vn.ttcs.recruitment.reporting.DashboardChartsView.WeeklySeries;
import vn.ttcs.recruitment.reporting.DashboardReportView.Card;
import vn.ttcs.recruitment.reporting.DashboardReportView.Cards;
import vn.ttcs.recruitment.reporting.DashboardReportView.OverduePage;
import vn.ttcs.recruitment.reporting.DashboardReportView.OverdueRequisition;
import vn.ttcs.recruitment.reporting.DashboardReportView.WeekRange;
import vn.ttcs.recruitment.security.AccessScope;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Task 555 (story S8-05): the dashboard's cards (open requisitions, applications received this business week,
 * upcoming interviews, offers awaiting an answer) and the open requisitions past their needed-by date, most overdue
 * first. Every number is computed in SQL inside the viewer's scope (ReportingAccess). A card whose module the viewer may
 * not read (INTERVIEWS, OFFERS) is returned unavailable with a reason, never as a misleading zero.
 */
@Service
public class DashboardReportService {
    static final int INTERVIEW_HORIZON_DAYS = 7;
    static final int DEFAULT_OVERDUE_PAGE_SIZE = 10;
    static final int MAX_OVERDUE_PAGE_SIZE = 50;
    static final int DEFAULT_WEEKS = 12;
    static final int DEFAULT_MONTHS = 12;

    private final ReportingAccess access;
    private final DashboardReportRepository repository;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public DashboardReportService(ReportingAccess access, DashboardReportRepository repository,
                                  BusinessCalendar calendar, Clock clock) {
        this.access = access;
        this.repository = repository;
        this.calendar = calendar;
        this.clock = clock;
    }

    public DashboardReportView dashboard(Jwt jwt, Integer page, Integer size) {
        ReportPaging paging = ReportPaging.of(page, size, DEFAULT_OVERDUE_PAGE_SIZE, MAX_OVERDUE_PAGE_SIZE);
        return access.read(jwt, viewer -> summary(viewer, paging));
    }

    /**
     * Task 556: applications received per business week and requisitions closed per month. With from and to, both
     * series cover the whole weeks and months of that range; without them, the last DEFAULT_WEEKS weeks and the last
     * DEFAULT_MONTHS months up to today.
     */
    public DashboardChartsView charts(Jwt jwt, LocalDate from, LocalDate to) {
        ReportPeriod period = ReportPeriod.explicit(from, to);
        return access.read(jwt, viewer -> charts(viewer, period));
    }

    DashboardChartsView charts(ReportViewer viewer, ReportPeriod period) {
        LocalDate today = calendar.today();
        List<ReportBuckets.Bucket> weeks = period == null
                ? ReportBuckets.weeks(today.minusWeeks(DEFAULT_WEEKS - 1L), today)
                : ReportBuckets.weeks(period.from(), period.to());
        List<ReportBuckets.Bucket> months = period == null
                ? ReportBuckets.months(today.minusMonths(DEFAULT_MONTHS - 1L), today)
                : ReportBuckets.months(period.from(), period.to());

        Map<Integer, Long> received = repository.applicationsPerBucket(bucketParameters(viewer, weeks));
        List<WeekPoint> weekPoints = new ArrayList<>();
        for (int index = 0; index < weeks.size(); index++) {
            var bucket = weeks.get(index);
            weekPoints.add(new WeekPoint(bucket.start(), bucket.end(), received.getOrDefault(index + 1, 0L)));
        }
        Map<Integer, DashboardReportRepository.ClosedCounts> closed =
                repository.closedPerBucket(bucketParameters(viewer, months));
        List<MonthPoint> monthPoints = new ArrayList<>();
        for (int index = 0; index < months.size(); index++) {
            var bucket = months.get(index);
            var counts = closed.getOrDefault(index + 1, new DashboardReportRepository.ClosedCounts(0, 0));
            monthPoints.add(new MonthPoint(YearMonth.from(bucket.start()).toString(), bucket.start(), bucket.end(),
                    counts.filled(), counts.cancelled(), counts.filled() + counts.cancelled()));
        }
        return new DashboardChartsView(viewer.scopeName(),
                new WeeklySeries(weeks.getFirst().start(), weeks.getLast().end(), weekPoints),
                new MonthlySeries(months.getFirst().start(), months.getLast().end(), monthPoints));
    }

    private MapSqlParameterSource bucketParameters(ReportViewer viewer, List<ReportBuckets.Bucket> buckets) {
        List<OffsetDateTime> bounds = buckets.stream()
                .map(bucket -> calendar.startOf(bucket.start()).atOffset(ZoneOffset.UTC)).toList();
        return ReportingAccess.parameters(viewer)
                .addValue("bounds", bounds)
                .addValue("seriesFrom", bounds.getFirst())
                .addValue("seriesTo", calendar.startOf(buckets.getLast().dayAfter()).atOffset(ZoneOffset.UTC));
    }

    DashboardReportView summary(ReportViewer viewer, ReportPaging paging) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = calendar.dateOf(now);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var parameters = ReportingAccess.parameters(viewer)
                .addValue("now", Timestamp.from(now))
                .addValue("horizon", Timestamp.from(now.plus(Duration.ofDays(INTERVIEW_HORIZON_DAYS))))
                .addValue("today", Date.valueOf(today))
                .addValue("receivedFrom", Timestamp.from(calendar.startOf(weekStart)))
                .addValue("receivedTo", Timestamp.from(calendar.startOf(weekStart.plusWeeks(1))));

        var requisitions = repository.requisitionCounts(parameters);
        Card upcoming = viewer.interviews() == AccessScope.NONE ? Card.unavailable("MISSING_INTERVIEWS_READ")
                : Card.of(repository.upcomingInterviews(parameters));
        Card offers = viewer.offers() == AccessScope.NONE ? Card.unavailable("MISSING_OFFERS_READ")
                : Card.of(repository.pendingOffers(parameters));
        Cards cards = new Cards(Card.of(requisitions.approved()), Card.of(requisitions.paused()),
                Card.of(repository.applicationsReceived(parameters)), upcoming, offers);

        long overdueTotal = repository.overdueCount(parameters);
        List<OverdueRequisition> overdue = repository.overduePage(parameters, paging).stream()
                .map(row -> new OverdueRequisition(row.requisitionId(), row.positionId(), row.positionName(),
                        row.departmentId(), row.departmentName(), row.status(), row.neededBy(),
                        ChronoUnit.DAYS.between(row.neededBy(), today), row.headcount(), row.hiredCount(),
                        Math.max(0, row.headcount() - row.hiredCount()), row.hiredCount() >= row.headcount()))
                .toList();
        return new DashboardReportView(now, today, calendar.zone().getId(), viewer.scopeName(), cards,
                new WeekRange(weekStart, weekStart.plusDays(6)), INTERVIEW_HORIZON_DAYS,
                new OverduePage(overdue, paging.page(), paging.size(), overdueTotal, paging.totalPages(overdueTotal)));
    }
}
