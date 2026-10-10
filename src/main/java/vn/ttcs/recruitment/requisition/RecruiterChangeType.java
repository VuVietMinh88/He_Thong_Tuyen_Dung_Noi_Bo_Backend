package vn.ttcs.recruitment.requisition;

// Task 283: the four ways the recruiters of a requisition can change.
public enum RecruiterChangeType {
    // A first primary recruiter for a requisition that had none.
    PRIMARY_ASSIGNED,
    // Another recruiter replaces the primary one (a handover); the previous primary leaves the requisition.
    PRIMARY_HANDED_OVER,
    SUPPORTING_ADDED,
    SUPPORTING_REMOVED
}
