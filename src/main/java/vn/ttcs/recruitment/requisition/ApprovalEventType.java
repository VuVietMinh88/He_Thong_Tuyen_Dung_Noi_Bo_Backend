package vn.ttcs.recruitment.requisition;

// One row of requisition_workflow_events (V19, V24 CHECK valid_requisition_workflow_event_type): the approval steps
// (tasks 257-264) and, from task 293, the status changes HR makes.
public enum ApprovalEventType {
    // A round starts (task 257, and task 264 after more information was asked).
    SUBMITTED,
    // The current level approved (task 263).
    APPROVED,
    // The current level rejected (task 262).
    REJECTED,
    // The current level asked the requester for more information (task 264).
    INFO_REQUESTED,
    // HR paused recruiting (task 293, RequisitionStatusChange.PAUSE).
    PAUSED,
    // HR resumed a paused requisition (task 293, RequisitionStatusChange.RESUME).
    RESUMED,
    // HR closed it as recruited in full (task 293, RequisitionStatusChange.FILL).
    FILLED,
    // HR cancelled it (task 293, RequisitionStatusChange.CANCEL).
    CANCELLED
}
