package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.UUID;

// Task 309: what candidates see on the recruitment portal, and exactly what the preview shows. Built only by of(),
// from the row of JobPostingReadRepository.SELECT_ROW, for both endpoints. No editor data (status, revision,
// requisition, creator, reviewer, publisher, times) and no salary unless the posting shows it. Task 320 (story S4-02):
// the public detail is this view plus whether candidates may still apply (PublicJobPostingDetail.of), so the
// preview's content is the public detail without applicationOpen and applicationClosesAt.
// The texts and details are the posting's own, frozen from the submission to the publication. The names of the
// position, the department, the work location and the employment type are read live, so a rename shows at once in
// the preview and on the portal (BA/PO question 16). Only the posting's texts are checked as plain text; clients
// render every string as text.
public record PublicJobPostingView(UUID id, String title, String positionName, String departmentName, int headcount,
                                   String workLocationName, String employmentTypeName, LocalDate applicationDeadline,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMin,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMax,
                                   String jobDescription, String candidateRequirements, String benefits) {

    static PublicJobPostingView of(JobPostingRow row) {
        boolean shown = row.showSalary(); // hidden amounts are dropped here, on the server
        return new PublicJobPostingView(row.id(), row.title(), row.positionName(), row.departmentName(),
                row.headcount(), row.workLocationName(), row.employmentTypeName(), row.applicationDeadline(),
                shown ? row.proposedSalaryMin() : null, shown ? row.proposedSalaryMax() : null,
                row.jobDescription(), row.candidateRequirements(), row.benefits());
    }
}
