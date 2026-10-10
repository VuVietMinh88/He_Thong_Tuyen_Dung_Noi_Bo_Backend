package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Task 278: the optional body of POST /requisitions/{id}/copy. Everything else comes from the source requisition, so
// a client cannot send other fields; edit the copy afterwards with PUT. headcountOverrideReason has the same rules and
// meaning as in RequisitionRequest (task 275): only the HR manager may use it, only when the copy goes over the plan.
public record RequisitionCopyRequest(
        @Size(max = 1_000, message = "Lý do vượt định biên tối đa 1.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER,
                message = "Lý do vượt định biên chứa ký tự không hợp lệ.")
        String headcountOverrideReason) {

    public RequisitionCopyRequest {
        headcountOverrideReason = headcountOverrideReason == null || headcountOverrideReason.isBlank()
                ? null : headcountOverrideReason;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported requisition copy field");
    }
}
