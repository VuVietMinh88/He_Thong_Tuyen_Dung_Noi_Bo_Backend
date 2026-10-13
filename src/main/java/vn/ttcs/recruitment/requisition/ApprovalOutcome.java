package vn.ttcs.recruitment.requisition;

/**
 * Task 261: what one decision of the current level does: the event recorded at that level and the requisition status
 * before and after it. ApprovalRound.apply computes it; RequisitionApprovalRepository.appendDecision records it.
 */
public record ApprovalOutcome(ApprovalEventType type, int level, RequisitionStatus before, RequisitionStatus after) {
}
