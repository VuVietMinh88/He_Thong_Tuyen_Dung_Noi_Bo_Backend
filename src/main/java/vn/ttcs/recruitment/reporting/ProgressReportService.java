package vn.ttcs.recruitment.reporting;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.reporting.ProgressReportRepository.Disclosed;
import vn.ttcs.recruitment.reporting.ProgressReportRepository.Header;
import vn.ttcs.recruitment.reporting.RequisitionProgressView.Availability;
import vn.ttcs.recruitment.reporting.RequisitionProgressView.ItemList;
import vn.ttcs.recruitment.reporting.RequisitionProgressView.StageCount;
import vn.ttcs.recruitment.security.AccessScope;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tasks 562-563 (story S8-06): requisition progress for the people who own a requisition (the head of its department,
 * HR) or work on it (its recruiters), inside the report scope (ReportingAccess). Each list is gated by its own grant:
 * the upcoming interviews by an INTERVIEWS read grant, the viewer's own pending evaluation forms by an EVALUATIONS
 * write grant (only a panel member who may write a form has one to write), and the applications waiting for a hiring
 * decision by the right to decide (ReportViewer.decidesHiring, and for a HIRING_MANAGER the management of the
 * department). The detail names candidates, so every disclosed application is logged (CandidateAccess.recordGranted,
 * PROFILE) in the same transaction; the list shows counts only and discloses nobody.
 */
@Service
public class ProgressReportService {
    static final int LIST_LIMIT = 20;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final ReportingAccess access;
    private final ProgressReportRepository repository;
    private final CandidateAccess candidates;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public ProgressReportService(ReportingAccess access, ProgressReportRepository repository, CandidateAccess candidates,
                                 BusinessCalendar calendar, Clock clock) {
        this.access = access;
        this.repository = repository;
        this.candidates = candidates;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** Task 562: one requisition, with its lists. */
    public RequisitionProgressView progress(Jwt jwt, UUID requisitionId) {
        return access.auditedRead(jwt, viewer -> {
            access.requireFilters(viewer, new ReportFilters(requisitionId, null, null));
            return progress(viewer, requisitionId);
        });
    }

    /** Task 563: the viewer's open requisitions, most urgent first, with counts only. */
    public RequisitionProgressPageView list(Jwt jwt, UUID departmentId, UUID positionId, Integer page, Integer size) {
        ReportPaging paging = ReportPaging.of(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ReportFilters filters = new ReportFilters(null, departmentId, positionId);
        return access.read(jwt, viewer -> {
            access.requireFilters(viewer, filters);
            return list(viewer, filters, paging);
        });
    }

    private RequisitionProgressView progress(ReportViewer viewer, UUID requisitionId) {
        Instant now = now();
        LocalDate today = calendar.dateOf(now);
        ReportQuery query = ReportQuery.of(viewer, ReportFilters.NONE)
                .with("requisitionIds", List.of(requisitionId)).with("now", Timestamp.from(now));
        Header header = repository.header(query);
        Summary summary = summary(header, repository.stageCounts(query).getOrDefault(requisitionId, Map.of()), today);

        Map<UUID, Set<UUID>> disclosed = new LinkedHashMap<>();
        ItemList<RequisitionProgressView.UpcomingInterview> upcoming = interviewsGate(viewer).available()
                ? ItemList.of(count(repository.upcomingCounts(query), requisitionId), LIST_LIMIT,
                        items(repository.upcoming(query, LIST_LIMIT), disclosed))
                : ItemList.unavailable(interviewsGate(viewer).unavailableReason(), LIST_LIMIT);
        ItemList<RequisitionProgressView.PendingEvaluation> pending = evaluationsGate(viewer).available()
                ? ItemList.of(count(repository.pendingCounts(query), requisitionId), LIST_LIMIT,
                        items(repository.pending(query, LIST_LIMIT), disclosed))
                : ItemList.unavailable(evaluationsGate(viewer).unavailableReason(), LIST_LIMIT);
        ItemList<RequisitionProgressView.AwaitingDecision> awaiting = decisionGate(viewer).available()
                ? ItemList.of(count(repository.awaitingCounts(query, decisionScope(viewer)), requisitionId), LIST_LIMIT,
                        items(repository.awaiting(query, decisionScope(viewer), LIST_LIMIT), disclosed))
                : ItemList.unavailable(decisionGate(viewer).unavailableReason(), LIST_LIMIT);
        CandidateReader reader = new CandidateReader(viewer.id(), viewer.candidates());
        disclosed.forEach((candidate, applications) ->
                candidates.recordGranted(reader, CandidateAccessKind.PROFILE, candidate, List.copyOf(applications)));
        return new RequisitionProgressView(now, today, viewer.scopeName(), summary.requisition(), summary.stages(),
                summary.total(), summary.active(), upcoming, pending, awaiting);
    }

    private RequisitionProgressPageView list(ReportViewer viewer, ReportFilters filters, ReportPaging paging) {
        Instant now = now();
        LocalDate today = calendar.dateOf(now);
        ReportQuery query = ReportQuery.of(viewer, filters).with("now", Timestamp.from(now));
        long total = repository.openCount(query);
        List<Header> headers = repository.openPage(query, paging);
        List<RequisitionProgressPageView.Item> items = new ArrayList<>();
        Availability interviews = interviewsGate(viewer), evaluations = evaluationsGate(viewer),
                decisions = decisionGate(viewer);
        if (!headers.isEmpty()) {
            ReportQuery counts = ReportQuery.of(viewer, ReportFilters.NONE).with("now", Timestamp.from(now))
                    .with("requisitionIds", headers.stream().map(Header::requisitionId).toList());
            Map<UUID, Map<String, Long>> stages = repository.stageCounts(counts);
            Map<UUID, Long> upcoming = interviews.available() ? repository.upcomingCounts(counts) : null;
            Map<UUID, Long> pending = evaluations.available() ? repository.pendingCounts(counts) : null;
            Map<UUID, Long> awaiting = decisions.available()
                    ? repository.awaitingCounts(counts, decisionScope(viewer)) : null;
            for (Header header : headers) {
                UUID id = header.requisitionId();
                Summary summary = summary(header, stages.getOrDefault(id, Map.of()), today);
                items.add(new RequisitionProgressPageView.Item(summary.requisition(), summary.stages(), summary.total(),
                        summary.active(), upcoming == null ? null : count(upcoming, id),
                        pending == null ? null : count(pending, id), awaiting == null ? null : count(awaiting, id)));
            }
        }
        return new RequisitionProgressPageView(now, today, viewer.scopeName(),
                new RequisitionProgressPageView.Filters(filters.departmentId(), filters.positionId()),
                new RequisitionProgressPageView.Lists(interviews, evaluations, decisions), List.copyOf(items),
                paging.page(), paging.size(), total, paging.totalPages(total));
    }

    private record Summary(RequisitionProgressView.Requisition requisition, List<StageCount> stages, long total,
                           long active) { }

    // All eight stages in pipeline order, zero included; hired = HIRED + ONBOARDED (onboarding follows the hire).
    private static Summary summary(Header header, Map<String, Long> counted, LocalDate today) {
        List<StageCount> stages = new ArrayList<>();
        long total = 0;
        long active = 0;
        for (ApplicationStage stage : ApplicationStage.values()) {
            long count = counted.getOrDefault(stage.name(), 0L);
            stages.add(new StageCount(stage.name(), stage.displayName(), count));
            total += count;
            active += stage.active() ? count : 0;
        }
        long hired = counted.getOrDefault("HIRED", 0L) + counted.getOrDefault("ONBOARDED", 0L);
        var requisition = new RequisitionProgressView.Requisition(header.requisitionId(), header.positionId(),
                header.positionName(), header.departmentId(), header.departmentName(), header.status(),
                header.headcount(), hired, Math.max(0, header.headcount() - hired), header.neededBy(),
                header.neededBy() == null ? null : ChronoUnit.DAYS.between(today, header.neededBy()));
        return new Summary(requisition, List.copyOf(stages), total, active);
    }

    private static Availability interviewsGate(ReportViewer viewer) {
        return viewer.interviews() == AccessScope.NONE ? new Availability(false, "MISSING_INTERVIEWS_READ")
                : new Availability(true, null);
    }

    private static Availability evaluationsGate(ReportViewer viewer) {
        return viewer.evaluationsWrite() == AccessScope.NONE ? new Availability(false, "MISSING_EVALUATIONS_WRITE")
                : new Availability(true, null);
    }

    private static Availability decisionGate(ReportViewer viewer) {
        return viewer.decidesHiring() ? new Availability(true, null) : new Availability(false, "NOT_A_HIRING_DECIDER");
    }

    private static String decisionScope(ReportViewer viewer) {
        return viewer.seniorDecider() ? "TRUE" : "r.department_id IN (" + DepartmentRepository.MANAGED_DEPARTMENT_IDS + ")";
    }

    private static long count(Map<UUID, Long> counts, UUID requisitionId) {
        return counts.getOrDefault(requisitionId, 0L);
    }

    private static <T> List<T> items(List<Disclosed<T>> rows, Map<UUID, Set<UUID>> disclosed) {
        rows.forEach(row -> disclosed.computeIfAbsent(row.candidateId(), ignored -> new LinkedHashSet<>())
                .add(row.applicationId()));
        return rows.stream().map(Disclosed::item).toList();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
