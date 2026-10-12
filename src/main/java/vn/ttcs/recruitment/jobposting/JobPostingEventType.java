package vn.ttcs.recruitment.jobposting;

// Task 308: one row of job_posting_events, the review and publication history of a posting. Must match V22
// valid_job_posting_event_type.
public enum JobPostingEventType {
    // The authors sent the posting for review (from DRAFT or REVISION_REQUESTED).
    SUBMITTED,
    // HR approved the posting.
    APPROVED,
    // HR asked the authors for changes, with a comment saying what to change.
    REVISION_REQUESTED,
    // HR published the approved posting.
    PUBLISHED,
    // Task 292: the requisition was closed as filled or cancelled; actor, time and reason are the requisition's. Must
    // match V25 valid_job_posting_event_type.
    CLOSED
}
