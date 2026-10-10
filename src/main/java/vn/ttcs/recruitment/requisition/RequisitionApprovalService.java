package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tasks 262-264: the decisions of the approval levels of a requisition (story S3-02). Only the current level decides,
 * and only someone ApproverRule admits for it: not the creator, not the submitter, not someone who decided another
 * level of the round. Every decision is added to the approval history with its actor, time, outcome and comment
 * (task 261), in the same transaction as the status it leads to.
 */
@Service
public class RequisitionApprovalService {
    private final RecruitmentRequisitionRepository requisitions;
    private final RequisitionApprovalRepository approvals;
    private final ApprovalParticipants participants;
    private final ApproverDirectory directory;
    private final DepartmentRepository departments;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final Clock clock;

    public RequisitionApprovalService(RecruitmentRequisitionRepository requisitions,
                                      RequisitionApprovalRepository approvals, ApprovalParticipants participants,
                                      ApproverDirectory directory, DepartmentRepository departments,
                                      AccountRepository accounts, AuthSessionRepository sessions,
                                      PermissionService permissions, Clock clock) {
        this.requisitions = requisitions;
        this.approvals = approvals;
        this.participants = participants;
        this.directory = directory;
        this.departments = departments;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.clock = clock;
    }

    /**
     * Task 262: the current level decides. Checks in order: the body (400), a comment when the action needs one (400,
     * before any lock), write access (401/403), read access to requisitions at all (403, before the lookup: the answer
     * shows the requisition), the requisition exists (404), the caller may read it, by department or as a participant
     * of its approval (403), it waits for approval (409), the caller looks at its latest progress (409), the caller
     * may decide its current level (403). The two 409s come before the last 403 on purpose: an approver who lost a race
     * to another holder of the same role gets a message to reload, not a permission error.
     * <p>
     * Lock order like every requisition write: the actor's account, the actor's session, the requisition row, then the
     * department row FOR SHARE, so that a change of its manager (which decides who may take a HIRING_MANAGER level)
     * waits for this decision or is seen by it. The caller is checked again after each lock. The account read under
     * its lock gives the caller's current roles. Nothing can wait after the department lock: the history row only
     * takes FOR KEY SHARE on rows that nobody locks for update (the requisition is ours, accounts are only locked by
     * their owners' writes, which the actor's lock serializes, and flow versions are sealed).
     */
    @Transactional
    public RequisitionView decide(Jwt jwt, UUID id, ApprovalDecisionRequest request) {
        ApprovalAction action = request.actionCode();
        if (action.requiresComment() && request.comment() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPROVAL_COMMENT_REQUIRED", COMMENT_REQUIRED,
                    Map.of("comment", COMMENT_REQUIRED));
        }
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        var locked = requisitions.findByIdForUpdate(id);
        Set<String> granted = requireStillWriter(jwt, writer);
        AccessScope.read(granted, PermissionModule.REQUISITIONS).orDeny();
        var requisition = locked.orElseThrow(RequisitionApprovalService::notFound);
        departments.findActiveForShare(requisition.getDepartmentId());
        granted = requireStillWriter(jwt, writer);
        AccessScope readScope = AccessScope.read(granted, PermissionModule.REQUISITIONS).orDeny();
        List<ApprovalEvent> events = approvals.findEvents(id);
        ApproverCandidate me = directory.candidate(writer.account(), granted);
        if (!participants.readable(me, readScope, requisition, () -> events)) {
            throw new AccessDeniedException("The caller may not read this requisition");
        }
        if (requisition.getStatus() != RequisitionStatus.PENDING_APPROVAL) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUISITION_NOT_PENDING_APPROVAL",
                    "Yêu cầu tuyển dụng không còn ở trạng thái chờ duyệt. Vui lòng tải lại để xem trạng thái mới nhất.");
        }
        if (request.expectedRevision() != ApprovalRound.lastRevision(events)) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_STATE_CHANGED",
                    "Tiến độ duyệt đã thay đổi (có thể người khác vừa xử lý). Vui lòng tải lại trước khi thao tác.");
        }
        ApprovalRound round = ApprovalRound.latest(events).filter(ApprovalRound::open)
                .orElseThrow(() -> new IllegalStateException("Requisition " + id + " waits without an open round"));
        List<ApprovalLevel> levels = approvals.findChainLevels(round.flowId(), round.flowVersion(), round.levelCount())
                .stream().map(ChainLevelRow::level).toList();
        var context = round.context(requisition, directory.companyWideRoles(), levels);
        if (!ApproverRule.admits(levels.get(round.currentLevel() - 1), context, me)) {
            throw new AccessDeniedException("The caller may not decide the current approval level");
        }
        ApprovalOutcome outcome = round.apply(action);
        approvals.appendDecision(id, round.round(), outcome, writer.actorId(), now(), request.comment());
        requisition.moveTo(outcome.before(), outcome.after());
        requisitions.flush();
        return RequisitionView.from(requisition);
    }

    private static final String COMMENT_REQUIRED = "Vui lòng nhập nhận xét khi từ chối hoặc yêu cầu bổ sung.";

    // The caller of a write once lockWriter holds the account and the session.
    private record Writer(UUID actorId, Account account, AuthSession session) { }

    private Writer lockWriter(Jwt jwt) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the other write services: the actor's account first, then the actor's session. Role,
        // lock and logout changes of the actor wait for these locks.
        Account account = accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        return new Writer(actorId, account, session);
    }

    // May the caller still write requisitions? The account and session rows stay locked, but time passes (token or
    // session expiry) and role_permissions can still change, so all three are checked each time, with the permissions
    // read again. Returns those permissions.
    private Set<String> requireStillWriter(Jwt jwt, Writer writer) {
        var now = clock.instant();
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!writer.session().getUserId().equals(writer.actorId()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Set<String> granted = permissions.forUser(writer.actorId());
        AccessScope.write(granted, PermissionModule.REQUISITIONS).orDeny();
        return granted;
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response shows the same time a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng.");
    }
}
