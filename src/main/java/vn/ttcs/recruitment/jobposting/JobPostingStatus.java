package vn.ttcs.recruitment.jobposting;

// V21 CHECK valid_job_posting_status allowed only DRAFT; V22 (task 308) widened it to the five statuses below. A new
// status needs a migration that drops and re-creates that named CHECK (and, if it may lack the portal details like
// DRAFT and REVISION_REQUESTED, job_posting_details_required) at the same time as the new enum value, otherwise
// saving it fails in PostgreSQL.
// Task 303: decide whether it may be edited (JobPostingService.EDITABLE_STATUSES). JobPostingStatusTest does not
// compile until it is.
// Task 308: decide whether it may be submitted (JobPostingService.SUBMITTABLE_STATUSES), decided
// (JobPostingDecision.from) and published (JobPostingApprovalService.PUBLISHABLE_STATUSES); widen V22's
// valid_job_posting_event_transition if a workflow step reaches it. JobPostingStatusTest and JobPostingDecisionTest do
// not compile until it is.
// Task 309: decide whether candidates see it on the public portal (JobPostingService.PUBLIC_STATUSES).
// JobPostingStatusTest does not compile until it is.
// Closing postings when their requisition closes is task 292 (S3-07), which will add CLOSED.
public enum JobPostingStatus {
    // Written by its authors, not sent for review yet.
    DRAFT,
    // Sent for review (task 308): HR approves it or asks for changes. Its content can no longer be edited.
    PENDING_APPROVAL,
    // HR asked for changes (task 308); the authors edit it and send it for review again.
    REVISION_REQUESTED,
    // HR approved it (task 308). Not public until it is published; HR may still ask for changes.
    APPROVED,
    // Published (task 308). Candidates see it on the public portal (task 309) while its deadline has not passed.
    PUBLISHED
}
