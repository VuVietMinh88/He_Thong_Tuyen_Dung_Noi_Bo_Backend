package vn.ttcs.recruitment.requisition;

import java.util.Objects;

/**
 * Task 256: who may decide a level of a requisition's approval chain. The single rule used at submit (is every level
 * decidable?), at each decision, for reading as an approver and for the progress view and the inbox.
 * <ul>
 * <li>A user step is decided by the account it names.</li>
 * <li>A role step is decided by a holder of the role that no user step of the same chain names (so a user step can
 * always be decided by its own person). For a company-wide role (APPROVER, and every role granted
 * REQUISITIONS_WRITE_ALL: HR_MANAGER, ADMIN) any holder qualifies; for the others (HIRING_MANAGER, RECRUITER) only a
 * holder who manages the requisition's department or a department above it.</li>
 * <li>The decider is allowed to sign in, may write and read requisitions (WRITE never implies READ), did not create
 * the requisition, did not submit this round, and has not decided another level of this round.</li>
 * </ul>
 * HR_MANAGER and ADMIN have no override: they decide only the levels that name their role or their account.
 */
public final class ApproverRule {
    private ApproverRule() {
    }

    // Is the level routed to this person? Ignores access, permissions and separation of duties (see admits).
    public static boolean targets(ApprovalLevel level, ApprovalContext context, ApproverCandidate candidate) {
        if (level.approverUserId() != null) {
            return level.approverUserId().equals(candidate.id());
        }
        if (!candidate.roles().contains(level.approverRole()) || context.namedUserIds().contains(candidate.id())) {
            return false;
        }
        return context.companyWideRoles().contains(level.approverRole())
                || candidate.managedDepartmentIds().get().contains(context.departmentId());
    }

    // May this person decide the level now?
    public static boolean admits(ApprovalLevel level, ApprovalContext context, ApproverCandidate candidate) {
        var permissions = candidate.permissions();
        return candidate.accessAllowed()
                && (permissions.contains("REQUISITIONS_WRITE_ALL") || permissions.contains("REQUISITIONS_WRITE_SCOPED"))
                && (permissions.contains("REQUISITIONS_READ_ALL") || permissions.contains("REQUISITIONS_READ_SCOPED"))
                && !Objects.equals(candidate.id(), context.createdBy())
                && !Objects.equals(candidate.id(), context.submittedBy())
                && !context.decidedInRound().contains(candidate.id())
                && targets(level, context, candidate);
    }
}
