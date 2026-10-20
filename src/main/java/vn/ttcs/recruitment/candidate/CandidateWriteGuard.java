package vn.ttcs.recruitment.candidate;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The caller checks of the candidate-extension writes (saved filters, manual intake, fit criteria, talent pool), the
 * pattern of ScreeningService and ApplicationReferralWriter in one place. lockWriter takes the caller's account, then
 * its session, FOR UPDATE in the caller's READ COMMITTED transaction, so an administrator lock, a logout or a role
 * change waits for the write. freshGrants must be called again after every later lock wait: the token's expiry and the
 * session's are times and role_permissions is not locked, so the grants are read from the database each time, never
 * taken from the JWT.
 * <p>
 * Task 413: writes about one application lock its requisition, then the application, FOR NO KEY UPDATE
 * (lockRequisitionOf, lockApplication) and call requireWritable after every lock wait.
 */
@Component
public class CandidateWriteGuard {
    // lock_not_available (lock_timeout), query_canceled (statement_timeout), deadlock_detected, serialization_failure.
    private static final Set<String> BUSY_STATES = Set.of("55P03", "57014", "40P01", "40001");
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final CandidateAccess candidates;
    private final RecruiterAssignmentAccess assignments;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CandidateWriteGuard(AccountRepository accounts, AuthSessionRepository sessions,
                               PermissionService permissions, CandidateAccess candidates,
                               RecruiterAssignmentAccess assignments, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.candidates = candidates;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Writer(UUID id, String fullName, Set<Role> roles, AuthSession session) {
        @Override public String toString() { return "Writer[" + id + ", name redacted]"; }
    }

    // The limits apply only to the caller's transaction. 401 SESSION_INVALID for an unknown, locked or disabled account
    // or an unknown, revoked or expired session.
    @Transactional(propagation = Propagation.MANDATORY)
    public Writer lockWriter(Jwt jwt) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account account = accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        Writer writer = new Writer(actorId, account.getFullName(), Set.copyOf(account.getRoles()), session);
        freshGrants(jwt, writer);
        return writer;
    }

    // The token and the session checked again, then the permission codes as stored now.
    public Set<String> freshGrants(Jwt jwt, Writer writer) {
        Instant now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || !writer.session().getUserId().equals(writer.id()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        return permissions.forUser(writer.id());
    }

    public record Scopes(AccessScope read, AccessScope write) {
        public CandidateReader reader(UUID actorId) { return new CandidateReader(actorId, read); }
    }

    // The CANDIDATES read and write scopes as stored now (403 without either), after the token and session checks.
    public Scopes freshScopes(Jwt jwt, Writer writer) {
        Set<String> grants = freshGrants(jwt, writer);
        return new Scopes(AccessScope.read(grants, PermissionModule.CANDIDATES).orDeny(),
                AccessScope.write(grants, PermissionModule.CANDIDATES).orDeny());
    }

    // The application's requisition (it never changes), locked FOR NO KEY UPDATE first, as every write about an
    // application does; 404 APPLICATION_NOT_FOUND for an unknown application.
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID lockRequisitionOf(UUID applicationId) {
        UUID requisition = jdbc.query("SELECT requisition_id FROM job_applications WHERE id = ?",
                (row, number) -> row.getObject(1, UUID.class), applicationId).stream().findFirst()
                .orElseThrow(CandidateWriteGuard::applicationNotFound);
        if (jdbc.queryForList("SELECT id FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE", UUID.class,
                requisition).isEmpty()) {
            throw applicationNotFound();
        }
        return requisition;
    }

    // The application row FOR NO KEY UPDATE, after its requisition; its stage as stored now.
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationStage lockApplication(UUID applicationId) {
        return jdbc.query("SELECT stage FROM job_applications WHERE id = ? FOR NO KEY UPDATE",
                (row, number) -> ApplicationStage.valueOf(row.getString(1)), applicationId).stream().findFirst()
                .orElseThrow(CandidateWriteGuard::applicationNotFound);
    }

    // Call after every lock wait: the scopes read again, then the record scope with them. The application must be
    // READABLE, and a SCOPED writer must be one of the requisition's recruiters now (ScreeningService's rule).
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplicationAccess requireWritable(Jwt jwt, Writer writer, UUID requisitionId, UUID applicationId) {
        Scopes scopes = freshScopes(jwt, writer);
        ApplicationAccess found = candidates.findApplication(scopes.reader(writer.id()), applicationId)
                .orElseThrow(CandidateWriteGuard::applicationNotFound);
        if (!found.readable() || scopes.write() != AccessScope.ALL && !assignments.isAssigned(requisitionId, writer.id())) {
            throw new AccessDeniedException("The application is outside the caller's candidate write scope");
        }
        return found;
    }

    public static ApiException applicationNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển.");
    }

    // A lock or statement timeout, a deadlock or a serialization failure, read from the cause chain because Spring's
    // default translator leaves PostgreSQL's 55P03 and 57014 uncategorized. Callers answer 503 and keep nothing.
    public static boolean busy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                    || cause instanceof SQLException sql && BUSY_STATES.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
