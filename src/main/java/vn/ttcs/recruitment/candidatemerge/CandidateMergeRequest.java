package vn.ttcs.recruitment.candidatemerge;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.util.UUID;

/**
 * Task 368 (story S5-01): merge the source candidate into the candidate of the path (the target, which stays), with an
 * optional reason for the trail. The staff member is the JWT's subject, never a field; an unknown field is refused
 * (400 INVALID_JSON) so a typo cannot pass silently.
 */
public record CandidateMergeRequest(
        @NotNull(message = "Vui lòng chọn hồ sơ ứng viên cần gộp.") UUID sourceCandidateId,
        @Size(max = 1000, message = "Lý do tối đa 1000 ký tự.") @PlainText(multiline = true) String reason) {

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported candidate merge field");
    }

    @Override public String toString() { return "CandidateMergeRequest[" + sourceCandidateId + ", reason redacted]"; }
}
