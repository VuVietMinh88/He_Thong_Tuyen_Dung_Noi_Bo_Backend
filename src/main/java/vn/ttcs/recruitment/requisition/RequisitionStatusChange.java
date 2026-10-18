package vn.ttcs.recruitment.requisition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Tasks 293/290 (story S3-07): the status changes HR makes outside the approval workflow. target: the status the
 * requisition moves to (the body's status in task 290); eventType: the history row it adds; from: the statuses it may
 * leave. Pausing, closing as filled and cancelling need a reason; resuming does not. Must match V24
 * valid_requisition_workflow_event_transition. RequisitionStatus asks every new status to be placed in these from-sets;
 * RequisitionStatusChangeTest does not compile until it is. Nothing leaves FILLED or CANCELLED: copy the requisition to
 * recruit again (task 278).
 */
public enum RequisitionStatusChange {
    PAUSE(RequisitionStatus.PAUSED, ApprovalEventType.PAUSED, true, EnumSet.of(RequisitionStatus.APPROVED)),
    RESUME(RequisitionStatus.APPROVED, ApprovalEventType.RESUMED, false, EnumSet.of(RequisitionStatus.PAUSED)),
    FILL(RequisitionStatus.FILLED, ApprovalEventType.FILLED, true,
            EnumSet.of(RequisitionStatus.APPROVED, RequisitionStatus.PAUSED)),
    CANCEL(RequisitionStatus.CANCELLED, ApprovalEventType.CANCELLED, true, EnumSet.of(RequisitionStatus.DRAFT,
            RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.RETURNED, RequisitionStatus.APPROVED,
            RequisitionStatus.PAUSED));

    // Task 290: the @Pattern of RequisitionStatusChangeRequest.status. RequisitionStatusChangeTest checks it lists
    // every target.
    public static final String TARGETS = "PAUSED|APPROVED|FILLED|CANCELLED";
    public static final String TARGETS_MESSAGE = "Trạng thái mới chỉ được là PAUSED (tạm dừng), APPROVED (tiếp tục "
            + "tuyển sau khi tạm dừng), FILLED (đã tuyển đủ) hoặc CANCELLED (hủy).";

    private final RequisitionStatus target;
    private final ApprovalEventType eventType;
    private final boolean requiresReason;
    private final Set<RequisitionStatus> from;

    RequisitionStatusChange(RequisitionStatus target, ApprovalEventType eventType, boolean requiresReason,
                            Set<RequisitionStatus> from) {
        this.target = target;
        this.eventType = eventType;
        this.requiresReason = requiresReason;
        this.from = Collections.unmodifiableSet(from);
    }

    public RequisitionStatus target() {
        return target;
    }

    public ApprovalEventType eventType() {
        return eventType;
    }

    public boolean requiresReason() {
        return requiresReason;
    }

    public Set<RequisitionStatus> from() {
        return from;
    }

    public boolean allowedFrom(RequisitionStatus status) {
        return from.contains(status);
    }

    // Task 290: the change whose target is the status HR asked for. Every target belongs to exactly one change.
    public static RequisitionStatusChange toward(RequisitionStatus target) {
        for (RequisitionStatusChange change : values()) {
            if (change.target == target) {
                return change;
            }
        }
        throw new IllegalArgumentException("No status change leads to " + target);
    }
}
