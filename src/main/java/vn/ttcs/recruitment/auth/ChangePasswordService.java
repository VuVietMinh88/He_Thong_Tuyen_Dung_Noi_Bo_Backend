package vn.ttcs.recruitment.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.passwordreset.PasswordResetTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ChangePasswordService {
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public ChangePasswordService(AccountRepository accounts, AuthSessionRepository sessions,
                                 PasswordResetTokenRepository resetTokens, PasswordEncoder passwordEncoder,
                                 Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.resetTokens = resetTokens;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public void changePassword(Jwt jwt, ChangePasswordRequest request) {
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }

        // Login and reset use this account lock, so their password/session changes cannot race ours.
        Account account = accounts.findByIdForUpdate(userId)
                .filter(Account::isEnabled).orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession caller = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        Instant now = clock.instant();
        if (!caller.getUserId().equals(userId) || !caller.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!passwordEncoder.matches(request.currentPassword(), account.getPasswordHash())) {
            throw new IncorrectCurrentPasswordException();
        }

        account.resetPassword(passwordEncoder.encode(request.newPassword()));
        resetTokens.invalidateForUser(userId, now);
        sessions.revokeOtherSessions(userId, sessionId, now);
    }
}
