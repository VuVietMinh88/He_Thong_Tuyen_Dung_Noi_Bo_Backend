package vn.ttcs.recruitment.account;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
public class AccountProvisioningService {
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AccountActivationRepository activationTokens;
    private final AccountInvitationMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final ResetTokenGenerator generator;
    private final PermissionService permissions;
    private final Clock clock;
    private final Duration activationTtl;

    public AccountProvisioningService(AccountRepository accounts, AuthSessionRepository sessions,
                                      AccountActivationRepository activationTokens,
                                      AccountInvitationMailSender mailSender, PasswordEncoder passwordEncoder,
                                      ResetTokenGenerator generator, PermissionService permissions, Clock clock,
                                      @Value("${app.account-activation.ttl}") Duration activationTtl) {
        if (activationTtl.compareTo(Duration.ofHours(1)) < 0
                || activationTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalStateException("Account activation TTL must be between 1 hour and 7 days.");
        }
        this.accounts = accounts;
        this.sessions = sessions;
        this.activationTokens = activationTokens;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.generator = generator;
        this.permissions = permissions;
        this.clock = clock;
        this.activationTtl = activationTtl;
    }

    @Transactional
    public AccountController.CreatedAccount create(Jwt jwt, CreateAccountRequest request) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account actor = accounts.findByIdForUpdate(actorId)
                .filter(Account::isAccessAllowed).orElseThrow(AuthenticationFailureException::sessionInvalid);
        // A request may have waited through an account lock and unlock since the security filter ran.
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
        if (accounts.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }
        // Generated credentials meet the existing password policy; only BCrypt is persisted.
        String temporaryPassword = "A7" + generator.create().substring(0, 22);
        Account account = Account.pendingActivation(request.email(), request.fullName(),
                passwordEncoder.encode(temporaryPassword), request.roles(), now);
        try {
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            // The unique constraint is decisive when different admins race after the precheck.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "user_accounts_email_key".equals(violation.getConstraintName())) {
                    throw new DuplicateEmailException();
                }
            }
            throw exception;
        }
        String activationToken = generator.create();
        activationTokens.insert(account.getId(), generator.hash(activationToken), now, now.plus(activationTtl));
        try {
            mailSender.send(account.getEmail(), temporaryPassword, activationToken, activationTtl);
        } catch (MailException exception) {
            // Do not expose SMTP details, recipient credentials or message bodies in API errors.
            throw new AccountInvitationException();
        }
        return new AccountController.CreatedAccount(account.getId(), account.getEmail(), account.getFullName(),
                account.getRoles(), "PENDING_ACTIVATION");
    }

    @Transactional
    public void activate(String token) {
        String hash = generator.hash(token);
        UUID userId = activationTokens.findUser(hash).orElseThrow(InvalidActivationTokenException::new);
        Account account = accounts.findByIdForUpdate(userId)
                .filter(value -> !value.isEnabled() && !value.isAdministrativelyLocked())
                .orElseThrow(InvalidActivationTokenException::new);
        if (!activationTokens.consume(hash, clock.instant())) {
            throw new InvalidActivationTokenException();
        }
        account.activate();
    }
}
