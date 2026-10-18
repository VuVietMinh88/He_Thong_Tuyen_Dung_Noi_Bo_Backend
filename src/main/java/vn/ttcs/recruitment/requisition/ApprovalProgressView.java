package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 267: the approval progress and history of a requisition (GET /requisitions/{id}/approvals, story S3-03).
 * revision is what a decision sends back as expectedRevision (0 before the first submission, then one more for each
 * submit, decision or status change). levels describes the chain of the latest round; history lists every event of
 * every round, newest first, with HR's status changes (task 293) among them. Salary thresholds are never shown, only
 * whether a level was added by the salary (addedBySalaryLimit).
 */
public record ApprovalProgressView(UUID requisitionId, RequisitionStatus status, UUID createdById,
                                   String createdByName, int revision, Integer round, Flow flow, Integer levelCount,
                                   Integer currentLevel, UUID submittedById, String submittedByName,
                                   Instant submittedAt, List<Level> levels, WaitingOn waitingOn, Actions actions,
                                   List<HistoryEntry> history) {

    public record Flow(UUID id, int version, String name) { }

    // status: APPROVED, REJECTED or INFO_REQUESTED once decided; PENDING for the current level; NOT_REACHED otherwise.
    // approverName: who decided, or who the level is routed to (the account, or the role's name) before that.
    public record Level(int level, String stepName, String approverType, UUID approverUserId, String approverRole,
                        String approverRoleName, boolean addedBySalaryLimit, String status, String approverName,
                        UUID decidedById, String comment, Instant actionDate) { }

    public record Person(UUID userId, String fullName) { }

    // Who the requisition waits for: an approval level (kind APPROVAL_LEVEL, with the people who may decide it now)
    // or the requester side after more information was asked (kind REQUESTER, naming the creator).
    public record WaitingOn(String kind, Integer level, String label, Boolean hasEligibleApprover,
                            List<Person> people) { }

    // canDecide: the caller may decide the current level now.
    public record Actions(boolean canDecide) { }

    // flowVersion, levelCount and salaryBasis are only set on SUBMITTED (the snapshot of the round).
    public record HistoryEntry(UUID id, int revision, Integer round, Integer level, ApprovalEventType action,
                               RequisitionStatus statusBefore, RequisitionStatus statusAfter, UUID actorId,
                               String actorName, Instant occurredAt, String comment, Integer flowVersion,
                               Integer levelCount, Long salaryBasis) { }
}
