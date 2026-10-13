package vn.ttcs.recruitment.jobposting;

import java.time.LocalDate;
import java.util.UUID;

// Task 302: everything the authors write on a job posting, saved as a whole. Position, department, headcount and the
// salary amounts are not part of it: they are read from the requisition, which cannot change once approved.
// title, jobDescription and candidateRequirements are never blank (V21 CHECKs). benefits, the two catalog values
// (WORK_LOCATION, EMPLOYMENT_TYPE) and the application deadline may be null while the posting is a draft.
public record JobPostingContent(String title, String jobDescription, String candidateRequirements, String benefits,
                                UUID workLocationId, UUID employmentTypeId, LocalDate applicationDeadline,
                                boolean showSalary) {
}
