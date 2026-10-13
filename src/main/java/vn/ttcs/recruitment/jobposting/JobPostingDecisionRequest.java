package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Task 308: the body of POST /job-postings/{id}/approvals. action is what HR decides (JobPostingDecision). comment is
 * the reviewer's opinion: required to ask for changes, optional to approve; a comment without any visible character,
 * such as only spaces or only zero-width spaces, counts as none (JobPostingText.invisibleToNull), and line breaks
 * inside a visible comment are kept. expectedRevision is the revision of the posting the reviewer is looking at (the
 * preview's revision from task 309): a decision taken on an outdated view, such as a second click or a change made
 * meanwhile, is refused.
 */
public record JobPostingDecisionRequest(
        @NotNull(message = "Vui lòng chọn thao tác duyệt.")
        @Pattern(regexp = JobPostingDecision.CODES, message = JobPostingDecision.CODES_MESSAGE)
        String action,
        @Size(max = 2_000, message = "Nhận xét tối đa 2.000 ký tự.")
        @Pattern(regexp = JobPostingText.NO_NUL_CHARACTER, message = "Nhận xét chứa ký tự không hợp lệ.")
        String comment,
        @NotNull(message = "Thiếu số phiên bản tin tuyển dụng đang xem.")
        @Min(value = 1, message = "Số phiên bản tin tuyển dụng không hợp lệ.")
        Integer expectedRevision) {

    public JobPostingDecisionRequest {
        comment = JobPostingText.invisibleToNull(comment);
    }

    // Only call it after @Valid has accepted the request: action is then one of JobPostingDecision.CODES.
    public JobPostingDecision decision() {
        return JobPostingDecision.valueOf(action);
    }

    // status, note, reason, feedback, approvedAt... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting decision field");
    }
}
