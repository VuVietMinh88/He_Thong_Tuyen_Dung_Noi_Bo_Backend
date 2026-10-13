package vn.ttcs.recruitment.requisition;

import vn.ttcs.recruitment.approval.ApprovalFlowRequest;

import java.util.UUID;

/**
 * Task 256: one level of the approval chain that applies to a requisition. position is the step's position in the
 * sealed flow version. Exactly one of approverUserId (one account decides) and approverRole (the holders of that role
 * decide, see ApproverRule) is set. addedBySalaryLimit: the level applies only because the proposed salary is above
 * its threshold; the threshold itself is never shown to clients.
 */
public record ApprovalLevel(int position, UUID approverUserId, String approverRole, boolean addedBySalaryLimit) {
    public static ApprovalLevel of(ApprovalFlowRequest.Step step) {
        return new ApprovalLevel(step.position(), step.approverUserId(), step.approverRole(),
                step.salaryThreshold() != null);
    }
}
