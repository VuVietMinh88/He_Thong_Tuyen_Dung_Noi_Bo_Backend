package vn.ttcs.recruitment.account.lock;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountNotFoundException;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.AccountSearchRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.auth.passwordreset.PasswordResetTokenRepository;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.HashSet;
import java.util.UUID;

@Service
public class AccountLockService {
    private static final String HANDOVER_WARNING =
            "Vui lòng rà soát và bàn giao các vị trí tuyển dụng do tài khoản này phụ trách (nếu có).";

    private final AccountRepository accounts;
    private final AccountSearchRepository search;
    private final AuthSessionRepository sessions;
    private final PasswordResetTokenRepository resetTokens;
    private final PermissionService permissions;
    private final Clock clock;

    public AccountLockService(AccountRepository accounts, AccountSearchRepository search,
                              AuthSessionRepository sessions, PasswordResetTokenRepository resetTokens,
                              PermissionService permissions, Clock clock) {
        this.accounts = accounts;
        this.search = search;
        this.sessions = sessions;
        this.resetTokens = resetTokens;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional
    public AccountLockResponse lock(Jwt jwt, UUID id, AccountLockRequest request) {
        Account target = requireTargetForAdministration(jwt, id);
        UUID actorId = UUID.fromString(jwt.getSubject());
        if (actorId.equals(id)) {
            throw new SelfAccountLockException();
        }
        var now = clock.instant();
        target.lockByAdministrator(request.reason(), actorId, now);
        // Login/reset also lock the account first, so they cannot create a session past this revocation.
        sessions.revokeForUser(id, now);
        resetTokens.invalidateForUser(id, now);
        return response(target);
    }

    @Transactional
    public AccountLockResponse unlock(Jwt jwt, UUID id) {
        Account target = requireTargetForAdministration(jwt, id);
        target.unlockByAdministrator();
        return response(target);
    }

    private AccountLockResponse response(Account target) {
        // JDBC status calculation must see the JPA update from this transaction.
        accounts.flush();
        var view = search.findById(target.getId(), clock.instant()).orElseThrow(AccountNotFoundException::new);
        return new AccountLockResponse(target.getId(), view.status(), target.getAdminLockReason(),
                target.getAdminLockedAt(), target.getAdminLockedBy(),
                target.isAdministrativelyLocked() ? HANDOVER_WARNING : null);
    }

    private Account requireTargetForAdministration(Jwt jwt, UUID id) {
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
        var locked = accounts.findAllByIdForUpdate(ids);
        Account actor = locked.stream().filter(account -> account.getId().equals(actorId) && account.isAccessAllowed())
                .findFirst().orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var now = clock.instant();
        if (!session.getUserId().equals(actorId) || !session.isActive(now)
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!actor.getRoles().contains(Role.ADMIN)
                || !permissions.forUser(actorId).contains("USER_ADMIN_WRITE_ALL")) {
            throw new AccessDeniedException("Account locking requires ADMIN");
        }
        return locked.stream().filter(account -> account.getId().equals(id))
                .findFirst().orElseThrow(AccountNotFoundException::new);
    }
}
