package vn.ttcs.recruitment.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.reporting.TimeCostCalculator.Accumulator;
import vn.ttcs.recruitment.reporting.TimeCostRepository.CostRow;
import vn.ttcs.recruitment.reporting.TimeCostRepository.HireRow;
import vn.ttcs.recruitment.reporting.TimeCostRepository.RequisitionInfo;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tasks 574-575 (story S8-08): time to hire and cost per hire of the hires and costs of a period, inside the viewer's scope.
 * The rules are in TimeCostCalculator. Hire evidence is the application's first HIRED stage event ("Nhận việc", written
 * when the candidate accepts the offer or HR records the acceptance), never a planned start date: offers.start_date and
 * onboarding_checklists.start_date are plans, not facts.
 */
@Service
public class TimeCostReportService {
    static final int DEFAULT_DAYS = 90;
    static final int MAX_HIRES = 50_000;
    static final int MAX_REQUISITIONS = 5_000;

    private final ReportingAccess access;
    private final TimeCostRepository repository;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public TimeCostReportService(ReportingAccess access, TimeCostRepository repository, BusinessCalendar calendar,
                                 Clock clock) {
        this.access = access;
        this.repository = repository;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * Task 575: the report narrowed by requisition, department and position (each optional, combined with AND; 404
     * for an unknown target, 403 outside the viewer's scope), with the comparison by department and by position.
     */
    public TimeCostReportView report(Jwt jwt, LocalDate from, LocalDate to, UUID requisitionId, UUID departmentId,
                                     UUID positionId) {
        ReportPeriod period = ReportPeriod.resolve(from, to, calendar.today(), DEFAULT_DAYS);
        ReportFilters filters = new ReportFilters(requisitionId, departmentId, positionId);
        return access.read(jwt, viewer -> {
            access.requireFilters(viewer, filters);
            return report(viewer, filters, period);
        });
    }

    TimeCostReportView report(ReportViewer viewer, ReportFilters filters, ReportPeriod period) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReportQuery query = ReportQuery.of(viewer, filters)
                .with("fromTs", Timestamp.from(calendar.startOf(period.from())))
                .with("toTs", Timestamp.from(calendar.startOf(period.to().plusDays(1))))
                .with("fromDate", Date.valueOf(period.from())).with("toDate", Date.valueOf(period.to()));
        List<HireRow> hires = repository.hires(query, MAX_HIRES + 1);
        if (hires.size() > MAX_HIRES) {
            throw tooLarge();
        }
        List<CostRow> costs = repository.costs(query);
        Set<UUID> requisitionIds = new LinkedHashSet<>();
        hires.forEach(hire -> requisitionIds.add(hire.requisitionId()));
        costs.forEach(cost -> requisitionIds.add(cost.requisitionId()));
        if (requisitionIds.size() > MAX_REQUISITIONS) {
            throw tooLarge();
        }
        Map<UUID, Instant> approvals = repository.finalApprovals(requisitionIds);
        Map<UUID, RequisitionInfo> infos = new HashMap<>();
        repository.requisitions(requisitionIds).forEach(info -> infos.put(info.requisitionId(), info));

        // Every hire and cost goes to the overall total, its requisition, its department and its position, so each
        // group is computed from the individual hires (weighted), never from other groups' averages.
        Accumulator overall = new Accumulator();
        Map<UUID, Accumulator> byRequisition = new HashMap<>();
        Map<UUID, Accumulator> byDepartment = new HashMap<>();
        Map<UUID, Accumulator> byPosition = new HashMap<>();
        for (HireRow row : hires) {
            Instant approvedAt = approvals.get(row.requisitionId());
            var hire = new TimeCostCalculator.Hire(row.requisitionId(), calendar.dateOf(row.hiredAt()),
                    approvedAt == null ? null : calendar.dateOf(approvedAt), row.acceptedOffer(), row.onboarded());
            overall.add(hire);
            groups(infos.get(row.requisitionId()), byRequisition, byDepartment, byPosition).forEach(group -> group.add(hire));
        }
        for (CostRow row : costs) {
            var cost = new TimeCostCalculator.Cost(row.requisitionId(), row.costType(), row.totalVnd(), row.entries());
            overall.add(cost);
            groups(infos.get(row.requisitionId()), byRequisition, byDepartment, byPosition).forEach(group -> group.add(cost));
        }
        List<RequisitionInfo> sorted = infos.values().stream()
                .sorted(Comparator.comparing(RequisitionInfo::departmentName).thenComparing(RequisitionInfo::positionName)
                        .thenComparing(RequisitionInfo::requisitionId))
                .toList();
        List<TimeCostReportView.RequisitionRow> rows = sorted.stream()
                .map(info -> new TimeCostReportView.RequisitionRow(info.requisitionId(), info.departmentId(),
                        info.departmentName(), info.positionId(), info.positionName(), info.status(),
                        approvals.containsKey(info.requisitionId()) ? calendar.dateOf(approvals.get(info.requisitionId()))
                                : null, byRequisition.get(info.requisitionId()).metrics()))
                .toList();
        return new TimeCostReportView(now, period.from(), period.to(), viewer.scopeName(), filters, overall.metrics(),
                comparison(sorted, byDepartment, RequisitionInfo::departmentId, RequisitionInfo::departmentName),
                comparison(sorted, byPosition, RequisitionInfo::positionId, RequisitionInfo::positionName), rows);
    }

    private static List<Accumulator> groups(RequisitionInfo info, Map<UUID, Accumulator> byRequisition,
                                            Map<UUID, Accumulator> byDepartment, Map<UUID, Accumulator> byPosition) {
        return List.of(byRequisition.computeIfAbsent(info.requisitionId(), ignored -> new Accumulator()),
                byDepartment.computeIfAbsent(info.departmentId(), ignored -> new Accumulator()),
                byPosition.computeIfAbsent(info.positionId(), ignored -> new Accumulator()));
    }

    // One row per department (or position) with activity in the period, by name then id; requisitions counts the
    // requisitions of the group that had a hire or a cost.
    private static List<TimeCostReportView.Group> comparison(List<RequisitionInfo> infos, Map<UUID, Accumulator> totals,
                                                             Function<RequisitionInfo, UUID> id,
                                                             Function<RequisitionInfo, String> name) {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Long> requisitions = new HashMap<>();
        for (RequisitionInfo info : infos) {
            names.put(id.apply(info), name.apply(info));
            requisitions.merge(id.apply(info), 1L, Long::sum);
        }
        return totals.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<UUID, Accumulator> entry) -> names.get(entry.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new TimeCostReportView.Group(entry.getKey(), names.get(entry.getKey()),
                        requisitions.get(entry.getKey()), entry.getValue().metrics()))
                .toList();
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.BAD_REQUEST, "REPORT_TOO_LARGE",
                "Báo cáo quá lớn. Hãy thu hẹp bộ lọc hoặc khoảng thời gian.");
    }
}
