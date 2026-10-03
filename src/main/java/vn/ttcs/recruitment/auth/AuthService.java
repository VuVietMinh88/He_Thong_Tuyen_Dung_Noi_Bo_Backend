package vn.ttcs.recruitment.auth;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(AccountRepository accounts, AuthSessionRepository sessions,
                       PasswordEncoder passwordEncoder, TokenService tokens, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // A rejected login must still commit its failed-attempt counter and lock timestamp.
    @Transactional(noRollbackFor = AuthenticationFailureException.class)
    public TokenResponse login(LoginRequest request) {
        Account account = accounts.findByEmailForUpdate(request.email()).orElse(null);
        boolean withinBcryptLimit = request.password().getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean passwordMatches = passwordEncoder.matches(
                withinBcryptLimit ? request.password() : "invalid-password-length",
                account == null ? dummyPasswordHash : account.getPasswordHash());
        Instant now = clock.instant();

        if (account == null || !account.isEnabled() || account.isLoginLocked(now)) {
            throw AuthenticationFailureException.loginFailed();
        }
        account.resetExpiredLock(now);
        if (!withinBcryptLimit || !passwordMatches) {
            account.recordFailedLogin(now);
            throw AuthenticationFailureException.loginFailed();
        }

        account.clearLoginFailures();
        String refreshToken = tokens.createRefreshToken();
        AuthSession session = sessions.save(new AuthSession(account.getId(),
                tokens.hashRefreshToken(refreshToken), now, now.plus(TokenService.REFRESH_TOKEN_TTL)));
        return tokenResponse(account, session, refreshToken);
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        // Lock the token row so concurrent refresh requests cannot both consume the same token.
        AuthSession session = sessions.findByRefreshTokenHash(tokens.hashRefreshToken(request.refreshToken()))
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        Instant now = clock.instant();
        if (!session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account account = accounts.findById(session.getUserId())
                .filter(Account::isEnabled)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        String refreshToken = tokens.createRefreshToken();
        session.rotateRefreshToken(tokens.hashRefreshToken(refreshToken), now.plus(TokenService.REFRESH_TOKEN_TTL));
        return tokenResponse(account, session, refreshToken);
    }

    @Transactional
    public void logout(Jwt jwt) {
        UUID sessionId;
        UUID userId;
        try {
            sessionId = UUID.fromString(jwt.getId());
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        // Authentication ran before this lock; another request may have changed the session meanwhile.
        Instant now = clock.instant();
        if (!session.getUserId().equals(userId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        session.revoke(now);
    }

    @Transactional(readOnly = true)
    public Account requireActiveAccount(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            AuthSession session = sessions.findById(UUID.fromString(jwt.getId()))
                    .filter(value -> value.getUserId().equals(userId) && value.isActive(clock.instant()))
                    .orElseThrow(() -> new BadCredentialsException("Inactive session"));
            return accounts.findById(session.getUserId()).filter(Account::isEnabled)
                    .orElseThrow(() -> new BadCredentialsException("Inactive account"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BadCredentialsException("Invalid token claims");
        }
    }

    private TokenResponse tokenResponse(Account account, AuthSession session, String refreshToken) {
        return new TokenResponse(tokens.createAccessToken(account.getId(), session.getId()),
                refreshToken, "Bearer", TokenService.ACCESS_TOKEN_TTL.toSeconds(),
                session.getExpiresAt(), CurrentUserResponse.from(account));
    }
}
