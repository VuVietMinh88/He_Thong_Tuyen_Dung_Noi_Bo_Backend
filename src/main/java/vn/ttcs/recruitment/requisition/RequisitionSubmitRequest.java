package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Task 257: the optional body of POST /requisitions/{id}/submit: a note for the approvers, kept in the approval
// history. Everything submitted comes from the saved requisition; edit it with PUT first. A note without any visible
// character, such as only spaces or only zero-width spaces, means no note (WorkflowComment).
public record RequisitionSubmitRequest(
        @Size(max = 2_000, message = "Ghi chú tối đa 2.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER, message = "Ghi chú chứa ký tự không hợp lệ.")
        String comment) {

    public RequisitionSubmitRequest {
        comment = WorkflowComment.orNull(comment);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported requisition submit field");
    }
}
