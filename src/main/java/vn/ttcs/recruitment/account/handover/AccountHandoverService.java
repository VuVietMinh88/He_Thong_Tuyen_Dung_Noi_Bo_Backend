package vn.ttcs.recruitment.account.handover;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountNotFoundException;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.AccountSearchRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.approval.ApprovalFlowRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.department.DepartmentManagerHandover;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RequisitionHandover;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * <p>
 * The handover moves the requisitions to other recruiters, with the rules and the history of the recruiter
 * assignment (RequisitionHandover), and the departments to other managers, with the rules of the department API
 * (DepartmentManagerHandover), all or nothing. It also needs REQUISITIONS_WRITE_ALL, the permission of assigning
 * recruiters, and ORGANIZATION_WRITE_ALL when it moves departments, the permission of PUT /departments/{id}; it keeps
 * REQUISITIONS_READ_ALL because it answers with what the account still holds. It never locks the account and works on
 * an account that is already locked: the dialog calls the unchanged lock API afterwards.
 */
@Service
public class AccountHandoverService {
    private static final List<String> REQUIRED_PERMISSIONS = List.of("USER_ADMIN_WRITE_ALL", "REQUISITIONS_READ_ALL");
    private static final List<String> HANDOVER_PERMISSIONS = List.of("USER_ADMIN_WRITE_ALL", "REQUISITIONS_READ_ALL",
            "REQUISITIONS_WRITE_ALL");
    // Only when the request moves departments: SecurityConfiguration cannot see the body, so only this checks it.
    private static final String DEPARTMENT_PERMISSION = "ORGANIZATION_WRITE_ALL";

    private final AuthService auth;
    private final PermissionService permissions;
    private final AccountSearchRepository search;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final RequisitionHandover requisitions;
    private final DepartmentRepository departments;
    private final DepartmentManagerHandover managers;
    private final ApprovalFlowRepository approvalFlows;
    private final Clock clock;

    public AccountHandoverService(AuthService auth, PermissionService permissions, AccountSearchRepository search,
                                  AccountRepository accounts, AuthSessionRepository sessions,
                                  RequisitionHandover requisitions, DepartmentRepository departments,
                                  DepartmentManagerHandover managers, ApprovalFlowRepository approvalFlows,
                                  Clock clock) {
        this.auth = auth;
        this.permissions = permissions;
        this.search = search;
        this.accounts = accounts;
        this.sessions = sessions;
        this.requisitions = requisitions;
        this.departments = departments;
        this.managers = managers;
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
        return view(id, now);
    }

    /**
     * Hands the requisitions and departments of account id over to the recipients of the request, in one transaction.
     * Checks in order: the body (400, before any lock), then the accounts of the caller, of the account handed over
     * and of every recipient are locked in id order and the caller's session after them, like the other account
     * services; the caller is checked again (401/403) and the account must exist (404). With departments, the tree
     * lock of the department writes comes next, before any row, as in DepartmentService. RequisitionHandover then
     * locks the requisitions, checks the caller once more and changes each team; DepartmentManagerHandover locks the
     * departments after them, checks the caller once more and changes each manager (409/400 on the first item that
     * fails, the requisitions first). Requisitions before departments is the order of the requisition writes and
     * approval decisions (the requisition row, then its department FOR SHARE), so none of them can deadlock with this.
     */
    @Transactional
    public AccountHandoverResult handOver(Jwt jwt, UUID id, AccountHandoverRequest request) {
        Map<String, String> errors = request.errorsFor(id);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.",
                    errors);
        }
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        var ids = new HashSet<UUID>();
        ids.add(actorId);
        ids.add(id);
        request.requisitions().forEach(item -> ids.add(item.toUserId()));
        request.departments().forEach(item -> ids.add(item.toUserId()));
        // A recipient that does not exist locks nothing and is refused with 400 like an assignment.
        Map<UUID, Account> locked = accounts.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account actor = locked.get(actorId);
        if (actor == null || !actor.isAccessAllowed()) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        boolean movesDepartments = !request.departments().isEmpty();
        Runnable callerCheck = () -> requireStillAllowed(jwt, actor, session, movesDepartments);
        callerCheck.run();
        if (!locked.containsKey(id)) {
            throw new AccountNotFoundException();
        }
        if (movesDepartments) {
            // May wait for a department write; the caller is checked again once the next rows are held.
            managers.lockTree();
        }
        if (!request.requisitions().isEmpty()) {
            List<RequisitionHandover.Transfer> transfers = new ArrayList<>();
            for (int index = 0; index < request.requisitions().size(); index++) {
                var item = request.requisitions().get(index);
                transfers.add(new RequisitionHandover.Transfer(AccountHandoverRequest.field(index),
                        item.requisitionId(), item.toUserId()));
            }
            requisitions.transfer(id, transfers, locked, actorId, request.note(), callerCheck);
        }
        if (movesDepartments) {
            List<DepartmentManagerHandover.Transfer> transfers = new ArrayList<>();
            for (int index = 0; index < request.departments().size(); index++) {
                var item = request.departments().get(index);
                transfers.add(new DepartmentManagerHandover.Transfer(AccountHandoverRequest.departmentField(index),
                        item.departmentId(), item.toUserId()));
            }
            managers.transfer(id, transfers, locked, callerCheck);
        }
        return new AccountHandoverResult(request.requisitions().size() + request.departments().size(),
                view(id, clock.instant()));
    }

    // May the caller still hand over? Its account and session stay locked, so an admin lock, a logout or a role change
    // through the account API waits for this transaction; but time passes (token or session expiry) and
    // role_permissions can still change, so all of it is checked after each lock wait, with the permissions read again.
    // movesDepartments: the request also gives departments to other managers, which needs ORGANIZATION_WRITE_ALL.
    private void requireStillAllowed(Jwt jwt, Account actor, AuthSession session, boolean movesDepartments) {
        Instant now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || !session.getUserId().equals(actor.getId()) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Set<String> granted = actor.getRoles().contains(Role.ADMIN) ? permissions.forUser(actor.getId()) : Set.of();
        if (!granted.containsAll(HANDOVER_PERMISSIONS)) {
            throw new AccessDeniedException("Handing work over requires ADMIN, USER_ADMIN_WRITE_ALL, "
                    + "REQUISITIONS_READ_ALL and REQUISITIONS_WRITE_ALL");
        }
        if (movesDepartments && !granted.contains(DEPARTMENT_PERMISSION)) {
            throw new AccessDeniedException("Handing departments over also requires ORGANIZATION_WRITE_ALL");
        }
    }

    private AccountHandoverView view(UUID id, Instant now) {
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
