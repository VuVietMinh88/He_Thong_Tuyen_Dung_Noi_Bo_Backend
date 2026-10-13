package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

// Task 303: the body of POST /job-postings. Only the approved requisition is required. A title, job description or
// candidate requirements left out (or blank) are taken from the requisition by JobPostingService: the title is the
// position name, the two texts are copied once. Everything else may stay empty in a draft; showSalary null means false.
// Only the checks a draft needs to be stored are here (size limits, no NUL character); the catalog values are checked
// by JobPostingService.
public record JobPostingCreateRequest(
        @NotNull(message = "Vui lòng chọn yêu cầu tuyển dụng đã duyệt.") UUID requisitionId,
        @Size(max = 255, message = "Tiêu đề tin tối đa 255 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Tiêu đề tin chứa ký tự không hợp lệ.")
        String title,
        @Size(max = 10_000, message = "Mô tả công việc tối đa 10.000 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Mô tả công việc chứa ký tự không hợp lệ.")
        String jobDescription,
        @Size(max = 10_000, message = "Yêu cầu ứng viên tối đa 10.000 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Yêu cầu ứng viên chứa ký tự không hợp lệ.")
        String candidateRequirements,
        @Size(max = 10_000, message = "Quyền lợi tối đa 10.000 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Quyền lợi chứa ký tự không hợp lệ.")
        String benefits,
        UUID workLocationId,
        UUID employmentTypeId,
        LocalDate applicationDeadline,
        Boolean showSalary) {

    // A blank text counts as not given: the title and the two texts are then inherited, benefits stay empty (V21
    // stores benefits not written yet as NULL, never as blank text).
    public JobPostingCreateRequest {
        title = blankToNull(title);
        jobDescription = blankToNull(jobDescription);
        candidateRequirements = blankToNull(candidateRequirements);
        benefits = blankToNull(benefits);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    // status, revision, createdBy, publishedAt, salary amounts... are decided by the server or read from the
    // requisition, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting field");
    }
}
