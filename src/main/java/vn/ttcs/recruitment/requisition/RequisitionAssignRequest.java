package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// Task 284: POST /requisitions/{id}/assign. {"recruiterId": "...", "note": "..."} is what the frontend already sends:
// role may be left out and then means PRIMARY (assign or hand over the recruiter in charge).
// note is an optional reason for the change. It is checked and accepted already, so the request does not change
// later, but nothing stores it yet: the change history that keeps it is task 286.
public record RequisitionAssignRequest(
        @NotNull(message = "Vui lòng chọn recruiter được phân công.") UUID recruiterId,
        @Pattern(regexp = RequisitionRecruiterRole.CODES,
                message = "Vai trò phân công chỉ được là PRIMARY (recruiter chính) hoặc SUPPORTING (recruiter hỗ trợ).")
        String role,
        @Size(max = 1_000, message = "Ghi chú tối đa 1.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER, message = "Ghi chú chứa ký tự không hợp lệ.")
        String note) {

    public RequisitionAssignRequest {
        note = note == null || note.isBlank() ? null : note;
    }

    // Only call it after @Valid has accepted the request: role is then null or one of the codes.
    RequisitionRecruiterRole roleCode() {
        return role == null ? RequisitionRecruiterRole.PRIMARY : RequisitionRecruiterRole.valueOf(role);
    }

    // assignedBy, assignedAt... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported recruiter assignment field");
    }
}
