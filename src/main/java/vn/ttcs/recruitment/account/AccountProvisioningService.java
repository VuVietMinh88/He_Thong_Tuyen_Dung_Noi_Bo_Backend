package vn.ttcs.recruitment.account;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration activationTtl;

    public AccountProvisioningService(AccountRepository accounts, AuthSessionRepository sessions,
                                      AccountActivationRepository activationTokens,
                                      AccountInvitationMailSender mailSender, PasswordEncoder passwordEncoder,
                                      ResetTokenGenerator generator, PermissionService permissions,
                                      JdbcTemplate jdbc, Clock clock,
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
        this.jdbc = jdbc;
        this.clock = clock;
        this.activationTtl = activationTtl;
    }

    @Transactional
    public AccountController.CreatedAccount create(Jwt jwt, CreateAccountRequest request) {
        return create(jwt, request, NewAccountProfile.NONE);
    }

    /**
     * Creates one account like POST /accounts and also saves its department, phone and display title. The staff
     * import calls this once per row from code without a transaction, so every row gets its own transaction: a row
     * that fails here (email taken or department stopped in the meantime, invitation not sent) rolls back alone.
     * Every check runs before the invitation email is sent, so a row refused by a check sends no email.
     */
    @Transactional
    public AccountController.CreatedAccount create(Jwt jwt, CreateAccountRequest request, NewAccountProfile profile) {
        lockCallerAllowedToCreate(jwt);
        var now = clock.instant();
        UUID departmentId = profile.departmentCode() == null ? null : activeDepartmentId(profile.departmentCode());
        if (accounts.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }
        // Generated credentials meet the existing password policy; only BCrypt is persisted.
        String temporaryPassword = "A7" + generator.create().substring(0, 22);
        Account account = Account.pendingActivation(request.email(), request.fullName(),
                passwordEncoder.encode(temporaryPassword), request.roles(), now);
        account.updateProfile(request.fullName(), profile.phone(), profile.displayTitle());
        account.assignDepartment(departmentId);
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
            // Do not expose SMTP details, recipient credentials or message bodies in API errors. Only whether the
            // server refused this address is kept, so the staff import can go on with the next rows.
            throw new AccountInvitationException(AccountInvitationMailSender.refusedRecipient(exception));
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

    /**
     * Checks that the caller may create accounts right now, with the same rule and locks as {@link #create}. The
     * staff import calls this before it reads an uploaded file, so someone without the right learns nothing from
     * the file, not even which of its emails already have accounts. Called from code without a transaction (as the
     * import does), the check runs in its own short transaction and the locks are released when it returns.
     */
    @Transactional
    public void requireCreateAccess(Jwt jwt) {
        lockCallerAllowedToCreate(jwt);
    }

    // Creating accounts needs an active account and session, an unexpired token, the ADMIN role and
    // USER_ADMIN_WRITE_ALL. The caller's account and then the caller's session are locked first (the same order as
    // the other account services), so a role removal, account lock or logout that is being saved right now is
    // waited for, and everything is read again after the wait instead of trusting the security filter alone.
    private void lockCallerAllowedToCreate(Jwt jwt) {
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
    }

    // Same rule as PUT /accounts/{id}: nobody new joins a department that is no longer used. Locking the row
    // (FOR SHARE, after the actor and session as everywhere else) keeps it active and keeps its code until this
    // account is saved. A department deactivated or renamed just before is not found.
    private UUID activeDepartmentId(String code) {
        return jdbc.query("SELECT id FROM departments WHERE code = ? AND active FOR SHARE",
                        (row, number) -> row.getObject("id", UUID.class), code)
                .stream().findFirst().orElseThrow(InvalidDepartmentException::new);
    }
}
