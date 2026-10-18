package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import vn.ttcs.recruitment.account.Role;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(PasswordChangeSessionIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordChangeSessionIntegrationTest {

    private static final String EMAIL = "password-change@example.test";
    private static final String PASSWORD = "TestingOnly123!";
    private static final String NEW_PASSWORD = "ChangedOnly456!";
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final String JWT_SECRET = randomSecret();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String browserId;

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.jwt-secret", () -> JWT_SECRET);
    }

    @BeforeEach
    void createAccount() {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM user_accounts");
        browserId = UUID.randomUUID().toString();
        accounts.saveAndFlush(new Account(EMAIL, "Password change", passwordEncoder.encode(PASSWORD),
                Set.of(Role.ADMIN), NOW));
    }

    @Test
    void successfulChangeRevokesCallerAndOtherSessionsAndRequiresTheNewPassword() throws Exception {
        JsonNode caller = successfulLogin();
        JsonNode other = successfulLogin();

        var changed = changePassword(caller, PASSWORD, NEW_PASSWORD);

        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(body(changed).path("message").asText()).contains("Vui lòng đăng nhập lại");
        assertThat(changed.body()).doesNotContain(PASSWORD, NEW_PASSWORD, "accessToken", "refreshToken");
        assertSessionRevoked(caller);
        assertSessionRevoked(other);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL", Integer.class))
                .isZero();
        assertThat(login(PASSWORD).statusCode()).isEqualTo(401);
        var fresh = login(NEW_PASSWORD);
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(me(body(fresh)).statusCode()).isEqualTo(200);
        assertThat(refresh(body(fresh)).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectedPasswordChangesKeepCallerAndOtherSessionsActive() throws Exception {
        JsonNode caller = successfulLogin();
        JsonNode other = successfulLogin();

        var wrongCurrent = changePassword(caller, "WrongCurrent123!", NEW_PASSWORD);
        assertThat(wrongCurrent.statusCode()).isEqualTo(400);
        assertThat(body(wrongCurrent).path("code").asText()).isEqualTo("CURRENT_PASSWORD_INCORRECT");
        var invalidNew = changePassword(caller, PASSWORD, "short");
        assertThat(invalidNew.statusCode()).isEqualTo(400);
        assertThat(body(invalidNew).path("code").asText()).isEqualTo("VALIDATION_ERROR");

        for (JsonNode session : List.of(caller, other)) {
            assertThat(me(session).statusCode()).isEqualTo(200);
            assertThat(refresh(session).statusCode()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NOT NULL", Integer.class))
                .isZero();
        assertThat(login(PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void onlyOneConcurrentPasswordChangeCanSucceedForTheSameSession() throws Exception {
        JsonNode caller = successfulLogin();
        List<String> replacements = List.of(NEW_PASSWORD, "AnotherChanged789!");

        try (var executor = Executors.newFixedThreadPool(2); var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM user_accounts WHERE email = ? FOR UPDATE")) {
                statement.setString(1, EMAIL);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            var first = executor.submit(() -> changePassword(caller, PASSWORD, replacements.get(0)));
            var second = executor.submit(() -> changePassword(caller, PASSWORD, replacements.get(1)));
            try {
                // Both requests authenticate before waiting on the same account row.
                // The losing request must recheck the revoked session after acquiring it.
                awaitBlockedAccountRequests(2);
            } finally {
                blocker.commit();
            }
            var responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 401);
            int winner = responses.get(0).statusCode() == 200 ? 0 : 1;
            assertThat(body(responses.get(1 - winner)).path("code").asText()).isEqualTo("SESSION_INVALID");
            assertSessionRevoked(caller);
            assertThat(login(replacements.get(winner)).statusCode()).isEqualTo(200);
            assertThat(login(replacements.get(1 - winner)).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void concurrentRefreshCannotKeepTheCallerLoggedInAfterPasswordChange() throws Exception {
        JsonNode caller = successfulLogin();
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return refresh(caller);
            });
            var changing = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return changePassword(caller, PASSWORD, NEW_PASSWORD);
            });

            var refreshResponse = refreshing.get(30, TimeUnit.SECONDS);
            assertThat(changing.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(refreshResponse.statusCode()).isIn(200, 401);
            if (refreshResponse.statusCode() == 200) {
                assertSessionRevoked(body(refreshResponse));
            }
            assertSessionRevoked(caller);
            assertThat(login(NEW_PASSWORD).statusCode()).isEqualTo(200);
        }
    }

    private void awaitBlockedAccountRequests(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query LIKE '%user_accounts%'
                    """, Integer.class);
            if (blocked != null && blocked >= count) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Password-change requests did not reach the held account lock");
    }

    private JsonNode successfulLogin() throws Exception {
        var response = login(PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private HttpResponse<String> login(String password) throws Exception {
        return request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of(
                "email", EMAIL, "password", password)), null);
    }

    private HttpResponse<String> changePassword(JsonNode session, String currentPassword, String newPassword)
            throws Exception {
        return request("POST", "/api/v1/auth/change-password", json.writeValueAsString(Map.of(
                "currentPassword", currentPassword, "newPassword", newPassword)), session.path("accessToken").asText());
    }

    private HttpResponse<String> me(JsonNode session) throws Exception {
        return request("GET", "/api/v1/auth/me", null, session.path("accessToken").asText());
    }

    private HttpResponse<String> refresh(JsonNode session) throws Exception {
        return request("POST", "/api/v1/auth/refresh", json.writeValueAsString(Map.of(
                "refreshToken", session.path("refreshToken").asText())), null);
    }

    private void assertSessionRevoked(JsonNode session) throws Exception {
        assertThat(me(session).statusCode()).isEqualTo(401);
        assertThat(refresh(session).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> request(String method, String path, String content, String accessToken) throws Exception {
        String baseUrl = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        if ("/api/v1/auth/login".equals(path)) {
            request.header("X-Device-Id", browserId);
        }
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
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
        Clock passwordChangeClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
