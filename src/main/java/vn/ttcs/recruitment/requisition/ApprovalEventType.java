package vn.ttcs.recruitment.requisition;

// One row of requisition_workflow_events (V19 CHECK valid_requisition_workflow_event_type).
public enum ApprovalEventType {
    // A round starts (task 257, and task 264 after more information was asked).
    SUBMITTED,
    // The current level approved (task 263).
    APPROVED,
    // The current level rejected (task 262).
    REJECTED,
    // The current level asked the requester for more information (task 264).
    INFO_REQUESTED
}
