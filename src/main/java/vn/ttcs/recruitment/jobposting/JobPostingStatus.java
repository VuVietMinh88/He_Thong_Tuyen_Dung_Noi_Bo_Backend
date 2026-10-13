package vn.ttcs.recruitment.jobposting;

// V21 CHECK valid_job_posting_status allows only DRAFT; the review workflow (task 308) drops and re-creates that named
// CHECK for its statuses. A new status needs a migration that does the same at the same time as the new enum value,
// otherwise saving it fails in PostgreSQL.
// Task 303: decide whether it may be edited (JobPostingService.EDITABLE_STATUSES). JobPostingStatusTest does not
// compile until it is.
public enum JobPostingStatus {
    // Written by its authors, not sent for review yet.
    DRAFT
}
