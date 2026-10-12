package vn.ttcs.recruitment.requisition;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 290 (story S3-07): HR pauses, resumes, closes as filled or cancels a requisition with a reason
 * (RequisitionStatusChange). Only REQUISITIONS_WRITE_ALL (HR_MANAGER, ADMIN) changes a status, like recruiter
 * assignment; a department head (REQUISITIONS_WRITE_SCOPED) may not. Every change adds one row to the requisition's
 * history (task 293) in the same transaction as the new status.
 * <p>
 * Checks in order: the body (400), a reason when the change needs one (400, before any lock), write access (401/403),
 * read access to requisitions at all (403, before the lookup: the answer shows the requisition), the requisition
 * exists (404), the caller may read it by department (403; taking part in its approval does not count), the change is
 * allowed from its status (409), the caller looks at its latest history (409). The status 409 comes before the
 * revision 409, as in a decision, so a second click is told the change is no longer possible rather than stale.
 * Then, for a closure, the active pipeline (409 unless confirmed, task 291), then the listeners (task 292 closes
 * postings afterwards).
 * <p>
 * Lock order like every requisition write: the actor's account, the actor's session, the requisition row, then only
 * what the listeners lock after it (job postings, task 292); the pipeline source reads under the requisition lock and
 * locks nothing. The caller is checked again after each lock. No
 * department lock: no rule here depends on its manager, as in assignment. No headcount plan lock: no change leads from
 * a status the plan does not count to one it counts (CountedRequisitionStatusChangeTest). Nothing waits after the
 * requisition lock: the history row only takes FOR KEY SHARE on the requisition (ours) and on the actor's account
 * (ours). READ COMMITTED on purpose: rows read after the requisition lock include everything committed while this
 * request waited. updatedAt (the last content edit) does not change.
 */
@Service
public class RequisitionStatusService {
    private static final String WRITE_ALL = "REQUISITIONS_WRITE_ALL";
    private static final String REASON_REQUIRED =
            "Vui lòng nhập lý do khi tạm dừng, đóng vì đã tuyển đủ hoặc hủy yêu cầu tuyển dụng.";
    private static final String NOT_ALLOWED = "Không thể chuyển yêu cầu tuyển dụng từ trạng thái hiện tại sang trạng "
            + "thái đã chọn. Vui lòng tải lại để xem trạng thái mới nhất.";
    private static final String STATE_CHANGED = "Yêu cầu tuyển dụng vừa có thay đổi khác (có thể người khác vừa xử "
            + "lý). Vui lòng tải lại trước khi thao tác.";

    private final RequisitionService requisitionService;
    private final RecruitmentRequisitionRepository requisitions;
    private final RequisitionApprovalRepository approvals;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final AuthService auth;
    private final RequisitionPipelineSource pipeline;
    private final ObjectProvider<RequisitionStatusListener> listeners;
    private final Clock clock;

    public RequisitionStatusService(RequisitionService requisitionService,
                                    RecruitmentRequisitionRepository requisitions,
                                    RequisitionApprovalRepository approvals, AccountRepository accounts,
                                    AuthSessionRepository sessions, PermissionService permissions, AuthService auth,
                                    RequisitionPipelineSource pipeline,
                                    ObjectProvider<RequisitionStatusListener> listeners, Clock clock) {
        this.requisitionService = requisitionService;
        this.requisitions = requisitions;
        this.approvals = approvals;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.auth = auth;
        this.pipeline = pipeline;
        this.listeners = listeners;
        this.clock = clock;
    }

    @Transactional
    public RequisitionView change(Jwt jwt, UUID id, RequisitionStatusChangeRequest request) {
        RequisitionStatusChange change = request.change();
        if (change.requiresReason() && request.reason() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUISITION_STATUS_REASON_REQUIRED", REASON_REQUIRED,
                    Map.of("reason", REASON_REQUIRED));
        }
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        var locked = requisitions.findByIdForUpdate(id);
        Set<String> granted = requireStillWriter(jwt, writer);
        AccessScope readScope = AccessScope.read(granted, PermissionModule.REQUISITIONS).orDeny();
        RecruitmentRequisition requisition = locked.orElseThrow(RequisitionStatusService::notFound);
        requisitionService.requireInReadScope(writer.actorId(), readScope, requisition.getDepartmentId());
        RequisitionStatus before = requisition.getStatus();
        if (!change.allowedFrom(before)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUISITION_STATUS_CHANGE_NOT_ALLOWED", NOT_ALLOWED);
        }
        if (request.expectedRevision() != ApprovalRound.lastRevision(approvals.findEvents(id))) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUISITION_STATE_CHANGED", STATE_CHANGED);
        }
        // Task 291: closing while candidates are still in the pipeline needs the caller's confirmation. Asked last,
        // under the requisition lock: a stale page reloads before anyone is asked to confirm, and an application
        // committed while this request waited is counted. A refusal writes nothing, so the same expectedRevision can be
        // sent again.
        if (change.checksActivePipeline() && !request.confirmsActiveCandidates()) {
            ActivePipeline active = pipeline.activePipeline(id);
            if (active.count() > 0) {
                throw new ActivePipelineConflictException(change, active);
            }
        }
        var transition = new RequisitionStatusTransition(id, change, before, change.target(), writer.actorId(), now(),
                request.reason());
        listeners.orderedStream().forEach(listener -> listener.beforeChange(transition));
        approvals.appendStatusChange(id, change, before, transition.actorId(), transition.at(), transition.reason());
        requisition.moveTo(before, change.target());
        requisitions.flush();
        listeners.orderedStream().forEach(listener -> listener.afterChange(transition));
        return RequisitionView.from(requisition);
    }

    /**
     * Task 291: the candidates still in the requisition's pipeline, for the close dialog. Same audience as a change
     * (REQUISITIONS_WRITE_ALL, read access, department read scope), without locks. Any status.
     * Checks in order: token and account (401), REQUISITIONS_WRITE_ALL (403), read access at all (403, before the
     * lookup), the requisition exists (404), department read scope (403; approval participants and assigned recruiters
     * do not count).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivePipeline activePipeline(Jwt jwt, UUID id) {
        var now = clock.instant();
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account actor = auth.requireActiveAccount(jwt);
        Set<String> granted = permissions.forUser(actor.getId());
        if (!granted.contains(WRITE_ALL)) {
            throw new AccessDeniedException("Reading the active pipeline requires REQUISITIONS_WRITE_ALL");
        }
        AccessScope readScope = AccessScope.read(granted, PermissionModule.REQUISITIONS).orDeny();
        RecruitmentRequisition requisition = requisitions.findById(id).orElseThrow(RequisitionStatusService::notFound);
        requisitionService.requireInReadScope(actor.getId(), readScope, requisition.getDepartmentId());
        return pipeline.activePipeline(id);
    }

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

    // May the caller still change statuses? The account and session rows stay locked, but time passes (token or
    // session expiry) and role_permissions can still change, so all three are checked each time, with the permissions
    // read again. The code itself is required, like recruiter assignment: AccessScope.write would also let
    // REQUISITIONS_WRITE_SCOPED through. Returns those permissions.
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
            throw new AccessDeniedException("Changing a requisition's status requires REQUISITIONS_WRITE_ALL");
        }
        return granted;
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response and the listeners see the time a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng.");
    }
}
