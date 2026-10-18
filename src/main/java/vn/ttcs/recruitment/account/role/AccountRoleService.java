package vn.ttcs.recruitment.account.role;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountNotFoundException;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountRoleService {
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final Clock clock;

    public AccountRoleService(AccountRepository accounts, AuthSessionRepository sessions,
                              PermissionService permissions, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional
    public AccountRolesResponse assign(Jwt jwt, UUID id, Role role) {
        Account target = requireTargetForAdministration(jwt, id);
        target.addRole(role);
        return AccountRolesResponse.from(target);
    }

    @Transactional
    public AccountRolesResponse revoke(Jwt jwt, UUID id, Role role) {
        Account target = requireTargetForAdministration(jwt, id);
        if (role == Role.ADMIN && id.equals(UUID.fromString(jwt.getSubject()))) {
            throw new SelfAdminRevocationException();
        }
        target.removeRole(role);
        return AccountRolesResponse.from(target);
    }

    // The request is the complete set: roles missing from it are revoked and new ones granted in one transaction,
    // so the account never ends up half changed. expectedRoles is compared under the target row lock, so a set built
    // from an outdated view cannot undo a change another administrator committed meanwhile. The caller holds ADMIN
    // (checked after the locks), so a set without ADMIN for the caller's own account would revoke it. Both refusals
    // happen before anything is written.
    @Transactional
    public AccountRolesResponse replace(Jwt jwt, UUID id, Set<Role> roles, Set<Role> expectedRoles) {
        Account target = requireTargetForAdministration(jwt, id);
        if (!target.getRoles().equals(expectedRoles)) {
            throw new AccountRolesChangedException();
        }
        if (!roles.contains(Role.ADMIN) && id.equals(UUID.fromString(jwt.getSubject()))) {
            throw new SelfAdminRevocationException();
        }
        target.replaceRoles(roles);
        return AccountRolesResponse.from(target);
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

        // Serialize changes to both users in UUID order, then lock the caller's session.
        var ids = new HashSet<UUID>();
        ids.add(actorId);
        ids.add(id);
        var locked = accounts.findAllByIdForUpdate(ids);
        Account actor = locked.stream()
                .filter(account -> account.getId().equals(actorId) && account.isAccessAllowed())
                .findFirst().orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var now = clock.instant();
        if (!session.getUserId().equals(actorId) || !session.isActive(now)
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // The security filter ran before these locks; another request may have removed ADMIN meanwhile.
        if (!actor.getRoles().contains(Role.ADMIN)
                || !permissions.forUser(actorId).contains("USER_ADMIN_WRITE_ALL")) {
            throw new AccessDeniedException("Account role administration requires ADMIN");
        }
        return locked.stream().filter(account -> account.getId().equals(id))
                .findFirst().orElseThrow(AccountNotFoundException::new);
    }
}
