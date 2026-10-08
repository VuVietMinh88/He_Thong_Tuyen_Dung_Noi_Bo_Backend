package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String EMAIL = "recruiter@example.test";
    private static final String PASSWORD = "TestingOnly123!";

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    // Lower cost keeps unit tests fast; the application uses BCrypt cost 12.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuthService service = new AuthService(accounts, sessions, passwordEncoder, tokens, clock);
    private Account account;

    @BeforeEach
    void createAccount() {
        account = new Account(EMAIL, "Recruiter", passwordEncoder.encode(PASSWORD), Set.of(Role.RECRUITER), NOW);
        when(accounts.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(account));
    }

    @Test
    void successfulLoginReturnsRoleAndClearsPreviousFailures() {
        account.recordFailedLogin(NOW);
        when(tokens.createRefreshToken()).thenReturn("test-refresh-token");
        when(tokens.hashRefreshToken("test-refresh-token")).thenReturn("test-refresh-hash");
        when(sessions.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokens.createAccessToken(any(), any())).thenReturn("test-access-token");

        TokenResponse response = service.login(new LoginRequest("  RECRUITER@EXAMPLE.TEST  ", PASSWORD));

        assertThat(response.accessToken()).isEqualTo("test-access-token");
        assertThat(response.user().roles()).containsExactly(Role.RECRUITER);
        // Four new failures must still leave the account unlocked after a successful login.
        for (int attempt = 0; attempt < 4; attempt++) {
            account.recordFailedLogin(NOW);
        }
        assertThat(account.isLoginLocked(NOW)).isFalse();
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnTheSameFailureAndCreateNoSession() {
        when(accounts.findByEmailForUpdate("unknown@example.test")).thenReturn(Optional.empty());
        for (LoginRequest request : new LoginRequest[] {
                new LoginRequest(EMAIL, "WrongPassword1"), new LoginRequest("unknown@example.test", PASSWORD)}) {
            assertThatThrownBy(() -> service.login(request))
                    .isInstanceOf(AuthenticationFailureException.class)
                    .hasMessage("Không thể đăng nhập bằng thông tin đã cung cấp.");
        }
        verify(sessions, never()).save(any());
    }

    @Test
    void fifthFailureLocksAccountAndCorrectPasswordCannotBypassTheLock() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "WrongPassword1")))
                    .isInstanceOf(AuthenticationFailureException.class);
        }
        assertThat(account.isLoginLocked(NOW.plusSeconds(899))).isTrue();
        assertThat(account.isLoginLocked(NOW.plus(Duration.ofMinutes(15)))).isFalse();
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthenticationFailureException.class);
        verify(sessions, never()).save(any());
    }

    @Test
    void overlongUtf8PasswordCannotAuthenticateEvenWithACorrectBcryptPrefix() {
        String prefix = "a".repeat(72);
        account = new Account(EMAIL, "Recruiter", passwordEncoder.encode(prefix), Set.of(Role.RECRUITER), NOW);
        when(accounts.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, prefix + "ắ")))
                .isInstanceOf(AuthenticationFailureException.class);
        verify(sessions, never()).save(any());
    }
}
