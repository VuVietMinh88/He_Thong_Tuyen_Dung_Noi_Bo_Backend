package vn.ttcs.recruitment.auth.passwordreset;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSessionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class PasswordResetService {

    private static final Duration EMAIL_COOLDOWN = Duration.ofMinutes(1);

    private final AccountRepository accounts;
    private final PasswordResetTokenRepository resetTokens;
    private final AuthSessionRepository sessions;
    private final ResetTokenGenerator generator;
    private final PasswordResetMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PasswordResetService(AccountRepository accounts, PasswordResetTokenRepository resetTokens,
                                AuthSessionRepository sessions, ResetTokenGenerator generator,
                                PasswordResetMailSender mailSender, PasswordEncoder passwordEncoder, Clock clock) {
        this.accounts = accounts;
        this.resetTokens = resetTokens;
        this.sessions = sessions;
        this.generator = generator;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public void sendResetEmail(String email) {
        Account account = accounts.findByEmailForUpdate(email).orElse(null);
        if (account == null || !account.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        // Serialize by account so parallel requests cannot bypass the per-account email cooldown.
        if (resetTokens.existsByUserIdAndCreatedAtAfter(account.getId(), now.minus(EMAIL_COOLDOWN))) {
            return;
        }
        String token = generator.create();
        resetTokens.saveAndFlush(new PasswordResetToken(account.getId(), generator.hash(token), now));
        // A failed SMTP send rolls back this token. No raw token is stored or returned by the API.
        mailSender.send(account.getEmail(), token);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String hash = generator.hash(request.token());
        PasswordResetToken resetToken = resetTokens.findByTokenHash(hash)
                .orElseThrow(InvalidResetTokenException::new);
        // Login uses the same account lock. New sessions cannot slip past password change/revocation.
        Account account = accounts.findByIdForUpdate(resetToken.getUserId())
                .filter(Account::isEnabled).orElseThrow(InvalidResetTokenException::new);
        Instant now = clock.instant();
        if (resetTokens.consume(hash, now) != 1) {
            throw new InvalidResetTokenException();
        }
        account.resetPassword(passwordEncoder.encode(request.newPassword()));
        resetTokens.invalidateForUser(account.getId(), now);
        sessions.revokeForUser(account.getId(), now);
    }
}
