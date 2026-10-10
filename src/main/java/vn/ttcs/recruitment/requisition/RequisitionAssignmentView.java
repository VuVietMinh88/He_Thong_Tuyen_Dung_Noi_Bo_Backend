package vn.ttcs.recruitment.requisition;

import java.util.List;
import java.util.UUID;

// Task 284: the recruiters of a requisition. Both keys are always present: an unassigned requisition has
// "primaryRecruiter": null and "supportingRecruiters": []. Supporting recruiters are in the order they were added.
public record RequisitionAssignmentView(UUID requisitionId, AssignedRecruiterView primaryRecruiter,
                                        List<AssignedRecruiterView> supportingRecruiters) {
}
