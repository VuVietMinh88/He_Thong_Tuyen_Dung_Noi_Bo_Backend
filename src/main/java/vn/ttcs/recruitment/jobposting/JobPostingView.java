package vn.ttcs.recruitment.jobposting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

// Task 303: a job posting as its authors see it. Only callers who may compose postings read it (JOB_POSTINGS write
// and read permission, and for a recruiter only postings of the requisitions assigned to them).
// Position, department, headcount and the salary are read from the requisition, which cannot change once approved.
// salaryMin/salaryMax are the requisition's approved proposal (whole VND per person and month), shown read-only so
// the author sees what showSalary would publish; the posting never stores an amount. Fields not filled in yet are
// JSON null. People are an id and a name, never an email.
// actions tells the viewer what it may do with the posting now, so the frontend hides buttons the server would refuse.
// Task 308: lastReview is HR's latest decision (approval or request for changes), null before the first one.
// Task 310: publishedById, publishedByName and publishedAt say who published the posting and when; null until then.
public record JobPostingView(UUID id, UUID requisitionId, JobPostingStatus status, int revision, String title,
                             UUID positionId, String positionName, UUID departmentId, String departmentName,
                             int headcount, String jobDescription, String candidateRequirements, String benefits,
                             UUID workLocationId, String workLocationName, UUID employmentTypeId,
                             String employmentTypeName, LocalDate applicationDeadline, boolean showSalary,
                             Long salaryMin, Long salaryMax, UUID createdById, String createdByName,
                             Instant createdAt, Instant updatedAt, Actions actions, LastReview lastReview,
                             UUID publishedById, String publishedByName, Instant publishedAt) {

    // canEdit: PUT would pass the status checks (the posting is editable and its requisition is still approved).
    // Task 308: canSubmit the same for sending it for review; canApprove, canRequestRevision and canPublish the status
    // and permission checks of the decision and the publication, and for canApprove also the rule that nobody
    // approves a posting they wrote, edited or last submitted (JobPostingReviewRule). The application deadline is not
    // part of the flags: a passed deadline is explained by its own 409 (JOB_POSTING_DEADLINE_PASSED).
    // Task 290: every flag also needs the requisition still approved: a paused, filled or cancelled requisition
    // freezes its postings (409 JOB_POSTING_REQUISITION_NOT_APPROVED).
    public record Actions(boolean canEdit, boolean canSubmit, boolean canApprove, boolean canRequestRevision,
                          boolean canPublish) { }

    // Task 308: action is APPROVED or REVISION_REQUESTED; comment is null when the reviewer wrote none.
    public record LastReview(JobPostingEventType action, String comment, UUID actorId, String actorName,
                             Instant occurredAt) { }

    // viewer: the caller who reads the posting; authors: the posting's authors (JobPostingAuthorRepository), read in
    // the same transaction. A caller who may read the posting may also edit and submit it, so canEdit and canSubmit
    // need neither; the review actions need both.
    static JobPostingView from(JobPostingRow row, JobPostingAccess.Caller viewer, Set<UUID> authors) {
        boolean source = JobPostingService.SOURCE_STATUSES.contains(row.requisitionStatus());
        boolean reviewer = viewer.reviewer();
        Actions actions = new Actions(
                JobPostingService.EDITABLE_STATUSES.contains(row.status()) && source,
                JobPostingService.SUBMITTABLE_STATUSES.contains(row.status()) && source,
                reviewer && JobPostingDecision.APPROVE.from().contains(row.status()) && source
                        && JobPostingReviewRule.mayApprove(viewer.id(), authors, row.latestSubmitterId()),
                reviewer && JobPostingDecision.REQUEST_REVISION.from().contains(row.status()) && source,
                reviewer && JobPostingApprovalService.PUBLISHABLE_STATUSES.contains(row.status()) && source);
        LastReview lastReview = row.reviewAction() == null ? null : new LastReview(row.reviewAction(),
                row.reviewComment(), row.reviewActorId(), row.reviewActorName(), row.reviewAt());
        return new JobPostingView(row.id(), row.requisitionId(), row.status(), row.revision(), row.title(),
                row.positionId(), row.positionName(), row.departmentId(), row.departmentName(), row.headcount(),
                row.jobDescription(), row.candidateRequirements(), row.benefits(), row.workLocationId(),
                row.workLocationName(), row.employmentTypeId(), row.employmentTypeName(), row.applicationDeadline(),
                row.showSalary(), row.proposedSalaryMin(), row.proposedSalaryMax(), row.createdBy(),
                row.createdByName(), row.createdAt(), row.updatedAt(), actions, lastReview, row.publishedBy(),
                row.publishedByName(), row.publishedAt());
    }
}
