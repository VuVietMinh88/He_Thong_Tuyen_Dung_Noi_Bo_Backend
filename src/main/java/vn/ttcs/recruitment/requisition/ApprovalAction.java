package vn.ttcs.recruitment.requisition;

/**
 * Tasks 262-264: what an approver of the current level may do. CODES is the pattern of ApprovalDecisionRequest.action:
 * a code that is not listed here is a form error, never a 500.
 */
public enum ApprovalAction {
    // Task 263: the level approves; the next level decides, or after the last level the requisition is approved.
    // A comment is optional.
    APPROVE(ApprovalEventType.APPROVED, false),
    // Task 262: the requisition is rejected. A comment is required.
    REJECT(ApprovalEventType.REJECTED, true),
    // Task 264: the requisition goes back to the requester side for more information. A comment (what to add) is
    // required.
    REQUEST_INFO(ApprovalEventType.INFO_REQUESTED, true);

    public static final String CODES = "APPROVE|REJECT|REQUEST_INFO";
    public static final String CODES_MESSAGE =
            "Thao tác chỉ được là APPROVE (duyệt), REJECT (từ chối) hoặc REQUEST_INFO (yêu cầu bổ sung).";

    private final ApprovalEventType eventType;
    private final boolean commentRequired;

    ApprovalAction(ApprovalEventType eventType, boolean commentRequired) {
        this.eventType = eventType;
        this.commentRequired = commentRequired;
    }

    public ApprovalEventType eventType() {
        return eventType;
    }

    public boolean requiresComment() {
        return commentRequired;
    }
}
