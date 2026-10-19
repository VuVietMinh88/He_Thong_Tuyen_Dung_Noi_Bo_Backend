package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Task 355 (story S4-08): the body of POST /job-postings/{id}/unpublish. expectedRevision is the revision of the
// published posting the caller is looking at (409 JOB_POSTING_CHANGED when it changed meanwhile). reason says why the
// posting is taken down and is kept in the history; it is required (JobPostingService.unpublish answers 400
// JOB_POSTING_UNPUBLISH_REASON_REQUIRED before any lock), like the reason of a requisition's pause or closure. A reason
// without any visible character, such as only spaces or only zero-width spaces, counts as none
// (JobPostingText.invisibleToNull), and line breaks inside a visible reason are kept.
public record JobPostingUnpublishRequest(
        @NotNull(message = "Thiếu số phiên bản tin tuyển dụng đang xem.")
        @Min(value = 1, message = "Số phiên bản tin tuyển dụng không hợp lệ.")
        Integer expectedRevision,
        @Size(max = 2_000, message = "Lý do tối đa 2.000 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Lý do chứa ký tự không hợp lệ.")
        String reason) {

    public JobPostingUnpublishRequest {
        reason = JobPostingText.invisibleToNull(reason);
    }

    // status, note, comment, unpublishedAt... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting unpublish field");
    }
}
