package vn.ttcs.recruitment.requisition;

/**
 * Tasks 262-264: what an approver of the current level may do. CODES is the pattern of ApprovalDecisionRequest.action:
 * a code that is not listed here is a form error, never a 500.
 */
public enum ApprovalAction {
    // Task 262: the requisition is rejected. A comment is required.
    REJECT(ApprovalEventType.REJECTED, true);

    public static final String CODES = "REJECT";
    public static final String CODES_MESSAGE = "Thao tác chỉ được là REJECT (từ chối).";

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
