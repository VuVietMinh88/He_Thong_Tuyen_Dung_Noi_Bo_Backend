package vn.ttcs.recruitment.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccountManagementService {
    private final AccountRepository accounts;
    private final AccountSearchRepository search;
    private final AuthService auth;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountManagementService(AccountRepository accounts, AccountSearchRepository search, AuthService auth,
                                    AuthSessionRepository sessions, PermissionService permissions,
                                    JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.search = search;
        this.auth = auth;
        this.sessions = sessions;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountPage list(Jwt jwt, String query, Role role, AccountStatus status, UUID departmentId,
                            int page, int size) {
        requireReadPermission(jwt);
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 255)) {
            throw new InvalidAccountQueryException();
        }
        return search.search(query, role, status, departmentId, page, size, clock.instant());
    }

    @Transactional(readOnly = true)
    public AccountView get(Jwt jwt, UUID id) {
        requireReadPermission(jwt);
        return search.findById(id, clock.instant()).orElseThrow(AccountNotFoundException::new);
    }

    @Transactional
    public AccountView update(Jwt jwt, UUID id, AccountUpdateRequest request) {
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
        if (!actor.getRoles().contains(Role.ADMIN)
                || !permissions.forUser(actorId).contains("USER_ADMIN_WRITE_ALL")) {
            throw new AccessDeniedException("Account administration requires ADMIN");
        }
        Account target = locked.stream().filter(account -> account.getId().equals(id))
                .findFirst().orElseThrow(AccountNotFoundException::new);
        if (request.departmentId() != null) {
            var department = jdbc.query("SELECT active FROM departments WHERE id = ? FOR SHARE",
                    (row, number) -> row.getBoolean("active"), request.departmentId());
            if (department.isEmpty() || (!department.getFirst()
                    && !Objects.equals(target.getDepartmentId(), request.departmentId()))) {
                throw new InvalidDepartmentException();
            }
        }
        target.updateProfile(request.fullName(), request.phone(), request.displayTitle());
        target.assignDepartment(request.departmentId());
        // JDBC reads do not trigger JPA auto-flush; persist first so the returned DTO reflects this update.
        accounts.flush();
        return search.findById(id, now).orElseThrow(AccountNotFoundException::new);
    }

    private void requireReadPermission(Jwt jwt) {
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("USER_ADMIN_READ_ALL")) {
            throw new AccessDeniedException("Account list requires USER_ADMIN_READ_ALL");
        }
    }
}
