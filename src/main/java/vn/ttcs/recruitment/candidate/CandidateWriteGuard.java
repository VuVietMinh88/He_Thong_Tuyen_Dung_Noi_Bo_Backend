package vn.ttcs.recruitment.candidate;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
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
 */
@Component
public class CandidateWriteGuard {
    // lock_not_available (lock_timeout), query_canceled (statement_timeout), deadlock_detected, serialization_failure.
    private static final Set<String> BUSY_STATES = Set.of("55P03", "57014", "40P01", "40001");
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CandidateWriteGuard(AccountRepository accounts, AuthSessionRepository sessions,
                               PermissionService permissions, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
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
