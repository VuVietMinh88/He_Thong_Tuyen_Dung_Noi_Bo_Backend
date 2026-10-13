package vn.ttcs.recruitment.approval;

import java.math.BigDecimal;
import java.util.List;

/**
 * Task 256: how many levels of an approval-flow version apply to a proposed salary. A step without a threshold always
 * applies; a step with a threshold applies only when the salary is strictly above it ("vượt hạn mức"). Level 1 never
 * has a threshold and thresholds never decrease (V18 seal trigger and ApprovalFlowService.validate), so the levels
 * that apply are always the first k, with k at least 1: a higher salary only adds higher levels. Preview and submit
 * share this rule.
 */
public final class ApprovalChain {
    private ApprovalChain() {
    }

    // steps in position order. Throws when the steps are not a sealed chain (a level applies after one that does not,
    // or none applies), which the database already rules out.
    public static int levelCount(List<ApprovalFlowRequest.Step> steps, BigDecimal salary) {
        int count = 0;
        for (int index = 0; index < steps.size(); index++) {
            BigDecimal threshold = steps.get(index).salaryThreshold();
            if (threshold == null || salary.compareTo(threshold) > 0) {
                if (count != index) {
                    throw new IllegalStateException("Approval levels must apply as a prefix of the chain");
                }
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalStateException("The first approval level must always apply");
        }
        return count;
    }
}
