package vn.ttcs.recruitment.messaging;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Who calls a messaging API. Reads take the active account of the token (401 otherwise). Writes follow the house lock
 * order: the caller's account, then its session, both FOR UPDATE in the write transaction, so a role change, a lock or
 * a logout of the caller waits for the write; after every later lock wait the write calls requireFresh and reads the
 * permissions again (a grant removed meanwhile is a 403 at once). Permissions are always read from the database,
 * never from the token.
 */
@Component
public class MessagingActors {
    private final AuthService auth;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final Clock clock;

    public MessagingActors(AuthService auth, AccountRepository accounts, AuthSessionRepository sessions,
                           PermissionService permissions, Clock clock) {
        this.auth = auth;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.clock = clock;
    }

    public record Actor(UUID id, String fullName, Set<Role> roles, AuthSession session) {
        @Override public String toString() { return "MessagingActor[" + id + ", name redacted]"; }
    }

    /** The caller of a read: an unexpired token of an active session and account. */
    public Account reader(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        return auth.requireActiveAccount(jwt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Actor lockWriter(Jwt jwt) {
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
        Actor actor = new Actor(actorId, account.getFullName(), Set.copyOf(account.getRoles()), session);
        requireFresh(jwt, actor);
        return actor;
    }

    /** Time passes while a write waits for locks: the token and the session must still be valid afterwards. */
    public void requireFresh(Jwt jwt, Actor actor) {
        Instant now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || !actor.session().getUserId().equals(actor.id()) || !actor.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    public Set<String> grants(UUID accountId) {
        return permissions.forUser(accountId);
    }

    /** 403 (the standard forbidden response) unless the account now holds every one of these permission codes. */
    public Set<String> requireAll(UUID accountId, String... codes) {
        Set<String> granted = grants(accountId);
        for (String code : codes) {
            if (!granted.contains(code)) throw new AccessDeniedException("Missing permission " + code);
        }
        return granted;
    }
}
