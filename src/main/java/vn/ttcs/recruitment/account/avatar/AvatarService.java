package vn.ttcs.recruitment.account.avatar;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Avatar of the signed-in user. Like the profile API, the owner always comes from the verified JWT: there is no way
 * to upload or delete another person's avatar. Reading a colleague's avatar only needs SELF_PROFILE_READ, which every
 * internal role has, so people can recognise each other on shared screens such as the interview calendar.
 */
@Service
public class AvatarService {
    private final AvatarImageValidator validator;
    private final AvatarImageProcessor processor;
    private final AvatarRepository avatars;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AvatarService(AvatarImageValidator validator, AvatarImageProcessor processor, AvatarRepository avatars,
                         AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                         PermissionService permissions, PlatformTransactionManager transactionManager, Clock clock) {
        this.validator = validator;
        this.processor = processor;
        this.avatars = avatars;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Replaces the caller's avatar. Checking and resizing the picture is the slow part, so it runs before the
     * transaction starts: the database locks below are then held only for the short write.
     */
    public AvatarView replace(Jwt jwt, MultipartFile file) {
        if (file == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_FILE_REQUIRED",
                    "Vui lòng chọn ảnh đại diện và gửi trong trường file.");
        }
        ProcessedAvatar avatar = processor.process(validator.validate(readBytes(file)));
        return transactions.execute(status -> {
            UUID userId = lockCallerForWrite(jwt);
            Instant now = now();
            avatars.save(userId, avatar, now);
            return AvatarView.of(userId, avatar, now);
        });
    }

    /** Removes the caller's avatar. Deleting when there is none also succeeds, so a repeated click is harmless. */
    @Transactional
    public void delete(Jwt jwt) {
        avatars.delete(lockCallerForWrite(jwt));
    }

    @Transactional(readOnly = true)
    public AvatarFile getOwn(Jwt jwt, AvatarSize size) {
        UUID callerId = requireReader(jwt);
        return avatars.find(callerId, size).orElseThrow(AvatarService::notFound);
    }

    /** A colleague's avatar. An unknown account and an account without an avatar both answer 404. */
    @Transactional(readOnly = true)
    public AvatarFile getForAccount(Jwt jwt, UUID accountId, AvatarSize size) {
        requireReader(jwt);
        return avatars.find(accountId, size).orElseThrow(AvatarService::notFound);
    }

    private UUID requireReader(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account caller = auth.requireActiveAccount(jwt);
        requirePermission(caller.getId(), "SELF_PROFILE_READ");
        return caller.getId();
    }

    private UUID lockCallerForWrite(Jwt jwt) {
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the profile and password services: the caller's account first, then its session.
        // An administrator lock, a logout or a role change waits for these locks, so it cannot interleave with
        // this write.
        accounts.findByIdForUpdate(userId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);

        // The request may have waited for those locks. Check the token, session and permission again now.
        Instant now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!session.getUserId().equals(userId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        requirePermission(userId, "SELF_PROFILE_WRITE");
        return userId;
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    private void requirePermission(UUID userId, String permission) {
        if (!permissions.forUser(userId).contains(permission)) {
            throw new AccessDeniedException("Avatar access requires " + permission);
        }
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the upload response shows the same time a later GET reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the uploaded avatar", exception);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "AVATAR_NOT_FOUND", "Chưa có ảnh đại diện.");
    }
}
