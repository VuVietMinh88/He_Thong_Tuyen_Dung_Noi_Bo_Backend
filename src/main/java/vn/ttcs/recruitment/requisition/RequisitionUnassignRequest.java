package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// Task 284: POST /requisitions/{id}/unassign removes a supporting recruiter. There is no role: the primary recruiter
// is never removed, only handed over with /assign. note has the same rules as in RequisitionAssignRequest.
public record RequisitionUnassignRequest(
        @NotNull(message = "Vui lòng chọn recruiter cần bỏ phân công.") UUID recruiterId,
        @Size(max = 1_000, message = "Ghi chú tối đa 1.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER, message = "Ghi chú chứa ký tự không hợp lệ.")
        String note) {

    public RequisitionUnassignRequest {
        note = note == null || note.isBlank() ? null : note;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported recruiter assignment field");
    }
}
