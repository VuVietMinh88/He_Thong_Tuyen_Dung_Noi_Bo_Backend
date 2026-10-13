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
public record JobPostingView(UUID id, UUID requisitionId, JobPostingStatus status, int revision, String title,
                             UUID positionId, String positionName, UUID departmentId, String departmentName,
                             int headcount, String jobDescription, String candidateRequirements, String benefits,
                             UUID workLocationId, String workLocationName, UUID employmentTypeId,
                             String employmentTypeName, LocalDate applicationDeadline, boolean showSalary,
                             Long salaryMin, Long salaryMax, UUID createdById, String createdByName,
                             Instant createdAt, Instant updatedAt, Actions actions) {

    // canEdit: PUT would pass the status checks (the posting is editable and its requisition is still approved).
    public record Actions(boolean canEdit) { }

    // viewer: the caller who reads the posting; authors: the posting's authors (JobPostingAuthorRepository), read in
    // the same transaction. A caller who may read the posting may also edit it, so canEdit needs neither; the review
    // actions of task 308 will.
    static JobPostingView from(JobPostingRow row, JobPostingAccess.Caller viewer, Set<UUID> authors) {
        boolean canEdit = JobPostingService.EDITABLE_STATUSES.contains(row.status())
                && JobPostingService.SOURCE_STATUSES.contains(row.requisitionStatus());
        return new JobPostingView(row.id(), row.requisitionId(), row.status(), row.revision(), row.title(),
                row.positionId(), row.positionName(), row.departmentId(), row.departmentName(), row.headcount(),
                row.jobDescription(), row.candidateRequirements(), row.benefits(), row.workLocationId(),
                row.workLocationName(), row.employmentTypeId(), row.employmentTypeName(), row.applicationDeadline(),
                row.showSalary(), row.proposedSalaryMin(), row.proposedSalaryMax(), row.createdBy(),
                row.createdByName(), row.createdAt(), row.updatedAt(), new Actions(canEdit));
    }
}
