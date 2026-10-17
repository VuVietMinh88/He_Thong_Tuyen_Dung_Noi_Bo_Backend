package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Task 320 (story S4-02): the answer of GET /api/v1/public/job-postings/{id}. The public posting (PublicJobPostingView,
// the preview's content, component by component and in the same order) and whether candidates may still apply to it.
// applicationOpen is false once the deadline has passed in the business zone: the posting has left the list, and the
// page shows a notice instead of the apply form. applicationClosesAt is the moment applications close, 00:00 of the
// day after the deadline in the business zone, so a page left open switches to the notice without knowing the zone.
// The apply API (task 326) still checks for itself when the application arrives.
public record PublicJobPostingDetail(UUID id, String title, String positionName, String departmentName,
                                     int headcount, String workLocationName, String employmentTypeName,
                                     LocalDate applicationDeadline,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMin,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMax,
                                     String jobDescription, String candidateRequirements, String benefits,
                                     boolean applicationOpen, Instant applicationClosesAt) {

    // Built from the public view, so the salary is hidden in one place only (PublicJobPostingView.of).
    static PublicJobPostingDetail of(PublicJobPostingView view, boolean applicationOpen, Instant applicationClosesAt) {
        return new PublicJobPostingDetail(view.id(), view.title(), view.positionName(), view.departmentName(),
                view.headcount(), view.workLocationName(), view.employmentTypeName(), view.applicationDeadline(),
                view.salaryMin(), view.salaryMax(), view.jobDescription(), view.candidateRequirements(),
                view.benefits(), applicationOpen, applicationClosesAt);
    }
}
