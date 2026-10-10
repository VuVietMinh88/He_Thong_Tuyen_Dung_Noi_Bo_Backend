package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 286: one row of the assignment history, newest first. assignedTo, assignedBy, assignedAt and note are the
 * fields the frontend already shows (AssignmentHistoryItem); the others say exactly what changed.
 * changeType PRIMARY_ASSIGNED / PRIMARY_HANDED_OVER: assignedTo is the new primary, previousAssignedTo the primary it
 * replaced (only for a handover, else null). SUPPORTING_ADDED / SUPPORTING_REMOVED: assignedTo is the supporting
 * recruiter added or removed. Names are read when the history is shown, so a renamed account shows its new name.
 */
public record RecruiterChangeView(UUID id, int revision, RecruiterChangeType changeType, UUID recruiterId,
                                  String assignedTo, UUID previousRecruiterId, String previousAssignedTo,
                                  UUID assignedById, String assignedBy, Instant assignedAt, String note) {
}
