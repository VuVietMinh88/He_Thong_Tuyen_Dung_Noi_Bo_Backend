package vn.ttcs.recruitment.auth;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "spring.mail.host=127.0.0.1", "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "app.password-reset.page-url=http://localhost:5173/reset-password",
        "app.password-reset.mail-from=no-reply@ttcs.test",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetIntegrationTest {

    private static final String EMAIL = "admin@example.test";
    private static final String PASSWORD = "TestingOnly123!";
    private static final String NEW_PASSWORD = "ChangedOnly456!";
    private static final Instant START = Instant.parse("2026-10-04T00:00:00Z");
    private static final LocalSmtpServer SMTP = startSmtp();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ResetTokenGenerator generator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired @Qualifier("passwordResetExecutor") private ThreadPoolTaskExecutor mailExecutor;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
        registry.add("spring.mail.port", SMTP::port);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        awaitMailJobs();
        clock.set(START);
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        SMTP.reset();
    }

    @AfterEach
    void finishMailJobs() throws Exception {
        SMTP.releaseReceipt();
        awaitMailJobs();
    }

    @AfterAll
    static void closeSmtp() throws IOException { SMTP.close(); }

    @Test
    void existingUnknownAndDisabledEmailsReceiveTheSamePublicResponse() throws Exception {
        var existing = forgot("  ADMIN@EXAMPLE.TEST  ", null);
        awaitMailJobs();
        var unknown = forgot("unknown@example.test", null);
        jdbc.update("UPDATE user_accounts SET enabled = false");
        var disabled = forgot(EMAIL, null);
        awaitMailJobs();
        assertThat(List.of(existing.statusCode(), unknown.statusCode(), disabled.statusCode())).containsOnly(202);
        assertThat(body(existing)).isEqualTo(body(unknown)).isEqualTo(body(disabled));
        assertThat(existing.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(body(existing).properties()).hasSize(1);
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(countTokens()).isEqualTo(1);
    }

    @Test
    void sendsUtf8EmailAndStoresOnlyTheHashWithThirtyMinuteExpiry() throws Exception {
        String token = issueToken();
        MimeMessage message = SMTP.messages().getFirst();
        assertThat(message.getSubject()).contains("Đặt lại mật khẩu");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@ttcs.test");
        assertThat(message.getContent().toString()).contains("30 phút", "chỉ dùng được một lần",
                "http://localhost:5173/reset-password?token=" + token).doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens", String.class))
                .isEqualTo(generator.hash(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM password_reset_tokens", Timestamp.class).toInstant())
                .isEqualTo(START.plusSeconds(1800));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts", String.class)).startsWith("$2");
    }

    @Test
    void requestingALinkDoesNotChangePasswordLockOrSessions() throws Exception {
        JsonNode login = login(PASSWORD);
        jdbc.update("UPDATE user_accounts SET failed_login_attempts = 4");
        issueToken();
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM user_accounts", Integer.class)).isEqualTo(4);
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash())).isTrue();
        assertThat(me(login.path("accessToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void parallelRequestsAreThrottledForOneMinuteWithoutRevealingIt() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                tasks.add(() -> forgot(EMAIL, null).statusCode());
            }
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get()).isEqualTo(202);
            }
        }
        awaitMailJobs();
        assertThat(SMTP.messages()).hasSize(1);
        clock.set(START.plusSeconds(59));
        forgot(EMAIL, null);
        awaitMailJobs();
        assertThat(SMTP.messages()).hasSize(1);
        clock.set(START.plusSeconds(60));
        issueToken();
        assertThat(SMTP.messages()).hasSize(2);
    }

    @Test
    void smtpFailureRollsBackNewTokenAndAllowsRetryWithoutLeakingFailure() throws Exception {
        issueToken();
        clock.set(START.plusSeconds(60));
        SMTP.rejectDelivery(true);
        var failed = forgot(EMAIL, null);
        awaitMailJobs();
        assertThat(failed.statusCode()).isEqualTo(202);
        assertThat(body(failed)).isEqualTo(body(forgot("unknown@example.test", null)));
        awaitMailJobs();
        assertThat(countTokens()).isEqualTo(1);
        assertThat(unusedTokens()).isEqualTo(1);
        SMTP.rejectDelivery(false);
        issueToken();
        assertThat(countTokens()).isEqualTo(2);
        assertThat(unusedTokens()).isEqualTo(2);
    }

    @Test
    void responseDoesNotWaitForTheSmtpReceipt() throws Exception {
        SMTP.pauseReceipt();
        try {
            assertThat(forgot(EMAIL, null).statusCode()).isEqualTo(202);
            assertThat(SMTP.awaitData()).isTrue();
        } finally {
            SMTP.releaseReceipt();
        }
        awaitMailJobs();
        assertThat(SMTP.messages()).hasSize(1);
    }

    @Test
    void fullQueueReturnsTheSameBusyResponseForKnownAndUnknownEmails() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mailExecutor.execute(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        List<Future<?>> queued = new ArrayList<>();
        try {
            for (int index = 0; index < 100; index++) {
                queued.add(mailExecutor.submit(() -> { }));
            }
            var known = forgot(EMAIL, null);
            var unknown = forgot("unknown@example.test", null);
            assertThat(known.statusCode()).isEqualTo(503);
            assertThat(unknown.statusCode()).isEqualTo(503);
            assertThat(body(known)).isEqualTo(body(unknown));
        } finally {
            release.countDown();
            for (Future<?> task : queued) { task.get(10, TimeUnit.SECONDS); }
        }
        assertThat(countTokens()).isZero();
    }

    private String issueToken() throws Exception {
        int previous = SMTP.messages().size();
        assertThat(forgot(EMAIL, null).statusCode()).isEqualTo(202);
        awaitMailJobs();
        assertThat(SMTP.messages()).hasSize(previous + 1);
        return lastEmailedToken();
    }

    private String lastEmailedToken() throws Exception {
        var match = Pattern.compile("\\?token=([A-Za-z0-9_-]{43})").matcher(SMTP.messages().getLast().getContent().toString());
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private void awaitMailJobs() throws Exception {
        // A FIFO barrier on the single mail worker also waits for its database transaction to finish.
        mailExecutor.submit(() -> { }).get(15, TimeUnit.SECONDS);
    }

    private HttpResponse<String> forgot(String email, String bearer) throws Exception {
        return post("/forgot-password", Map.of("email", email), bearer);
    }

    private HttpResponse<String> reset(String token, String password, String bearer) throws Exception {
        return post("/reset-password", Map.of("token", token, "newPassword", password), bearer);
    }

    private JsonNode login(String password) throws Exception {
        var response = post("/login", Map.of("email", EMAIL, "password", password), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private HttpResponse<String> refresh(String token) throws Exception {
        return post("/refresh", Map.of("refreshToken", token), null);
    }

    private HttpResponse<String> me(String token) throws Exception { return request("GET", "/me", null, token); }

    private HttpResponse<String> post(String path, Map<String, String> payload, String bearer) throws Exception {
        return request("POST", path, json.writeValueAsString(payload), bearer);
    }

    private HttpResponse<String> request(String method, String path, String payload, String bearer) throws Exception {
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + "/api/v1/auth";
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (bearer != null) { builder.header("Authorization", "Bearer " + bearer); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private String passwordHash() { return jdbc.queryForObject("SELECT password_hash FROM user_accounts", String.class); }
    private int countTokens() { return jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class); }
    private int unusedTokens() { return jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE used_at IS NULL", Integer.class); }

    private static LocalSmtpServer startSmtp() {
        try {
            return new LocalSmtpServer();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start local SMTP fixture", exception);
        }
    }
}
