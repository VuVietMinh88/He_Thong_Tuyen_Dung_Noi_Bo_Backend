package vn.ttcs.recruitment.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Task 562 (story S8-06): the progress of one requisition for the people who own or work on it: the applications in
 * each current stage, the upcoming interviews, the viewer's own evaluation forms still to submit, the applications
 * waiting for the viewer's hiring decision, and the days left until the needed-by date.
 */
public record RequisitionProgressView(Instant generatedAt, LocalDate businessDate, String scope, Requisition requisition,
                                      List<StageCount> stageCounts, long totalApplications, long activeApplications,
                                      ItemList<UpcomingInterview> upcomingInterviews,
                                      ItemList<PendingEvaluation> pendingEvaluations,
                                      ItemList<AwaitingDecision> awaitingDecision) {

    /** daysRemaining = neededBy - businessDate: negative when overdue, null without a needed-by date. */
    public record Requisition(UUID requisitionId, UUID positionId, String positionName, UUID departmentId,
                              String departmentName, String status, int headcount, long hiredCount,
                              long remainingHeadcount, LocalDate neededBy, Long daysRemaining) { }

    /** The current stage of the requisition's applications (all eight stages, zero included, in pipeline order). */
    public record StageCount(String stage, String label, long count) { }

    /** Whether a list (or its count) is available to the viewer; the reason is null when it is. */
    public record Availability(boolean available, String unavailableReason) { }

    /**
     * A list the viewer may or may not see. available=false comes with a reason and no items, never with an empty
     * list that would look like "nothing to do". total counts every matching row; items holds at most limit of them.
     */
    public record ItemList<T>(boolean available, String unavailableReason, long total, int limit, List<T> items) {
        static <T> ItemList<T> of(long total, int limit, List<T> items) {
            return new ItemList<>(true, null, total, limit, List.copyOf(items));
        }

        static <T> ItemList<T> unavailable(String reason, int limit) {
            return new ItemList<>(false, reason, 0, limit, List.of());
        }
    }

    public record UpcomingInterview(UUID interviewId, UUID applicationId, String candidateName, String applicationStage,
                                    String roundName, Instant startsAt, Instant endsAt, String mode,
                                    boolean viewerOnPanel) { }

    /** formStatus is NOT_STARTED (no form yet) or DRAFT. */
    public record PendingEvaluation(UUID interviewId, UUID applicationId, String candidateName, String roundName,
                                    Instant endedAt, String formStatus) { }

    public record AwaitingDecision(UUID applicationId, String candidateName, UUID interviewId, String roundName,
                                   Instant interviewEndedAt, long submittedEvaluations) { }
}
