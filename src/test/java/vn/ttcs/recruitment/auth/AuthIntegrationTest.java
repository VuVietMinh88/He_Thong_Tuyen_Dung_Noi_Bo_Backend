package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test",
        "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthIntegrationTest {

    private static final String EMAIL = "admin@example.test";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-09-25T00:00:00Z");
    private static final String JWT_SECRET = randomSecret();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenService tokenService;
    @Autowired private MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.jwt-secret", () -> JWT_SECRET);
    }

    @BeforeEach
    void resetDatabase() {
        clock.set(START);
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
    }

    @Test
    void publicHealthStillWorksAndMeRequiresAuthentication() throws Exception {
        var health = request("GET", "/api/v1/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body(health).path("status").asText()).isEqualTo("UP");

        var legacyHealth = request("GET", "/api/health", null, null);
        assertThat(legacyHealth.statusCode()).isEqualTo(200);
        assertThat(body(legacyHealth).path("status").asText()).isEqualTo("UP");
        assertThat(body(legacyHealth).size()).isEqualTo(1);
        assertThat(request("HEAD", "/api/health", null, null).statusCode()).isEqualTo(200);
        String migrationBaseUrl = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var legacyPostPreflight = client.send(HttpRequest.newBuilder(URI.create(migrationBaseUrl + "/api/health"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(legacyPostPreflight.statusCode()).isEqualTo(403);
        var authPostPreflight = client.send(HttpRequest.newBuilder(URI.create(migrationBaseUrl + "/api/v1/auth/login"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(authPostPreflight.statusCode()).isEqualTo(200);
        assertThat(authPostPreflight.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");

        var me = request("GET", "/api/v1/auth/me", null, null);
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(body(me).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(me.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
    }

    @Test
    void loginNormalizesEmailAndReturnsSafeUserData() throws Exception {
        var response = login("  ADMIN@EXAMPLE.TEST  ", PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = body(response);
        assertThat(result.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(result.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(result.path("refreshToken").asText()).hasSize(43);
        assertThat(result.path("user").path("email").asText()).isEqualTo(EMAIL);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body()).doesNotContain("passwordHash", PASSWORD, "failedLoginAttempts");

        var me = request("GET", "/api/v1/auth/me", null, result.path("accessToken").asText());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(body(me).path("fullName").asText()).isEqualTo("Quản trị viên");
        assertThat(body(me).path("roles").toString()).contains("ADMIN");
    }

    @Test
    void unknownWrongAndDisabledAccountsHaveTheSameFailure() throws Exception {
        var unknown = login("unknown@example.test", PASSWORD);
        var wrong = login(EMAIL, "WrongPassword1");
        jdbc.update("UPDATE user_accounts SET enabled = false WHERE email = ?", EMAIL);
        var disabled = login(EMAIL, PASSWORD);
        assertThat(List.of(unknown.statusCode(), wrong.statusCode(), disabled.statusCode()))
                .containsOnly(401);
        assertThat(body(unknown)).isEqualTo(body(wrong)).isEqualTo(body(disabled));
    }

    @Test
    void fiveFailuresAreCommittedAndLockExpiresAtFifteenMinutes() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(login(EMAIL, "WrongPassword1").statusCode()).isEqualTo(401);
            assertThat(failedAttempts()).isEqualTo(attempt);
        }
        Instant lockedUntil = jdbc.queryForObject("SELECT locked_until FROM user_accounts WHERE email = ?",
                Timestamp.class, EMAIL).toInstant();
        assertThat(lockedUntil).isEqualTo(START.plus(Duration.ofMinutes(15)));
        assertThat(login(EMAIL, PASSWORD).statusCode()).isEqualTo(401);
        clock.set(START.plusSeconds(899));
        assertThat(login(EMAIL, PASSWORD).statusCode()).isEqualTo(401);
        assertThat(failedAttempts()).isEqualTo(5);
        clock.set(START.plusSeconds(900));
        assertThat(login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(failedAttempts()).isZero();
    }

    @Test
    void successfulLoginResetsConsecutiveFailures() throws Exception {
        login(EMAIL, "WrongPassword1");
        login(EMAIL, "WrongPassword1");
        assertThat(login(EMAIL, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(failedAttempts()).isZero();
        login(EMAIL, "WrongPassword1");
        assertThat(failedAttempts()).isEqualTo(1);
    }

    @Test
    void concurrentFailuresDoNotLoseCounterUpdates() throws Exception {
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                attempts.add(() -> login(EMAIL, "WrongPassword1").statusCode());
            }
            for (var result : executor.invokeAll(attempts)) {
                assertThat(result.get()).isEqualTo(401);
            }
        }
        assertThat(failedAttempts()).isEqualTo(5);
        assertThat(login(EMAIL, PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRotatesTokenAndExtendsTheSession() throws Exception {
        JsonNode original = successfulLogin();
        String originalRefresh = original.path("refreshToken").asText();
        clock.set(START.plus(Duration.ofDays(1)));
        var refreshed = refresh(originalRefresh);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        JsonNode replacement = body(refreshed);
        assertThat(replacement.path("refreshToken").asText()).isNotEqualTo(originalRefresh);
        assertThat(refreshed.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(refreshed.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", String.class))
                .isEqualTo(tokenService.hashRefreshToken(replacement.path("refreshToken").asText()));
        assertThat(Instant.parse(replacement.path("refreshExpiresAt").asText()))
                .isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(refresh(originalRefresh).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/auth/me", null,
                replacement.path("accessToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentRefreshConsumesTheOldTokenOnlyOnce() throws Exception {
        String refreshToken = successfulLogin().path("refreshToken").asText();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<HttpResponse<String>>>of(
                    () -> refresh(refreshToken), () -> refresh(refreshToken)));
            var responses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                    .containsExactlyInAnyOrder(200, 401);
            JsonNode winner = body(responses.stream().filter(response -> response.statusCode() == 200)
                    .findFirst().orElseThrow());
            assertThat(request("GET", "/api/v1/auth/me", null, winner.path("accessToken").asText()).statusCode())
                    .isEqualTo(200);
            assertThat(refresh(winner.path("refreshToken").asText()).statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "malformed"})
    void refreshUsesItsBodyTokenEvenWhenAnInvalidBearerHeaderIsAttached(String kind) throws Exception {
        JsonNode original = successfulLogin();
        clock.set(START.plusSeconds(900));
        String headerToken = kind.equals("expired") ? original.path("accessToken").asText() : "not.a.jwt";
        var response = request("POST", "/api/v1/auth/refresh", json.writeValueAsString(Map.of(
                "refreshToken", original.path("refreshToken").asText())), headerToken);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode replacement = body(response);
        assertThat(request("GET", "/api/v1/auth/me", null,
                replacement.path("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(refresh(original.path("refreshToken").asText()).statusCode()).isEqualTo(401);
    }

    @Test
    void invalidRefreshRequestsDoNotChangeTheLiveSession() throws Exception {
        JsonNode original = successfulLogin();
        String hash = jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", String.class);
        Timestamp expiry = jdbc.queryForObject("SELECT expires_at FROM auth_sessions", Timestamp.class);
        for (String payload : List.of("{}", "null", "{\"refreshToken\":null}",
                "{\"refreshToken\":\"\"}", "{\"refreshToken\":\"bad-token\"}", "{")) {
            var response = request("POST", "/api/v1/auth/refresh", payload, null);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain(original.path("refreshToken").asText(), "bad-token");
        }
        var unknown = refresh("A".repeat(43));
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(body(unknown).path("code").asText()).isEqualTo("SESSION_INVALID");
        assertThat(unknown.body()).doesNotContain("A".repeat(43));
        assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", String.class)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_sessions", Timestamp.class)).isEqualTo(expiry);
        assertThat(refresh(original.path("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void accessExpiresExactlyAtFifteenMinutesAndCanBeRefreshed() throws Exception {
        JsonNode original = successfulLogin();
        clock.set(START.plusSeconds(899));
        assertThat(request("GET", "/api/v1/auth/me", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(200);
        clock.set(START.plusSeconds(900));
        assertThat(request("GET", "/api/v1/auth/me", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(401);
        var refreshed = refresh(original.path("refreshToken").asText());
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/auth/me", null,
                body(refreshed).path("accessToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void refreshExpiresExactlyAtSevenDaysWithoutActivity() throws Exception {
        JsonNode original = successfulLogin();
        clock.set(START.plus(Duration.ofDays(7)));
        assertThat(refresh(original.path("refreshToken").asText()).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "malformed"})
    void loginCanRecoverWhileTheClientStillAttachesAnInvalidBearer(String kind) throws Exception {
        JsonNode original = successfulLogin();
        clock.set(START.plusSeconds(900));
        String oldHeader = kind.equals("expired") ? original.path("accessToken").asText() : "not.a.jwt";
        var response = request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of(
                "email", EMAIL, "password", PASSWORD)), oldHeader);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode replacement = body(response);
        assertThat(request("GET", "/api/v1/auth/me", null,
                replacement.path("accessToken").asText()).statusCode()).isEqualTo(200);
        var invalidPassword = request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of(
                "email", EMAIL, "password", "WrongPassword1")), oldHeader);
        assertThat(invalidPassword.statusCode()).isEqualTo(401);
        assertThat(body(invalidPassword).path("code").asText()).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void aValidJwtCannotOutliveItsDatabaseSessionAndReadsDoNotExtendIt() throws Exception {
        JsonNode original = successfulLogin();
        Instant expiresAt = START.plusSeconds(60);
        jdbc.update("UPDATE auth_sessions SET expires_at = ?", Timestamp.from(expiresAt));
        String hash = jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", String.class);
        clock.set(expiresAt.minusSeconds(1));
        assertThat(request("GET", "/api/v1/auth/me", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_sessions", Timestamp.class).toInstant())
                .isEqualTo(expiresAt);

        clock.set(expiresAt);
        var me = request("GET", "/api/v1/auth/me", null, original.path("accessToken").asText());
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(body(me).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(me.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(me.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var expiredRefresh = refresh(original.path("refreshToken").asText());
        assertThat(expiredRefresh.statusCode()).isEqualTo(401);
        assertThat(body(expiredRefresh).path("code").asText()).isEqualTo("SESSION_INVALID");
        assertThat(request("POST", "/api/v1/auth/logout", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", String.class)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM auth_sessions", Timestamp.class).toInstant())
                .isEqualTo(expiresAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    void refreshImmediatelyBeforeSevenDaysKeepsTheSessionUsablePastTheOldDeadline() throws Exception {
        JsonNode original = successfulLogin();
        Instant oldDeadline = START.plus(Duration.ofDays(7));
        clock.set(oldDeadline.minusSeconds(1));
        var response = refresh(original.path("refreshToken").asText());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode replacement = body(response);
        assertThat(Instant.parse(replacement.path("refreshExpiresAt").asText()))
                .isEqualTo(clock.instant().plus(Duration.ofDays(7)));

        clock.set(oldDeadline);
        assertThat(request("GET", "/api/v1/auth/me", null,
                replacement.path("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(refresh(original.path("refreshToken").asText()).statusCode()).isEqualTo(401);
        assertThat(refresh(replacement.path("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void expiredAccessCanBeRefreshedBeforeLogoutAndCannotRefreshAfterLogout() throws Exception {
        JsonNode original = successfulLogin();
        clock.set(START.plusSeconds(900));
        assertThat(request("POST", "/api/v1/auth/logout", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(401);
        var response = refresh(original.path("refreshToken").asText());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode replacement = body(response);
        assertThat(request("POST", "/api/v1/auth/logout", null,
                replacement.path("accessToken").asText()).statusCode()).isEqualTo(204);
        assertThat(refresh(replacement.path("refreshToken").asText()).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutImmediatelyRevokesAllTokensOfThatSession() throws Exception {
        JsonNode original = successfulLogin();
        clock.set(START.plusSeconds(1));
        JsonNode replacement = body(refresh(original.path("refreshToken").asText()));
        var logout = request("POST", "/api/v1/auth/logout", null, replacement.path("accessToken").asText());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(logout.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(request("POST", "/api/v1/auth/logout", null,
                replacement.path("accessToken").asText()).statusCode()).isEqualTo(401);
        for (JsonNode tokens : List.of(original, replacement)) {
            assertThat(request("GET", "/api/v1/auth/me", null,
                    tokens.path("accessToken").asText()).statusCode()).isEqualTo(401);
            assertThat(refresh(tokens.path("refreshToken").asText()).statusCode()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NOT NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void logoutDoesNotRevokeOtherDevices() throws Exception {
        JsonNode first = successfulLogin();
        JsonNode second = successfulLogin();
        request("POST", "/api/v1/auth/logout", null, first.path("accessToken").asText());
        assertThat(request("GET", "/api/v1/auth/me", null,
                second.path("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(refresh(second.path("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void logoutRejectsMissingCredentialsAndAnotherSessionsSubject() throws Exception {
        JsonNode original = successfulLogin();
        UUID sessionId = jdbc.queryForObject("SELECT id FROM auth_sessions", UUID.class);
        assertThat(request("POST", "/api/v1/auth/logout", null, null).statusCode()).isEqualTo(401);
        String wrongSubject = tokenService.createAccessToken(UUID.randomUUID(), sessionId);
        assertThat(request("POST", "/api/v1/auth/logout", null, wrongSubject).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/auth/me", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    void refreshAndLogoutCannotLeaveAUsableSessionAfterLogoutReturns() throws Exception {
        JsonNode original = successfulLogin();
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return refresh(original.path("refreshToken").asText());
            });
            var loggingOut = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return request("POST", "/api/v1/auth/logout", null, original.path("accessToken").asText());
            });
            var refreshResponse = refreshing.get(30, TimeUnit.SECONDS);
            assertThat(loggingOut.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(refreshResponse.statusCode()).isIn(200, 401);
            if (refreshResponse.statusCode() == 200) {
                JsonNode replacement = body(refreshResponse);
                assertThat(request("GET", "/api/v1/auth/me", null,
                        replacement.path("accessToken").asText()).statusCode()).isEqualTo(401);
                assertThat(refresh(replacement.path("refreshToken").asText()).statusCode()).isEqualTo(401);
            }
        }
        assertThat(refresh(original.path("refreshToken").asText()).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/auth/me", null,
                original.path("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void disablingAccountBlocksExistingAccessAndRefreshTokens() throws Exception {
        JsonNode tokens = successfulLogin();
        jdbc.update("UPDATE user_accounts SET enabled = false WHERE email = ?", EMAIL);
        assertThat(request("GET", "/api/v1/auth/me", null,
                tokens.path("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(refresh(tokens.path("refreshToken").asText()).statusCode()).isEqualTo(401);
    }

    @Test
    void currentRolesAreReadFromDatabaseOnTheNextRequest() throws Exception {
        JsonNode tokens = successfulLogin();
        UUID userId = UUID.fromString(tokens.path("user").path("id").asText());
        jdbc.update("UPDATE user_roles SET role = 'INTERVIEWER' WHERE user_id = ?", userId);
        var me = request("GET", "/api/v1/auth/me", null, tokens.path("accessToken").asText());
        assertThat(body(me).path("roles").toString()).contains("INTERVIEWER").doesNotContain("ADMIN");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void internalRolesCanReadOnlyTheirOwnProfile(Role role) throws Exception {
        String email = "role-" + role.name().toLowerCase() + "@example.test";
        Account account = accounts.saveAndFlush(new Account(email, "Người dùng kiểm thử",
                passwordEncoder.encode(PASSWORD), Set.of(role), clock.instant()));
        JsonNode tokens = body(login(email, PASSWORD));
        var me = request("GET", "/api/v1/auth/me", null, tokens.path("accessToken").asText());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(body(me).path("id").asText()).isEqualTo(account.getId().toString());
        assertThat(body(me).path("roles").toString()).contains(role.name());
        assertThat(request("GET", "/api/v1/users", null,
                tokens.path("accessToken").asText()).statusCode()).isEqualTo(403);
    }

    @Test
    void malformedAndTamperedJwtAreRejected() throws Exception {
        String token = successfulLogin().path("accessToken").asText();
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
        for (String invalidToken : List.of("not.a.jwt", tampered)) {
            var response = request("GET", "/api/v1/auth/me", null, invalidToken);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(body(response).path("code").asText()).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void signedTokenMustBelongToItsLiveDatabaseSession() throws Exception {
        JsonNode tokens = successfulLogin();
        UUID userId = UUID.fromString(tokens.path("user").path("id").asText());
        UUID sessionId = jdbc.queryForObject("SELECT id FROM auth_sessions", UUID.class);
        for (String invalidToken : List.of(tokenService.createAccessToken(UUID.randomUUID(), sessionId),
                tokenService.createAccessToken(userId, UUID.randomUUID()))) {
            assertThat(request("GET", "/api/v1/auth/me", null, invalidToken).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void validatesInputWithoutReturningSubmittedPasswords() throws Exception {
        var invalid = login("not-an-email", "");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(body(invalid).path("fieldErrors").has("email")).isTrue();
        assertThat(body(invalid).path("fieldErrors").has("password")).isTrue();
        var malformed = request("POST", "/api/v1/auth/login", "{", null);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(body(malformed).path("code").asText()).isEqualTo("INVALID_JSON");
        assertThat(refresh("bad-token").statusCode()).isEqualTo(400);
        assertThat(login(EMAIL, "ắ".repeat(50)).statusCode()).isEqualTo(401);
    }

    @Test
    void missingFieldsAndNullBodyAreRejectedWithoutCreatingASession() throws Exception {
        for (String payload : List.of("{}", "null", "{\"email\":null,\"password\":null}")) {
            var response = request("POST", "/api/v1/auth/login", payload, null);
            assertThat(response.statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class)).isZero();
    }

    @Test
    void onlyConfiguredFrontendOriginsCanUseCors() throws Exception {
        String baseUrl = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        for (String origin : List.of("http://localhost:5173", "https://untrusted.example.test")) {
            HttpRequest preflight = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(preflight, HttpResponse.BodyHandlers.ofString());
            if (origin.equals("http://localhost:5173")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            } else {
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }

    @Test
    void databaseContainsOnlyHashedCredentialsAndBootstrapDoesNotResetThem() throws Exception {
        JsonNode tokens = successfulLogin();
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email = ?",
                String.class, EMAIL);
        String refreshHash = jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions", String.class);
        assertThat(passwordHash).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
        assertThat(refreshHash).hasSize(64).isNotEqualTo(tokens.path("refreshToken").asText());
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email = ?", String.class, EMAIL))
                .isEqualTo(passwordHash);
    }

    private JsonNode successfulLogin() throws Exception {
        var response = login(EMAIL, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of(
                "email", email, "password", password)), null);
    }

    private HttpResponse<String> refresh(String token) throws Exception {
        return request("POST", "/api/v1/auth/refresh", json.writeValueAsString(Map.of("refreshToken", token)), null);
    }

    private HttpResponse<String> request(String method, String path, String content, String accessToken) throws Exception {
        String baseUrl = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private int failedAttempts() {
        return jdbc.queryForObject("SELECT failed_login_attempts FROM user_accounts WHERE email = ?", Integer.class, EMAIL);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres embeddedPostgres() throws IOException {
            return EmbeddedPostgres.builder().setPort(0)
                    .setServerConfig("listen_addresses", "127.0.0.1").start();
        }

        @Bean
        DataSource dataSource(EmbeddedPostgres postgres) {
            return postgres.getPostgresDatabase();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> current = new AtomicReference<>(START);

        void set(Instant value) { current.set(value); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }
}
