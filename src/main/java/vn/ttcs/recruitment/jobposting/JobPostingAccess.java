package vn.ttcs.recruitment.jobposting;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Task 303: who may compose and read job postings; task 308: who may review and publish them. Copies of the
 * requisition helpers (RequisitionService), which are private to the requisition package.
 * <p>
 * Every internal posting call needs a JOB_POSTINGS write permission and the matching read permission: ALL (HR_MANAGER,
 * ADMIN) reaches every posting, SCOPED (RECRUITER) only the postings of the requisitions the caller is the primary or a
 * supporting recruiter of (V16). JOB_POSTINGS_READ_ALL alone (HIRING_MANAGER, APPROVER) reads only the public portal.
 * Who created a posting does not matter: after a handover the new recruiter takes its drafts over. Out of scope is
 * the standard 403 FORBIDDEN, not a 404.
 */
@Component
class JobPostingAccess {
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final JobPostingSourceRepository sources;
    private final Clock clock;

    JobPostingAccess(AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                     PermissionService permissions, JobPostingSourceRepository sources, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.sources = sources;
        this.clock = clock;
    }

    // The caller of a write after lockWriter: the account id and the session row this transaction has locked.
    record Writer(UUID actorId, AuthSession session) { }

    // Who is calling and how much of the JOB_POSTINGS module they reach: ALL only when both the write and the read
    // scope are ALL, otherwise SCOPED (never NONE). permissions: every code the caller holds, read together with the
    // scope (after the locks for a write).
    record Caller(UUID id, AccessScope scope, Set<String> permissions) {
        // Task 308: reviewing and publishing are HR decisions (ADMIN, HR_MANAGER in the seed).
        boolean reviewer() {
            return permissions.contains("JOB_POSTINGS_WRITE_ALL");
        }
    }

    Writer lockWriter(Jwt jwt) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the other write services: the actor's account first, then the actor's session.
        // Role, lock and logout changes wait for these locks, so they cannot interleave with this write.
        accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        return new Writer(actorId, session);
    }

    // May the caller still compose postings? Called once the account and session locks are held, and again after
    // every row lock the write waits for (requisition, posting, catalog values). The account and session rows stay
    // locked, so an admin lock, a logout or a role change through the account API waits for this transaction; but
    // time passes (token or session expiry) and role_permissions can still change, so all three are checked every
    // time, with the permissions read again from the database. The read permission is required too, before any
    // lookup: every write answers with the whole posting, and WRITE never implies READ.
    Caller requireComposer(Jwt jwt, Writer writer) {
        var now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!writer.session().getUserId().equals(writer.actorId()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        return caller(writer.actorId(), permissions.forUser(writer.actorId()));
    }

    // Task 308: may the caller still review (approve, ask for changes) and publish postings? The checks of
    // requireComposer, then JOB_POSTINGS_WRITE_ALL itself: the SCOPED write permission of a recruiter composes postings
    // but never decides about them. Called at the same points as requireComposer, so a permission removed or narrowed
    // while the request waited for a lock is refused. A reviewer reaches the postings its read permission reaches
    // (ALL for every holder of JOB_POSTINGS_WRITE_ALL in the seed).
    Caller requireReviewer(Jwt jwt, Writer writer) {
        Caller composer = requireComposer(jwt, writer);
        if (!composer.reviewer()) {
            throw new AccessDeniedException("Only JOB_POSTINGS_WRITE_ALL reviews and publishes job postings");
        }
        AccessScope read = AccessScope.read(composer.permissions(), PermissionModule.JOB_POSTINGS).orDeny();
        return new Caller(composer.id(), read, composer.permissions());
    }

    // Reads take no lock. Internal reads are for composers only: the same write and read permissions as a write.
    Caller requireReader(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        return caller(actor.getId(), permissions.forUser(actor.getId()));
    }

    // ALL reaches every posting. SCOPED only reaches the postings of a requisition the caller is assigned to. A write
    // calls it while it holds the requisition row lock, so a handover committed while it waited is respected.
    void requireInScope(Caller caller, UUID requisitionId) {
        if (caller.scope() == AccessScope.ALL) {
            return;
        }
        if (!sources.isAssigned(requisitionId, caller.id())) {
            throw new AccessDeniedException("The posting's requisition is not assigned to the caller");
        }
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response shows the same time a later read returns.
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // NONE throws AccessDeniedException, which the security layer turns into the standard 403 FORBIDDEN.
    private static Caller caller(UUID id, Set<String> granted) {
        AccessScope write = AccessScope.write(granted, PermissionModule.JOB_POSTINGS).orDeny();
        AccessScope read = AccessScope.read(granted, PermissionModule.JOB_POSTINGS).orDeny();
        AccessScope scope = write == AccessScope.ALL && read == AccessScope.ALL ? AccessScope.ALL : AccessScope.SCOPED;
        return new Caller(id, scope, granted);
    }

    private static void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }
}
