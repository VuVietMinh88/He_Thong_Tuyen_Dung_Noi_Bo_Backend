package vn.ttcs.recruitment.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Task 563 (story S8-06): the open requisitions a department head owns (or a recruiter works on, or HR oversees), most
 * urgent first, each with its current stage counts and the size of the lists of the detail (task 562). A count the
 * viewer may not see is null, and lists says why once for the whole page. No candidate is named here.
 */
public record RequisitionProgressPageView(Instant generatedAt, LocalDate businessDate, String scope, Filters filters,
                                          Lists lists, List<Item> items, int page, int size, long totalElements,
                                          int totalPages) {

    public record Filters(UUID departmentId, UUID positionId) { }

    /** Whether each count is available to the viewer; the reason is null when it is. */
    public record Lists(RequisitionProgressView.Availability upcomingInterviews,
                        RequisitionProgressView.Availability pendingEvaluations,
                        RequisitionProgressView.Availability awaitingDecision) { }

    public record Item(RequisitionProgressView.Requisition requisition, List<RequisitionProgressView.StageCount> stageCounts,
                       long totalApplications, long activeApplications, Long upcomingInterviews,
                       Long pendingEvaluations, Long awaitingDecision) { }
}
