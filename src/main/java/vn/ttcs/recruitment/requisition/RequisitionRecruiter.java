package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.util.UUID;

// Task 283: one row of requisition_recruiters (V16): a recruiter currently assigned to a requisition, in which role,
// and who gave them that role when.
public record RequisitionRecruiter(UUID recruiterId, RequisitionRecruiterRole role, UUID assignedBy,
                                   Instant assignedAt) {
}
