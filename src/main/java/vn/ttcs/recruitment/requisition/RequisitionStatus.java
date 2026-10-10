package vn.ttcs.recruitment.requisition;

// V13 CHECK valid_requisition_status only allows DRAFT. A new status needs a migration that widens the CHECK
// at the same time as the new enum value, otherwise saving it fails in PostgreSQL.
// Task 197: a new status that is still open (not closed or cancelled) must also be added to
// DepartmentRepository.OPEN_REQUISITION_STATUSES, so that it keeps blocking the deletion of its department.
// DepartmentDeletionIntegrationTest does not compile until the new status is given its group there.
// Task 272: decide too whether its people still count against the headcount plan of the department
// (HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES); HeadcountPlanRepositoryIntegrationTest does not compile
// until the new status is given its group there.
// Task 279: RequisitionService.copy copies a requisition in any status into a new DRAFT. Decide whether a requisition
// in the new status may be copied (for example a cancelled one), and add a copy test for it.
// Task 284: decide whether a requisition in the new status may still change recruiters
// (RequisitionRecruiterService.ASSIGNABLE_STATUSES; reading stays allowed). RequisitionRecruiterStatusTest does not
// compile until the new status is given its group there.
public enum RequisitionStatus {
    DRAFT
}
