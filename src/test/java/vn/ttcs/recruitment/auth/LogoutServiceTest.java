package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import vn.ttcs.recruitment.account.AccountRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final Clock clock = mock(Clock.class);
    private final AuthService service = new AuthService(mock(AccountRepository.class), sessions,
            new BCryptPasswordEncoder(4), tokens, clock);

    @Test
    void logoutRevokesTheOwnedActiveSessionWithoutIssuingTokens() {
        AuthSession session = activeSession();
        when(clock.instant()).thenReturn(NOW);
        when(sessions.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));

        service.logout(jwt(userId.toString(), session.getId().toString()));

        assertThat(session.isActive(NOW)).isFalse();
        verifyNoInteractions(tokens);
    }

    @Test
    void logoutCannotRevokeASessionOwnedByAnotherSubject() {
        AuthSession session = activeSession();
        when(clock.instant()).thenReturn(NOW);
        when(sessions.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.logout(jwt(UUID.randomUUID().toString(), session.getId().toString())))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThat(session.isActive(NOW)).isTrue();
    }

    @Test
    void logoutChecksExpirationAfterAcquiringTheSessionLock() {
        AuthSession session = activeSession();
        when(clock.instant()).thenReturn(NOW);
        when(sessions.findByIdForUpdate(session.getId())).thenAnswer(invocation -> {
            // The request passed authentication, but expired while waiting for a refresh/logout lock.
            when(clock.instant()).thenReturn(NOW.plusSeconds(60));
            return Optional.of(session);
        });

        assertThatThrownBy(() -> service.logout(jwt(userId.toString(), session.getId().toString())))
                .isInstanceOf(AuthenticationFailureException.class);
        // Expiry must not be changed into an explicit revocation.
        assertThat(session.isActive(NOW)).isTrue();
    }

    @Test
    void logoutRejectsASessionRevokedWhileTheRequestWasInFlight() {
        AuthSession session = activeSession();
        when(clock.instant()).thenReturn(NOW);
        when(sessions.findByIdForUpdate(session.getId())).thenAnswer(invocation -> {
            session.revoke(NOW);
            return Optional.of(session);
        });

        assertThatThrownBy(() -> service.logout(jwt(userId.toString(), session.getId().toString())))
                .isInstanceOf(AuthenticationFailureException.class);
        verifyNoInteractions(tokens);
    }

    @Test
    void malformedClaimsHaveAnAuthenticationFailureInsteadOfAServerError() {
        assertThatThrownBy(() -> service.logout(jwt(userId.toString(), "not-a-session-id")))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThatThrownBy(() -> service.logout(jwt("not-a-user-id", UUID.randomUUID().toString())))
                .isInstanceOf(AuthenticationFailureException.class);
        verifyNoInteractions(sessions, tokens);
    }

    @Test
    void missingSessionHasAnAuthenticationFailure() {
        UUID sessionId = UUID.randomUUID();
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.logout(jwt(userId.toString(), sessionId.toString())))
                .isInstanceOf(AuthenticationFailureException.class);
        verifyNoInteractions(tokens);
    }

    private AuthSession activeSession() {
        return new AuthSession(userId, "test-refresh-hash", NOW.minusSeconds(1), NOW.plusSeconds(60));
    }

    private Jwt jwt(String subject, String sessionId) {
        return Jwt.withTokenValue("test-token").header("alg", "HS256")
                .subject(subject).claim("jti", sessionId).issuedAt(NOW.minusSeconds(1))
                .expiresAt(NOW.plusSeconds(900)).build();
    }
}
