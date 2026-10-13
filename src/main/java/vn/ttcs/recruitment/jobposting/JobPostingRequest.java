package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.time.LocalDate;
import java.util.UUID;

// Task 303: the body of PUT /job-postings/{id}, which replaces the whole content of a draft. expectedRevision is the
// revision of the posting the author is looking at: a save made on an outdated view, such as a second tab or a change
// someone else saved meanwhile, is refused (409 JOB_POSTING_CHANGED). The title and the two texts are required (V21
// never stores them blank); benefits, the catalog values and the deadline left out are cleared, so a draft stays
// saveable while it is being written. The requisition of a posting never changes, so requisitionId is not accepted.
// Task 304: the same text rules as JobPostingCreateRequest (plain text, a one-line title). A text inherited from the
// requisition with HTML in it is refused here, so the author rewrites it before the draft can be saved again.
public record JobPostingRequest(
        @NotNull(message = "Thiếu số phiên bản tin tuyển dụng đang xem.")
        @Min(value = 1, message = "Số phiên bản tin tuyển dụng không hợp lệ.")
        Integer expectedRevision,
        @NotBlank(message = "Tiêu đề tin không được để trống.")
        @Size(max = 255, message = "Tiêu đề tin tối đa 255 ký tự.")
        @PlainText(message = "Tiêu đề tin phải là văn bản thuần trên một dòng, có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String title,
        @NotBlank(message = "Mô tả công việc không được để trống.")
        @Size(max = 10_000, message = "Mô tả công việc tối đa 10.000 ký tự.")
        @PlainText(multiline = true,
                message = "Mô tả công việc phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.")
        String jobDescription,
        @NotBlank(message = "Yêu cầu ứng viên không được để trống.")
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
        @NotNull(message = "Vui lòng chọn có hiển thị mức lương hay không.") Boolean showSalary) {

    // A blank text becomes null, so a blank title or text only gets the @NotBlank message. Task 304: the title loses
    // the spaces at both ends; the texts keep their indentation and empty lines, only their line breaks become \n.
    public JobPostingRequest {
        title = JobPostingText.blankToNull(JobPostingText.stripSpaces(title));
        jobDescription = JobPostingText.blankToNull(JobPostingText.normalizeLineBreaks(jobDescription));
        candidateRequirements = JobPostingText.blankToNull(JobPostingText.normalizeLineBreaks(candidateRequirements));
        benefits = JobPostingText.blankToNull(JobPostingText.normalizeLineBreaks(benefits));
    }

    // The content to save. Only call it after @Valid has accepted the request: showSalary is then not null.
    JobPostingContent content() {
        return new JobPostingContent(title, jobDescription, candidateRequirements, benefits, workLocationId,
                employmentTypeId, applicationDeadline, showSalary);
    }

    // requisitionId, status, revision, createdBy, publishedAt, salary amounts... are decided by the server or read
    // from the requisition, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting field");
    }
}
