package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Task 262: the body of POST /requisitions/{id}/approvals. action is what the current level decides (ApprovalAction).
 * comment is the approver's opinion: required to reject or to ask for more information, optional to approve; a comment
 * without any visible character, such as only spaces or only zero-width spaces, counts as none (WorkflowComment), and
 * line breaks inside a visible comment are kept. expectedRevision is the revision of the approval progress the
 * approver is looking at: the sequence number of the requisition's latest approval event, 1 after the first submit
 * and one more for each decision or submit (GET /requisitions/{id}/approvals, task 267, returns it). A decision taken
 * on an outdated view, such as a second click or a decision someone else took meanwhile, is refused.
 */
public record ApprovalDecisionRequest(
        @NotNull(message = "Vui lòng chọn thao tác duyệt.")
        @Pattern(regexp = ApprovalAction.CODES, message = ApprovalAction.CODES_MESSAGE)
        String action,
        @Size(max = 2_000, message = "Nhận xét tối đa 2.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER, message = "Nhận xét chứa ký tự không hợp lệ.")
        String comment,
        @NotNull(message = "Thiếu số phiên bản tiến độ duyệt đang xem.")
        @Min(value = 1, message = "Số phiên bản tiến độ duyệt không hợp lệ.")
        Integer expectedRevision) {

    public ApprovalDecisionRequest {
        comment = WorkflowComment.orNull(comment);
    }

    public ApprovalAction actionCode() {
        return ApprovalAction.valueOf(action);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported approval decision field");
    }
}
