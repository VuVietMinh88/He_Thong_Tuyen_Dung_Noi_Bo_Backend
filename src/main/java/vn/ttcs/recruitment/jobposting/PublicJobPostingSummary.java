package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.UUID;

// Task 309: one posting of the public list. The texts are only in the detail (PublicJobPostingView).
public record PublicJobPostingSummary(UUID id, String title, String positionName, String departmentName,
                                      String workLocationName, String employmentTypeName,
                                      LocalDate applicationDeadline,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMin,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMax) {

    // Built from the public view, so the salary is hidden in one place only (PublicJobPostingView.of).
    static PublicJobPostingSummary of(PublicJobPostingView view) {
        return new PublicJobPostingSummary(view.id(), view.title(), view.positionName(), view.departmentName(),
                view.workLocationName(), view.employmentTypeName(), view.applicationDeadline(), view.salaryMin(),
                view.salaryMax());
    }
}
