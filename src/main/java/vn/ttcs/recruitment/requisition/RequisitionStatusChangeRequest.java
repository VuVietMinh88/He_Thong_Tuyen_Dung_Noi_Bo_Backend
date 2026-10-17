package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Task 290: the body of POST /requisitions/{id}/status-changes. status is the status HR moves the requisition to
 * (APPROVED resumes a paused one). reason says why, in the frontend's field name: required to pause, to close as
 * filled or to cancel, optional to resume; a reason without any visible character (only spaces, non-breaking spaces,
 * line breaks or zero-width characters) counts as none, like every workflow comment (WorkflowComment), and line breaks
 * are kept. It is stored in the comment of the history row. expectedRevision is the revision of
 * GET /requisitions/{id}/approvals the caller is looking at (0 before the first submission), so a change made on an
 * outdated view is refused. confirmActiveCandidates (task 291) closes as filled or cancels although candidates are
 * still in the pipeline. Four fields, no others.
 */
public record RequisitionStatusChangeRequest(
        @NotNull(message = "Vui lòng chọn trạng thái mới.")
        @Pattern(regexp = RequisitionStatusChange.TARGETS, message = RequisitionStatusChange.TARGETS_MESSAGE)
        String status,
        @Size(max = 2_000, message = "Lý do tối đa 2.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER, message = "Lý do chứa ký tự không hợp lệ.")
        String reason,
        @NotNull(message = "Thiếu số phiên bản lịch sử của yêu cầu đang xem.")
        @Min(value = 0, message = "Số phiên bản lịch sử không hợp lệ.")
        Integer expectedRevision,
        // Task 291: true once the caller saw the candidates still in the pipeline and closes anyway. Absent or null is
        // false; ignored when the change needs no confirmation or nobody is in the pipeline. Never stored.
        Boolean confirmActiveCandidates) {

    public RequisitionStatusChangeRequest {
        reason = WorkflowComment.orNull(reason);
    }

    // Only call it after @Valid has accepted the request: status is then one of the targets.
    public RequisitionStatusChange change() {
        return RequisitionStatusChange.toward(RequisitionStatus.valueOf(status));
    }

    // Task 291: absent and null mean no confirmation.
    public boolean confirmsActiveCandidates() {
        return Boolean.TRUE.equals(confirmActiveCandidates);
    }

    // The actor, the time and the status before are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported requisition status change field");
    }
}
