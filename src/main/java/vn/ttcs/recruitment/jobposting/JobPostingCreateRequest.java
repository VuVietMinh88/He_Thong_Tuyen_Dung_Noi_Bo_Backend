package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.time.LocalDate;
import java.util.UUID;

// Task 303: the body of POST /job-postings. Only the approved requisition is required. A title, job description or
// candidate requirements left out (or blank) are taken from the requisition by JobPostingService: the title is the
// position name, the two texts are copied once. Everything else may stay empty in a draft; showSalary null means false.
// Only the checks of the fields themselves are here; the deadline and the catalog values are checked by
// JobPostingService.
// Task 304: the texts are plain text (@PlainText), never HTML, because candidates read them on the public portal. The
// title stays on one line; the other texts may use line breaks and tabs.
public record JobPostingCreateRequest(
        @NotNull(message = "Vui lòng chọn yêu cầu tuyển dụng đã duyệt.") UUID requisitionId,
        @Size(max = 255, message = "Tiêu đề tin tối đa 255 ký tự.")
        @PlainText(message = "Tiêu đề tin phải là văn bản thuần trên một dòng, có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String title,
        @Size(max = 10_000, message = "Mô tả công việc tối đa 10.000 ký tự.")
        @PlainText(multiline = true,
                message = "Mô tả công việc phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String jobDescription,
        @Size(max = 10_000, message = "Yêu cầu ứng viên tối đa 10.000 ký tự.")
        @PlainText(multiline = true,
                message = "Yêu cầu ứng viên phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String candidateRequirements,
        @Size(max = 10_000, message = "Quyền lợi tối đa 10.000 ký tự.")
        @PlainText(multiline = true,
                message = "Quyền lợi phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String benefits,
        UUID workLocationId,
        UUID employmentTypeId,
        LocalDate applicationDeadline,
        Boolean showSalary) {

    // A blank text counts as not given: the title and the two texts are then inherited, benefits stay empty (V21
    // stores benefits not written yet as NULL, never as blank text). Task 304: the title loses the spaces at both ends;
    // the texts keep their indentation and empty lines, only their line breaks become \n.
    public JobPostingCreateRequest {
        title = JobPostingText.blankToNull(JobPostingText.stripSpaces(title));
        jobDescription = JobPostingText.blankToNull(JobPostingText.normalizeLineBreaks(jobDescription));
        candidateRequirements = JobPostingText.blankToNull(JobPostingText.normalizeLineBreaks(candidateRequirements));
        benefits = JobPostingText.blankToNull(JobPostingText.normalizeLineBreaks(benefits));
    }

    // status, revision, createdBy, publishedAt, salary amounts... are decided by the server or read from the
    // requisition, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting field");
    }
}
