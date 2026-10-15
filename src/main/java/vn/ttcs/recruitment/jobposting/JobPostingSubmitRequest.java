package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Task 308: the body of POST /job-postings/{id}/submit. Everything sent for review comes from the saved posting; save
// it with PUT first. expectedRevision is the revision the author is looking at (409 JOB_POSTING_CHANGED when someone
// changed the posting meanwhile). comment is an optional note for the reviewers, kept in the history; a comment
// without any visible character, such as only spaces or only zero-width spaces, counts as none
// (JobPostingText.invisibleToNull), and line breaks inside a visible comment are kept.
public record JobPostingSubmitRequest(
        @NotNull(message = "Thiếu số phiên bản tin tuyển dụng đang xem.")
        @Min(value = 1, message = "Số phiên bản tin tuyển dụng không hợp lệ.")
        Integer expectedRevision,
        @Size(max = 2_000, message = "Nhận xét tối đa 2.000 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Nhận xét chứa ký tự không hợp lệ.")
        String comment) {

    public JobPostingSubmitRequest {
        comment = JobPostingText.invisibleToNull(comment);
    }

    // status, note, publishedAt... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting submit field");
    }
}
