package vn.ttcs.recruitment.requisition;

// V13 CHECK valid_requisition_status only allows DRAFT. A new status needs a migration that widens the CHECK
// at the same time as the new enum value, otherwise saving it fails in PostgreSQL.
// Task 197: a new status that is still open (not closed or cancelled) must also be added to
// DepartmentRepository.OPEN_REQUISITION_STATUSES, so that it keeps blocking the deletion of its department.
// DepartmentDeletionIntegrationTest does not compile until the new status is given its group there.
public enum RequisitionStatus {
    DRAFT
}
