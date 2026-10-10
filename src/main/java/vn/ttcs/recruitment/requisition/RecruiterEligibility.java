package vn.ttcs.recruitment.requisition;

import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.Role;

// Task 284: may this account be given a role on a requisition? The only place this rule lives: the API uses it before
// assigning, and the assignment view shows it as "eligible" to the HR users who manage the team.
public enum RecruiterEligibility {
    ELIGIBLE,
    // No such account, or the account does not hold the RECRUITER role.
    NOT_RECRUITER,
    // A recruiter who cannot work now: locked by an administrator, or not activated yet. A temporary login lockout
    // after wrong passwords does not count; it ends by itself.
    INACTIVE;

    public static RecruiterEligibility of(Account account) {
        if (account == null || !account.getRoles().contains(Role.RECRUITER)) {
            return NOT_RECRUITER;
        }
        return account.isAccessAllowed() ? ELIGIBLE : INACTIVE;
    }
}
