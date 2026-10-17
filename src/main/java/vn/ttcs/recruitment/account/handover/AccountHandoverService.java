package vn.ttcs.recruitment.account.handover;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountNotFoundException;
import vn.ttcs.recruitment.account.AccountSearchRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.approval.ApprovalFlowRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RequisitionHandover;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Feedback item 10 (locking an account): before an administrator locks an account, the lock dialog lists the work
 * the account still holds, so it can be handed over to other people: the requisitions it recruits for (PRIMARY or
 * SUPPORTING, in a status whose recruiters may still change), the departments it manages directly (active, or still
 * with open requisitions), the steps of current approval-flow versions that name it, and the levels of approval
 * rounds in progress that still wait for it. Reading never locks and never blocks a lock.
 * <p>
 * Access is that of the lock itself (role ADMIN and USER_ADMIN_WRITE_ALL) plus REQUISITIONS_READ_ALL, because the
 * answer shows requisitions of every department. SecurityConfiguration checks the same three; they are checked again
 * here, with the permissions read again, like the other account services.
 */
@Service
public class AccountHandoverService {
    private static final List<String> REQUIRED_PERMISSIONS = List.of("USER_ADMIN_WRITE_ALL", "REQUISITIONS_READ_ALL");

    private final AuthService auth;
    private final PermissionService permissions;
    private final AccountSearchRepository search;
    private final RequisitionHandover requisitions;
    private final DepartmentRepository departments;
    private final ApprovalFlowRepository approvalFlows;
    private final Clock clock;

    public AccountHandoverService(AuthService auth, PermissionService permissions, AccountSearchRepository search,
                                  RequisitionHandover requisitions, DepartmentRepository departments,
                                  ApprovalFlowRepository approvalFlows, Clock clock) {
        this.auth = auth;
        this.permissions = permissions;
        this.search = search;
        this.requisitions = requisitions;
        this.departments = departments;
        this.approvalFlows = approvalFlows;
        this.clock = clock;
    }

    // One snapshot (REPEATABLE_READ) for the caller check and the four lists, so the answer never mixes the work of
    // the account before and after a handover committed in between. An account that is already locked is still
    // listed: its work may be handed over later.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountHandoverView items(Jwt jwt, UUID id) {
        Instant now = clock.instant();
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account actor = auth.requireActiveAccount(jwt);
        if (!actor.getRoles().contains(Role.ADMIN)
                || !permissions.forUser(actor.getId()).containsAll(REQUIRED_PERMISSIONS)) {
            throw new AccessDeniedException(
                    "Handover items require ADMIN, USER_ADMIN_WRITE_ALL and REQUISITIONS_READ_ALL");
        }
        var target = search.findById(id, now).orElseThrow(AccountNotFoundException::new);
        return new AccountHandoverView(target.id(), target.fullName(), target.status(),
                requisitions.heldBy(id).stream().map(AccountHandoverView.HandoverRequisition::of).toList(),
                departments.findManagedBy(id).stream().map(AccountHandoverView.HandoverDepartment::of).toList(),
                approvalFlows.findCurrentStepsNaming(id).stream()
                        .map(AccountHandoverView.HandoverApprovalStep::of).toList(),
                requisitions.awaitingDecisionOf(id).stream()
                        .map(AccountHandoverView.HandoverPendingApproval::of).toList());
    }
}
