package vn.ttcs.recruitment.account.profile;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.PhoneAlreadyUsedException;
import vn.ttcs.recruitment.account.avatar.AvatarRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ProfileService {
    private final AccountRepository accounts;
    private final AvatarRepository avatars;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProfileService(AccountRepository accounts, AvatarRepository avatars, AuthSessionRepository sessions,
                          AuthService auth, PermissionService permissions, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.avatars = avatars;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account account = auth.requireActiveAccount(jwt);
        requirePermission(account.getId(), "SELF_PROFILE_READ");
        return response(account);
    }

    @Transactional
    public ProfileResponse update(Jwt jwt, ProfileUpdateRequest request) {
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }

        // Use the same lock order as password changes: account first, then the caller's session.
        Account account = accounts.findByIdForUpdate(userId)
                .filter(Account::isAccessAllowed).orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession caller = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        Instant now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!caller.getUserId().equals(userId) || !caller.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        requirePermission(userId, "SELF_PROFILE_WRITE");
        // Keeping one's own number or clearing it never conflicts. Any other account with the number, whatever its
        // status, keeps it; the answer does not say which account that is.
        String phone = request.phone();
        if (phone != null && !phone.equals(account.getPhone()) && accounts.existsByPhoneAndIdNot(phone, userId)) {
            throw new PhoneAlreadyUsedException();
        }

        // Identity comes only from the verified JWT; this method never accepts a target user id.
        account.updateProfile(request.fullName(), phone, request.displayTitle());
        // Write now: a save of the same number racing after the check above breaks V38's unique constraint here,
        // where it becomes the 409, instead of at commit outside this method.
        try {
            accounts.flush();
        } catch (DataIntegrityViolationException exception) {
            throw PhoneAlreadyUsedException.translate(exception);
        }
        return response(account);
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    private void requirePermission(UUID userId, String permission) {
        if (!permissions.forUser(userId).contains(permission)) {
            throw new AccessDeniedException("Không có quyền truy cập hồ sơ cá nhân.");
        }
    }

    private ProfileResponse response(Account account) {
        String departmentName = null;
        if (account.getDepartmentId() != null) {
            departmentName = jdbc.queryForObject("SELECT name FROM departments WHERE id = ?",
                    String.class, account.getDepartmentId());
        }
        Instant avatarUpdatedAt = avatars.findUpdatedAt(account.getId()).orElse(null);
        return ProfileResponse.from(account, departmentName, avatarUpdatedAt);
    }
}
