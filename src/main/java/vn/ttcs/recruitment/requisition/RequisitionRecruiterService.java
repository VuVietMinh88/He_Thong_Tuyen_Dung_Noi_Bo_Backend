package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Task 284: who recruits for a requisition (story S3-06). HR (REQUISITIONS_WRITE_ALL: HR_MANAGER, ADMIN) assigns one
 * primary recruiter and up to 10 supporting recruiters, hands the requisition over to another primary, and removes
 * supporting recruiters. A department head (REQUISITIONS_WRITE_SCOPED) may not. Anyone who may read the requisition
 * may read who is assigned, and only they: a write answers with the team, so the writer must be a reader too. The
 * rules of the team itself are in RequisitionAssignment. Task 286 records every change (before/after, who, when,
 * note) in requisition_recruiter_changes.
 */
@Service
public class RequisitionRecruiterService {
    // Statuses in which the recruiters may still change; reading is allowed in every status. Only DRAFT exists today.
    // RequisitionStatus asks every new status to be classified here; RequisitionRecruiterStatusTest does not compile
    // until it is.
    static final Set<RequisitionStatus> ASSIGNABLE_STATUSES = EnumSet.of(RequisitionStatus.DRAFT);

    private static final String WRITE_ALL = "REQUISITIONS_WRITE_ALL";

    private final RequisitionService requisitionService;
    private final RecruitmentRequisitionRepository requisitions;
    private final RequisitionRecruiterRepository recruiters;
    private final RequisitionRecruiterChangeRepository changes;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final Clock clock;

    public RequisitionRecruiterService(RequisitionService requisitionService,
                                       RecruitmentRequisitionRepository requisitions,
                                       RequisitionRecruiterRepository recruiters,
                                       RequisitionRecruiterChangeRepository changes, AccountRepository accounts,
                                       AuthSessionRepository sessions, PermissionService permissions, Clock clock) {
        this.requisitionService = requisitionService;
        this.requisitions = requisitions;
        this.recruiters = recruiters;
        this.changes = changes;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.clock = clock;
    }

    // Exactly the access of GET /requisitions/{id}: read permission (403), the requisition exists (404), and a SCOPED
    // reader manages its department (403). Reusing it keeps the two in step when the scope rules change.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RequisitionAssignmentView assignment(Jwt jwt, UUID id) {
        requisitionService.get(jwt, id);
        boolean showEligibility = permissions.forUser(UUID.fromString(jwt.getSubject())).contains(WRITE_ALL);
        return view(id, showEligibility);
    }

    /**
     * Gives recruiterId the role in the request (PRIMARY when left out): a first primary, a handover to another primary
     * (also promoting a supporting recruiter), or one more supporting recruiter.
     * Checks in order: write access (401/403), read access to requisitions at all (403), the requisition exists (404),
     * it is in the caller's read scope (403), its status allows changes (409), nothing to do when the person already
     * has that role (200, nothing written), the person may be assigned (400), the team rules (409). A write answers
     * with the whole team, even when it changed nothing, so like POST /requisitions/{id}/copy it also needs READ for
     * this requisition: WRITE never implies READ.
     * <p>
     * Lock order: the accounts of the actor and of the person assigned (in id order, like account administration and
     * DepartmentService, so role changes and admin locks of those accounts cannot interleave and cannot deadlock), the
     * actor's session, then the requisition row, which every writer of the team takes before reading it. Requisition
     * saves take account, session and requisition in the same order and only lock other tables after that.
     * Nothing can wait after the requisition lock: foreign key checks take FOR KEY SHARE, which the FOR NO KEY UPDATE
     * locks of this transaction do not block. So the caller is checked after the account locks and after the
     * requisition lock, and not again.
     */
    @Transactional
    public RequisitionAssignmentView assign(Jwt jwt, UUID id, RequisitionAssignRequest request) {
        Writer writer = lockWriter(jwt, Set.of(request.recruiterId()));
        RecruitmentRequisition requisition = lockRequisition(jwt, writer, id);
        requireAssignable(requisition);
        RequisitionRecruiterRole role = request.roleCode();
        var team = recruiters.findByRequisition(id);
        // Someone who keeps the role they already have is not checked again, even if they were locked since:
        // nothing changes, like saving a department with its current manager.
        if (!team.holds(request.recruiterId(), role)) {
            requireEligible(writer.lockedAccount(request.recruiterId()));
            apply(id, team.assign(request.recruiterId(), role), writer.actorId(), request.note());
        }
        return view(id, true);
    }

    // Removes a supporting recruiter; nothing to do when the person is not assigned. The primary recruiter is never
    // removed (409), only handed over. Same checks and locks as assign, without an account to assign.
    @Transactional
    public RequisitionAssignmentView unassign(Jwt jwt, UUID id, RequisitionUnassignRequest request) {
        Writer writer = lockWriter(jwt, Set.of());
        RecruitmentRequisition requisition = lockRequisition(jwt, writer, id);
        requireAssignable(requisition);
        recruiters.findByRequisition(id).unassign(request.recruiterId())
                .ifPresent(change -> apply(id, change, writer.actorId(), request.note()));
        return view(id, true);
    }

    // Task 286: the history of recruiter changes of this requisition, newest first. Same access as GET /assignment.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecruiterChangeView> history(Jwt jwt, UUID id) {
        requisitionService.get(jwt, id);
        return changes.findByRequisition(id);
    }

    // Task 286: the team and its history change together, under the requisition lock and with the same moment, so
    // replaying the history always ends in the current team. No-ops and refused requests never reach this point.
    // Recording a handover references the old primary's account (FOR KEY SHARE), which never waits for the
    // FOR NO KEY UPDATE locks of other writes, so no check is needed after it.
    private void apply(UUID id, RecruiterChange change, UUID actorId, String note) {
        Instant at = now();
        recruiters.apply(id, change, actorId, at);
        changes.record(id, change, actorId, at, note);
    }

    private RequisitionAssignmentView view(UUID id, boolean showEligibility) {
        var rows = recruiters.findAssigned(id);
        Map<UUID, RecruiterEligibility> eligibility = Map.of();
        if (showEligibility && !rows.isEmpty()) {
            var ids = rows.stream().map(RequisitionRecruiterRepository.AssignedRecruiterRow::recruiterId)
                    .collect(Collectors.toSet());
            eligibility = accounts.findAllById(ids).stream()
                    .collect(Collectors.toMap(Account::getId, RecruiterEligibility::of));
        }
        Map<UUID, RecruiterEligibility> known = eligibility;
        Function<RequisitionRecruiterRepository.AssignedRecruiterRow, AssignedRecruiterView> toView = row ->
                new AssignedRecruiterView(row.recruiterId(), row.fullName(),
                        showEligibility ? known.get(row.recruiterId()) == RecruiterEligibility.ELIGIBLE : null,
                        row.assignedById(), row.assignedByName(), row.assignedAt());
        AssignedRecruiterView primary = rows.stream()
                .filter(row -> row.role() == RequisitionRecruiterRole.PRIMARY).map(toView).findFirst().orElse(null);
        List<AssignedRecruiterView> supporting = rows.stream()
                .filter(row -> row.role() == RequisitionRecruiterRole.SUPPORTING).map(toView).toList();
        return new RequisitionAssignmentView(id, primary, supporting);
    }

    // The caller of a write once lockWriter holds the accounts and the session.
    private record Writer(UUID actorId, AuthSession session, List<Account> lockedAccounts) {
        Account lockedAccount(UUID id) {
            return lockedAccounts.stream().filter(account -> account.getId().equals(id)).findFirst().orElse(null);
        }
    }

    private Writer lockWriter(Jwt jwt, Set<UUID> otherAccountIds) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        var accountIds = new HashSet<>(otherAccountIds);
        accountIds.add(actorId);
        // An account id that does not exist simply locks nothing; the person assigned is then refused with 400.
        var lockedAccounts = accounts.findAllByIdForUpdate(accountIds);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        lockedAccounts.stream().filter(account -> account.getId().equals(actorId) && account.isAccessAllowed())
                .findFirst().orElseThrow(AuthenticationFailureException::sessionInvalid);
        var writer = new Writer(actorId, session, lockedAccounts);
        requireStillWriter(jwt, writer);
        return writer;
    }

    // The requisition lock can wait for a save of the same requisition, so the caller is checked again after it, and
    // before the 404 (like the other write services). Then the read access of GET /requisitions/{id}, with the
    // permissions just read again, like copy (task 278): a caller without any READ permission of REQUISITIONS gets 403
    // before the 404, so one who may only write learns nothing about the requisition; a SCOPED reader must manage its
    // department, checked under this lock so a department change committed while we waited is respected.
    private RecruitmentRequisition lockRequisition(Jwt jwt, Writer writer, UUID id) {
        var locked = requisitions.findByIdForUpdate(id);
        Set<String> granted = requireStillWriter(jwt, writer);
        AccessScope readScope = AccessScope.read(granted, PermissionModule.REQUISITIONS).orDeny();
        var requisition = locked.orElseThrow(RequisitionRecruiterService::notFound);
        requisitionService.requireInReadScope(writer.actorId(), readScope, requisition.getDepartmentId());
        return requisition;
    }

    // May the caller still change recruiters? The account and session rows stay locked, so an admin lock, a logout or a
    // role change through the account API waits for this transaction; but time passes (token or session expiry) and
    // role_permissions can still change, so all three are checked each time, with the permissions read again.
    // Returns those permissions.
    private Set<String> requireStillWriter(Jwt jwt, Writer writer) {
        var now = clock.instant();
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!writer.session().getUserId().equals(writer.actorId()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Set<String> granted = permissions.forUser(writer.actorId());
        if (!granted.contains(WRITE_ALL)) {
            throw new AccessDeniedException("Assigning recruiters requires REQUISITIONS_WRITE_ALL");
        }
        return granted;
    }

    private static void requireAssignable(RecruitmentRequisition requisition) {
        if (!ASSIGNABLE_STATUSES.contains(requisition.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUISITION_NOT_ASSIGNABLE",
                    "Không thể phân công recruiter cho yêu cầu tuyển dụng ở trạng thái hiện tại.");
        }
    }

    // The account was read while locked, so a role change or an admin lock committed while we waited is seen here.
    private static void requireEligible(Account account) {
        switch (RecruiterEligibility.of(account)) {
            case NOT_RECRUITER -> throw invalidRecruiter("INVALID_REQUISITION_RECRUITER",
                    "Người được phân công phải là tài khoản tồn tại và có vai trò Recruiter.");
            case INACTIVE -> throw invalidRecruiter("REQUISITION_RECRUITER_INACTIVE",
                    "Tài khoản này đang bị khóa hoặc chưa kích hoạt nên không thể được phân công.");
            case ELIGIBLE -> { }
        }
    }

    private static ApiException invalidRecruiter(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of("recruiterId", message));
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response shows the same time a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng.");
    }
}
