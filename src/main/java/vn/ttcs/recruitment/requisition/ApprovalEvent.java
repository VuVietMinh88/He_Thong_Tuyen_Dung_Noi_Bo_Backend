package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the approval history (requisition_workflow_events, V19). round is set on every approval event; level on
 * decisions only; flowId, flowVersion, levelCount and salaryBasis on SUBMITTED only (the snapshot of the round).
 * actorName is read when the history is read, so a renamed account shows its new name.
 */
public record ApprovalEvent(UUID id, int revision, ApprovalEventType type, RequisitionStatus statusBefore,
                            RequisitionStatus statusAfter, Integer round, Integer level, UUID flowId,
                            Integer flowVersion, Integer levelCount, Long salaryBasis, UUID actorId, String actorName,
                            Instant occurredAt, String comment) {
}
