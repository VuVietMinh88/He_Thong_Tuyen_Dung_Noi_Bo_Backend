package vn.ttcs.recruitment.requisition;

// V13 CHECK valid_requisition_status allowed only DRAFT; V19 (task 257) widened it to five statuses and V24 (task 293)
// to the eight below. A new status needs a migration that widens the CHECK at the same time as the new enum value,
// otherwise saving it fails in PostgreSQL.
// Task 197: a new status that is still open (not filled, rejected or cancelled) must also be added to
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
// Task 257: decide whether it may be edited (RequisitionService.EDITABLE_STATUSES) and submitted for approval
// (RequisitionService.SUBMITTABLE_STATUSES). RequisitionWorkflowStatusTest does not compile until it is.
// If the status is reached through the approval workflow, widen V19's valid_requisition_workflow_event_type and
// valid_requisition_workflow_event_transition in a new migration. Decisions only happen in PENDING_APPROVAL.
// Task 303: decide whether job postings may be made from, and still changed for, a requisition in the new status
// (JobPostingService.SOURCE_STATUSES; the public portal shows a posting only while its requisition is in that set).
// JobPostingSourceStatusTest does not compile until the new status is given its group there. Closing postings with
// their requisition is task 292.
// Task 297: decide whether a requisition in the new status can still be overdue in GET /requisitions
// (RequisitionListItemView.OVERDUE_STATUSES). RequisitionOverdueStatusTest does not compile until it is.
// Tasks 293/290: decide from which statuses a requisition may reach the new status and where it may go next
// (RequisitionStatusChange.from); RequisitionStatusChangeTest does not compile until it is. Widen V24's
// valid_requisition_workflow_event_transition in a new migration if a status change reaches it.
public enum RequisitionStatus {
    // Saved by its department, not sent for approval yet (or copied).
    DRAFT,
    // Waiting for the current level of its approval round (task 257).
    PENDING_APPROVAL,
    // An approver asked for more information; the requester edits it and submits it again (task 264).
    RETURNED,
    // Every level of the round approved (task 263). Job postings are made from approved requisitions
    // (JobPostingService.SOURCE_STATUSES).
    APPROVED,
    // An approver rejected it (task 262). Final: copy it to try again.
    REJECTED,
    // HR paused recruiting (task 290); it keeps its headcount and recruiters and can be resumed to APPROVED. Its
    // content is the approved one (not editable).
    PAUSED,
    // Recruited in full (task 290). Final: copy it to recruit again. Its people still count against the plan.
    FILLED,
    // Cancelled by HR (task 290). Final; gives its headcount back.
    CANCELLED
}
