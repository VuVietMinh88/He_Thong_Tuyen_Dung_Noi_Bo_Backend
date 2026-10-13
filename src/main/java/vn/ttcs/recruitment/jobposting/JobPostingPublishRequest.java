package vn.ttcs.recruitment.jobposting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// Task 308: the body of POST /job-postings/{id}/publish. expectedRevision is the revision of the approved posting the
// publisher is looking at, so what is published is what was last approved. Who publishes and when come from the token
// and the server clock, never from the body; there is no comment and no channel to choose.
public record JobPostingPublishRequest(
        @NotNull(message = "Thiếu số phiên bản tin tuyển dụng đang xem.")
        @Min(value = 1, message = "Số phiên bản tin tuyển dụng không hợp lệ.")
        Integer expectedRevision) {

    // status, note, channels, publishedAt... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported job posting publish field");
    }
}
