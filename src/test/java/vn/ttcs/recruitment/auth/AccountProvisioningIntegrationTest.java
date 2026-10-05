package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "spring.mail.host=127.0.0.1", "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "app.account-activation.page-url=http://localhost:5173/activate-account",
        "app.account-activation.ttl=24h", "app.password-reset.mail-from=no-reply@ttcs.test",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountProvisioningIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final String EMAIL = "new@example.test";
    private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalSmtpServer SMTP = startSmtp();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ResetTokenGenerator generator;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String adminToken;

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
        clock.set(START);
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        SMTP.reset();
        adminToken = login("admin@example.test", PASSWORD).path("accessToken").asText();
    }

    @AfterAll
    static void closeSmtp() throws IOException { SMTP.close(); }

    @Test
    void adminCreatesPendingAccountWithEmailAndCanActivateThenLogin() throws Exception {
        var result = create("  NEW@EXAMPLE.TEST  ", adminToken);
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var created = body(result);
        assertThat(created.path("email").asText()).isEqualTo(EMAIL);
        assertThat(created.path("fullName").asText()).isEqualTo("Người phỏng vấn mới");
        assertThat(created.path("roles").toString()).contains("INTERVIEWER", "HIRING_MANAGER");
        assertThat(created.path("status").asText()).isEqualTo("PENDING_ACTIVATION");
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(accounts.findById(id).orElseThrow().isEnabled()).isFalse();
        assertThat(SMTP.messages()).hasSize(1);
        var message = SMTP.messages().getFirst();
        assertThat(message.getSubject()).contains("Kích hoạt tài khoản");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@ttcs.test");
        String content = message.getContent().toString();
        String token = activationToken(content);
        String temporaryPassword = temporaryPassword(content);
        assertThat(content).contains("24 giờ", "chỉ dùng được một lần", "đổi mật khẩu");
        assertThat(temporaryPassword).hasSize(24).matches(".*[A-Za-z].*").matches(".*[0-9].*");
        String hash = accounts.findById(id).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2").isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, hash)).isTrue();
        assertThat(result.body()).doesNotContain(temporaryPassword, token, hash);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM account_activation_tokens", String.class))
                .isEqualTo(generator.hash(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM account_activation_tokens", Timestamp.class).toInstant())
                .isEqualTo(START.plus(Duration.ofHours(24)));
        assertThat(post("/api/v1/auth/login", Map.of("email", EMAIL, "password", temporaryPassword), null).statusCode())
                .isEqualTo(401);
        var activated = activate(token, "expired.or.invalid.bearer");
        assertThat(activated.statusCode()).isEqualTo(200);
        assertThat(activated.body()).doesNotContain(token, temporaryPassword, "accessToken");
        assertThat(activated.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var newLogin = login(EMAIL, temporaryPassword);
        assertThat(newLogin.path("user").path("roles").toString()).contains("INTERVIEWER", "HIRING_MANAGER");
        assertThat(activate(token, null).statusCode()).isEqualTo(400);
        assertThat(accounts.findById(id).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void existingEmailCaseAndWhitespaceAreRejectedWithoutAnEmailOrDataChange() throws Exception {
        var result = create("  ADMIN@EXAMPLE.TEST ", adminToken);
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(body(result).path("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(body(result).path("message").asText()).contains("Email đã được sử dụng");
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activation_tokens", Integer.class)).isZero();
    }

    @Test
    void duplicatePendingInvitationDoesNotOverwritePasswordOrSendAgain() throws Exception {
        assertThat(create(EMAIL, adminToken).statusCode()).isEqualTo(201);
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email=?", String.class, EMAIL);
        assertThat(create(" NEW@EXAMPLE.TEST ", adminToken).statusCode()).isEqualTo(409);
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email=?", String.class, EMAIL))
                .isEqualTo(hash);
    }

    @Test
    void simultaneousDifferentAdminsReceiveOneCreatedAndOneConflict() throws Exception {
        accounts.saveAndFlush(new Account("secondadmin@example.test", "Second admin",
                passwordEncoder.encode(PASSWORD), Set.of(Role.ADMIN), START));
        String secondToken = login("secondadmin@example.test", PASSWORD).path("accessToken").asText();
        SMTP.pauseReceipt();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> create(EMAIL, adminToken));
            assertThat(SMTP.awaitData()).isTrue();
            var second = executor.submit(() -> create(" NEW@EXAMPLE.TEST ", secondToken));
            try {
                // Prove the second insert reached the database while the first was uncommitted.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blockedInsert = false;
                while (System.nanoTime() < deadline) {
                    blockedInsert = jdbc.queryForObject("""
                            SELECT count(*) > 0 FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query LIKE '%insert into user_accounts%'
                            """, Boolean.class);
                    if (blockedInsert) { break; }
                    Thread.sleep(20);
                }
                assertThat(blockedInsert).isTrue();
            } finally {
                SMTP.releaseReceipt();
            }
            assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            var duplicate = second.get(15, TimeUnit.SECONDS);
            assertThat(duplicate.statusCode()).isEqualTo(409);
            assertThat(body(duplicate).path("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
        } finally {
            SMTP.releaseReceipt();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE email=?", Integer.class, EMAIL)).isEqualTo(1);
        assertThat(SMTP.messages()).hasSize(1);
    }

    @Test
    void smtpFailureRollsBackAndAllowsRetry() throws Exception {
        SMTP.rejectDelivery(true);
        var failed = create(EMAIL, adminToken);
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(body(failed).path("code").asText()).isEqualTo("ACCOUNT_EMAIL_UNAVAILABLE");
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activation_tokens", Integer.class)).isZero();
        assertThat(failed.body()).doesNotContain("451", "SMTP", "Mật khẩu tạm");
        SMTP.rejectDelivery(false);
        assertThat(create(EMAIL, adminToken).statusCode()).isEqualTo(201);
        assertThat(accounts.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void nonAdminCannotCreateEvenWithValidLogin(Role role) throws Exception {
        accounts.saveAndFlush(new Account("staff@example.test", "Staff",
                passwordEncoder.encode(PASSWORD), Set.of(role), START));
        String token = login("staff@example.test", PASSWORD).path("accessToken").asText();
        var denied = create(EMAIL, token);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(body(denied).path("message").asText()).contains("quyền");
        assertThat(accounts.count()).isEqualTo(2);
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test
    void noAuthenticationAndRevokedRoleCannotCreate() throws Exception {
        assertThat(create(EMAIL, null).statusCode()).isEqualTo(401);
        jdbc.update("DELETE FROM user_roles WHERE role='ADMIN'");
        assertThat(create(EMAIL, adminToken).statusCode()).isEqualTo(403);
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(SMTP.messages()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\":\"invalid\",\"fullName\":\"User\",\"roles\":[\"INTERVIEWER\"]}",
            "{\"email\":\"new@example.test\",\"fullName\":\"  \",\"roles\":[\"INTERVIEWER\"]}",
            "{\"email\":\"new@example.test\",\"fullName\":\"User\",\"roles\":[]}",
            "{\"email\":\"new@example.test\",\"fullName\":\"User\",\"roles\":[null]}",
            "{\"email\":\"new@example.test\",\"fullName\":\"User\",\"roles\":[\"CANDIDATE\"]}",
            "{}"
    })
    void invalidInputDoesNotCreateOrSendEmail(String content) throws Exception {
        var response = request("POST", "/api/v1/accounts", content, adminToken);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test
    void oversizeInputAndMalformedJsonAreRejected() throws Exception {
        assertThat(post("/api/v1/accounts", Map.of("email", EMAIL, "fullName", "a".repeat(256),
                "roles", List.of("INTERVIEWER")), adminToken).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/v1/accounts", "{", adminToken).statusCode()).isEqualTo(400);
        assertThat(SMTP.messages()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {86399, 86400, 86401})
    void activationExpiryUsesStrictBoundary(long elapsed) throws Exception {
        assertThat(create(EMAIL, adminToken).statusCode()).isEqualTo(201);
        String token = activationToken(SMTP.messages().getFirst().getContent().toString());
        clock.set(START.plusSeconds(elapsed));
        assertThat(activate(token, null).statusCode()).isEqualTo(elapsed < 86400 ? 200 : 400);
        assertThat(jdbc.queryForObject("SELECT enabled FROM user_accounts WHERE email=?", Boolean.class, EMAIL))
                .isEqualTo(elapsed < 86400);
    }

    @Test
    void unknownWrongPurposeAndMalformedTokensDoNotActivate() throws Exception {
        assertThat(create(EMAIL, adminToken).statusCode()).isEqualTo(201);
        String token = activationToken(SMTP.messages().getFirst().getContent().toString());
        String resetToken = generator.create();
        UUID adminId = UUID.fromString(login("admin@example.test", PASSWORD).path("user").path("id").asText());
        jdbc.update("""
                INSERT INTO password_reset_tokens (id,user_id,token_hash,created_at,expires_at)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), adminId, generator.hash(resetToken), Timestamp.from(START),
                Timestamp.from(START.plusSeconds(1800)));
        for (String invalid : List.of(generator.create(), resetToken, "abc")) {
            assertThat(activate(invalid, null).statusCode()).isEqualTo(400);
        }
        var reset = post("/api/v1/auth/reset-password", Map.of("token", token, "newPassword", "NewPassword123"), null);
        assertThat(reset.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT enabled FROM user_accounts WHERE email=?", Boolean.class, EMAIL)).isFalse();
        assertThat(activate(token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentActivationConsumesOnlyOnce() throws Exception {
        assertThat(create(EMAIL, adminToken).statusCode()).isEqualTo(201);
        String token = activationToken(SMTP.messages().getFirst().getContent().toString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> activate(token, null));
            var second = executor.submit(() -> activate(token, null));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 400);
        }
    }

    private HttpResponse<String> create(String email, String token) throws Exception {
        return post("/api/v1/accounts", Map.of("email", email, "fullName", "  Người phỏng vấn mới  ",
                "roles", List.of("INTERVIEWER", "HIRING_MANAGER")), token);
    }

    private HttpResponse<String> activate(String token, String bearer) throws Exception {
        return post("/api/v1/auth/activate-account", Map.of("token", token), bearer);
    }

    private JsonNode login(String email, String password) throws Exception {
        var response = post("/api/v1/auth/login", Map.of("email", email, "password", password), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response);
    }

    private HttpResponse<String> post(String path, Map<String, ?> content, String token) throws Exception {
        return request("POST", path, json.writeValueAsString(content), token);
    }

    private HttpResponse<String> request(String method, String path, String content, String token) throws Exception {
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(content));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    private String activationToken(String content) {
        var matcher = Pattern.compile("http://localhost:5173/activate-account\\?token=([A-Za-z0-9_-]{43})").matcher(content);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String temporaryPassword(String content) {
        var matcher = Pattern.compile("Mật khẩu tạm: ([^\\r\\n]+)").matcher(content);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static LocalSmtpServer startSmtp() {
        try { return new LocalSmtpServer(); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
