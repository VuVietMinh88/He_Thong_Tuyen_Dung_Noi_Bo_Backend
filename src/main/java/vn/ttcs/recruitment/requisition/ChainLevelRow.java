package vn.ttcs.recruitment.requisition;

/**
 * Task 261: one level of a round's chain as it is shown: approverName is the full name of the account a user step
 * names, approverRoleName the display name of a role step's role (read when shown, so renames are followed).
 */
public record ChainLevelRow(ApprovalLevel level, String approverName, String approverRoleName) {
    // "Cấp 2 · Phạm Minh Đức" or "Cấp 1 · Trưởng phòng Nhân sự".
    public String stepName() {
        return "Cấp " + level.position() + " · " + (level.approverUserId() != null ? approverName : approverRoleName);
    }
}
