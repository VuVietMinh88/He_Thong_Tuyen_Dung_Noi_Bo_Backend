package vn.ttcs.recruitment.pipeline;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.pipeline.ApplicationStageChangeRepository.RejectionReason;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 380 (story S5-03, "Kiểm tra trạng thái hiện tại, giai đoạn đích và quyền theo hồ sơ ứng tuyển"): a staff member
 * moves one application to another stage. The board's drag and drop (task 375) calls this same service.
 * <p>
 * Who: a CANDIDATES write permission and a CANDIDATES read permission (the answer shows the application; a write
 * never implies a read, V3), both read again from the database. ALL (HR_MANAGER, ADMIN) writes every application;
 * SCOPED (RECRUITER) only those of a requisition it is now the PRIMARY or a SUPPORTING recruiter of
 * (RecruiterAssignmentAccess.isAssigned, checked under the requisition lock as that class asks of candidate writes).
 * Managing the department gives read access only (CandidateAccess.READABLE): a hiring manager reads, never moves.
 * <p>
 * Order: @Valid (400, before any database access); task 381: a rejection without rejectionReasonId (400
 * REJECTION_REASON_REQUIRED) or a reason with another target (400 REJECTION_REASON_NOT_ALLOWED), also before any
 * database access; then one REQUIRES_NEW READ COMMITTED transaction of at most 5 s with lock_timeout 2 s and
 * statement_timeout 3 s, in the house's lock order:
 * <ol>
 * <li>the caller's account, then its session, FOR UPDATE; then the token, the session and both permissions (401,
 * 403 before any lookup);</li>
 * <li>the application's requisition, read without a lock (unknown application: 404 APPLICATION_NOT_FOUND), then the
 * requisition FOR NO KEY UPDATE, the lock of every closure, team change and withdrawal of that requisition; the caller
 * again, then the scope: 404 when the application is linked to no candidate profile (only after SQL edits, like the CV
 * API), 403 when it is outside the caller's read or write scope;</li>
 * <li>the application FOR NO KEY UPDATE and its stage as locked; the caller and the scope again. After every lock wait
 * the scope is evaluated with the permissions read after that wait: a grant narrowed from ALL to SCOPED meanwhile is
 * a 403 for an application outside the narrower scope;</li>
 * <li>409 APPLICATION_STAGE_CHANGED when that stage is not expectedCurrentStage (another change or the candidate's
 * withdrawal came first: reload), then the rule (ApplicationStageTransitions.decide): 409
 * APPLICATION_STAGE_TRANSITION_NOT_ALLOWED, or for SCREENING → OFFER 409 APPLICATION_INTERVIEW_REQUIRED (the caller
 * is no HR_MANAGER: the role of the locked account, so ADMIN or CANDIDATES_WRITE_ALL alone is not enough) and 409
 * APPLICATION_INTERVIEW_SKIP_UNCONFIRMED (an HR_MANAGER without confirmSkipInterview: true);</li>
 * <li>task 381, a rejection: its REJECTION_REASON value FOR SHARE, the caller and the scope again, then 400
 * INVALID_REJECTION_REASON (unknown, or a value of another catalog) or 400 REJECTION_REASON_INACTIVE;</li>
 * <li>the clock, read after the last lock; the stage, only from the locked stage, and one STAFF history row (V34) in
 * this transaction, with the rejection's reason id and current name and whether the interview was skipped (V40), and
 * (task 382) the caller's optional note. That row is the history of task 382: before and after, who, when, why (the
 * catalog reason and the note) and the HR exception, never edited afterwards (V34's trigger).</li>
 * </ol>
 * A refusal writes nothing. A lock or statement timeout answers 503 APPLICATION_STAGE_BUSY, and then nothing of the
 * change is kept. A stage change and a withdrawal of one application run one after the other on the requisition lock;
 * the second sees the first's stage.
 */
@Service
public class ApplicationStageChangeService {
    // lock_not_available, query_canceled (statement_timeout) and deadlock_detected.
    private static final Set<String> BUSY_STATES = Set.of("55P03", "57014", "40P01");

    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final CandidateAccess candidates;
    private final RecruiterAssignmentAccess assignments;
    private final ApplicationStageChangeRepository stages;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ApplicationStageChangeService(AccountRepository accounts, AuthSessionRepository sessions,
                                  PermissionService permissions, CandidateAccess candidates,
                                  RecruiterAssignmentAccess assignments, ApplicationStageChangeRepository stages,
                                  JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.candidates = candidates;
        this.assignments = assignments;
        this.stages = stages;
        this.jdbc = jdbc;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        // Commits on its own and reads the newest committed row version under each lock (ApplicationWithdrawalAccess's
        // contract for every write that changes a stage).
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    // Call only after @Valid has accepted the request: both stages are then stage names.
    public ApplicationStageChangeView change(Jwt jwt, UUID applicationId, ApplicationStageChangeRequest request) {
        requireReasonMatchesTarget(request);
        try {
            return Objects.requireNonNull(transaction.execute(status -> changeLocked(jwt, applicationId, request)));
        } catch (RuntimeException exception) {
            if (busy(exception)) {
                // The transaction kept nothing, and the caller may simply send the same request again.
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_STAGE_BUSY",
                        "Hồ sơ đang được xử lý ở một thao tác khác. Vui lòng thử lại sau ít phút.");
            }
            throw exception;
        }
    }

    // A lock or statement timeout (lock_timeout, statement_timeout, the transaction's 5 s) or a deadlock. JdbcTemplate's
    // default translator (Spring 6) leaves PostgreSQL's 55P03 and 57014 uncategorized, so the SQLState is read from the
    // cause chain, as RecruitmentCatalogService does for its constraint names.
    private static boolean busy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                    || (cause instanceof SQLException sql && BUSY_STATES.contains(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private ApplicationStageChangeView changeLocked(Jwt jwt, UUID applicationId, ApplicationStageChangeRequest request) {
        // These limits apply only to this transaction.
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        // 1. Account, then session, then the caller checked.
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        // 2.-3. The requisition first, as every closure, team change and withdrawal does; then the caller and the scope
        // of this application, under the requisition lock (a handover waits for it).
        UUID requisitionId = stages.requisitionOf(applicationId).orElseThrow(ApplicationStageChangeService::notFound);
        stages.lockRequisition(requisitionId);
        requireStillInScope(jwt, writer, requisitionId, applicationId);
        // 4. The application as locked; then the caller and the scope again, with the permissions read after the wait.
        ApplicationStage current = stages.lockApplication(applicationId)
                .orElseThrow(ApplicationStageChangeService::notFound);
        requireStillInScope(jwt, writer, requisitionId, applicationId);
        // 5. The caller's view, then the rule.
        ApplicationStage target = request.target();
        if (current != request.expected()) {
            throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_STAGE_CHANGED",
                    "Hồ sơ vừa được chuyển sang giai đoạn khác hoặc ứng viên vừa rút hồ sơ. Vui lòng tải lại trước "
                            + "khi thao tác.");
        }
        boolean interviewSkipped = switch (ApplicationStageTransitions.decide(current, target, writer.hrManager(),
                request.confirmsInterviewSkip())) {
            case ALLOWED -> false;
            case INTERVIEW_SKIPPED -> true;
            case INTERVIEW_REQUIRED -> throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_INTERVIEW_REQUIRED",
                    "Hồ sơ phải qua giai đoạn Phỏng vấn trước khi Đề xuất offer. Chỉ Trưởng phòng Nhân sự được xác "
                            + "nhận bỏ qua giai đoạn này.");
            case INTERVIEW_SKIP_UNCONFIRMED -> throw new ApiException(HttpStatus.CONFLICT,
                    "APPLICATION_INTERVIEW_SKIP_UNCONFIRMED",
                    "Vui lòng xác nhận bỏ qua giai đoạn Phỏng vấn để chuyển thẳng sang Đề xuất offer.");
            case NOT_ALLOWED -> throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_STAGE_TRANSITION_NOT_ALLOWED",
                    "Không thể chuyển hồ sơ từ giai đoạn " + current.displayName() + " sang giai đoạn "
                            + target.displayName() + ".");
        };
        // 6. Task 381: a rejection's reason, locked FOR SHARE (HR editing the value makes this wait), then the caller and
        // the scope again before anything else is decided, including whether the value exists.
        RejectionReason reason = null;
        if (target == ApplicationStage.REJECTED) {
            Optional<RejectionReason> locked = stages.lockRejectionReason(request.rejectionReasonId());
            requireStillInScope(jwt, writer, requisitionId, applicationId);
            reason = locked.orElseThrow(() -> reasonError("INVALID_REJECTION_REASON", "Lý do loại không tồn tại."));
            if (!reason.active()) {
                throw reasonError("REJECTION_REASON_INACTIVE", "Lý do loại đã ngừng sử dụng, vui lòng chọn lý do khác.");
            }
        }
        // 7. After the last lock wait: the change happens when its locks were granted.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        stages.move(applicationId, current, target);
        stages.record(applicationId, current, target, writer.id(), now, request.note(), reason, interviewSkipped);
        return ApplicationStageChangeView.of(applicationId, current, target, now, writer.id(), writer.fullName(),
                request.note(), reason, interviewSkipped);
    }

    // Task 381: a rejection names its reason, and only a rejection does. Checked before any lock, like the reason of a
    // requisition's status change.
    private static void requireReasonMatchesTarget(ApplicationStageChangeRequest request) {
        if (request.target() == ApplicationStage.REJECTED && request.rejectionReasonId() == null) {
            throw reasonError("REJECTION_REASON_REQUIRED", "Vui lòng chọn lý do loại.");
        }
        if (request.target() != ApplicationStage.REJECTED && request.rejectionReasonId() != null) {
            throw reasonError("REJECTION_REASON_NOT_ALLOWED", "Chỉ chọn lý do loại khi chuyển hồ sơ sang Loại.");
        }
    }

    private static ApiException reasonError(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of("rejectionReasonId", message));
    }

    // The caller of a write once lockWriter holds the account and the session. hrManager is read with the locked
    // account: a role change locks that account too, so it waits for this write.
    private record Writer(UUID id, String fullName, boolean hrManager, AuthSession session) {
        @Override public String toString() { return "Writer[" + id + ", name redacted]"; }
    }

    // How far the caller's CANDIDATES permissions reach, read again from the database; never NONE.
    private record Scopes(AccessScope write, AccessScope read) { }

    private Writer lockWriter(Jwt jwt) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the other write services: the caller's account first, then its session. Role, lock and
        // logout changes of the caller wait for these locks.
        Account account = accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        return new Writer(actorId, account.getFullName(), account.getRoles().contains(Role.HR_MANAGER), session);
    }

    // The account and the session stay locked, but time passes (token or session expiry) and role_permissions can
    // still change, so all three are checked after every wait, the permissions read again.
    private Scopes requireStillWriter(Jwt jwt, Writer writer) {
        Instant now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || !writer.session().getUserId().equals(writer.id()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Set<String> granted = permissions.forUser(writer.id());
        return new Scopes(AccessScope.write(granted, PermissionModule.CANDIDATES).orDeny(),
                AccessScope.read(granted, PermissionModule.CANDIDATES).orDeny());
    }

    // After every lock wait: the caller (requireStillWriter), then the record scope evaluated with the scopes it has
    // just read. A grant changed from ALL to SCOPED while the request waited keeps a non-empty scope, so checking that
    // a permission still exists is not enough; the application must still be inside the narrower scope.
    private void requireStillInScope(Jwt jwt, Writer writer, UUID requisitionId, UUID applicationId) {
        requireInScope(writer, requireStillWriter(jwt, writer), requisitionId, applicationId);
    }

    // Read: CandidateAccess.findApplication, the READABLE rule of every candidate read. Write: ALL, or the assigned
    // recruiter (a department manager reads but does not write). Both are required.
    private void requireInScope(Writer writer, Scopes scopes, UUID requisitionId, UUID applicationId) {
        ApplicationAccess found = candidates.findApplication(new CandidateReader(writer.id(), scopes.read()),
                applicationId).orElseThrow(ApplicationStageChangeService::notFound);
        boolean writable = scopes.write() == AccessScope.ALL || assignments.isAssigned(requisitionId, writer.id());
        if (!found.readable() || !writable) {
            throw new AccessDeniedException("The application is outside the caller's candidate scope");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển.");
    }
}
