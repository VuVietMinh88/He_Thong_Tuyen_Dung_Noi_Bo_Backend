package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "spring.mail.host=127.0.0.1", "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountLockIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    private static final LocalSmtpServer SMTP = startSmtp();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private ResetTokenGenerator generator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired @Qualifier("passwordResetExecutor") private ThreadPoolTaskExecutor mailExecutor;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;

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
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        SMTP.reset();
    }

    @AfterEach
    void finishMailJobs() throws Exception { awaitMailJobs(); }

    @AfterAll
    static void closeSmtp() throws IOException { SMTP.close(); }

    @Test
    void locksWithTrimmedReasonAndRevokesEverySessionWithoutChangingAccountData() throws Exception {
        UUID target = account("staff@example.test", Set.of(Role.RECRUITER, Role.INTERVIEWER));
        JsonNode first = login("staff@example.test");
        JsonNode second = login("staff@example.test");
        jdbc.update("UPDATE user_accounts SET phone = '0912345678', display_title = 'Recruiter' WHERE id = ?", target);
        Map<String, Object> before = accountData(target);

        var response = lock(target, "  Nghỉ việc  ", adminToken);
        JsonNode result = ok(response);
        assertThat(result.size()).isEqualTo(6);
        assertThat(result.path("userId").asText()).isEqualTo(target.toString());
        assertThat(result.path("status").asText()).isEqualTo("ADMINISTRATIVELY_LOCKED");
        assertThat(result.path("lockReason").asText()).isEqualTo("Nghỉ việc");
        assertThat(Instant.parse(result.path("lockedAt").asText())).isEqualTo(START);
        assertThat(result.path("lockedBy").asText()).isEqualTo(adminId.toString());
        assertThat(result.path("handoverWarning").asText()).isNotBlank();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(accountData(target)).isEqualTo(before);
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.RECRUITER, Role.INTERVIEWER);
        assertNoActiveSessions(target);
        assertTokensRejected(first);
        assertTokensRejected(second);
        assertThat(loginResponse("staff@example.test").statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/profile", null, adminToken).statusCode()).isEqualTo(200);

        JsonNode detail = ok(request("GET", "/api/v1/accounts/" + target, null, adminToken));
        assertThat(detail.path("status").asText()).isEqualTo("ADMINISTRATIVELY_LOCKED");
        JsonNode page = ok(request("GET", "/api/v1/accounts?status=ADMINISTRATIVELY_LOCKED", null, adminToken));
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(target.toString());
    }

    @Test
    void repeatedLockKeepsOriginalReasonActorAndTimeButInvalidatesNewResetLinks() throws Exception {
        UUID target = account("repeat@example.test", Set.of(Role.INTERVIEWER));
        account("secondadmin@example.test", Set.of(Role.ADMIN));
        String secondAdminToken = login("secondadmin@example.test").path("accessToken").asText();
        JsonNode original = ok(lock(target, "Original reason", adminToken));
        String resetToken = resetToken(target);
        clock.set(START.plusSeconds(60));

        assertThat(ok(lock(target, "Different reason", secondAdminToken))).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_tokens WHERE token_hash = ?",
                Timestamp.class, generator.hash(resetToken)).toInstant()).isEqualTo(START.plusSeconds(60));
        assertNoActiveSessions(target);
    }

    @Test
    void unlockingRestoresNewLoginButNeverRevivesOldSessionsAndIsIdempotent() throws Exception {
        UUID target = account("unlock@example.test", Set.of(Role.HR_MANAGER));
        JsonNode oldTokens = login("unlock@example.test");
        ok(lock(target, "Temporary administrative hold", adminToken));

        for (int attempt = 0; attempt < 2; attempt++) {
            var response = unlock(target, adminToken);
            JsonNode result = ok(response);
            assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(result.path("lockReason").isNull()).isTrue();
            assertThat(result.path("lockedBy").isNull()).isTrue();
            assertThat(result.path("lockedAt").isNull()).isTrue();
            assertThat(result.path("handoverWarning").isNull()).isTrue();
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertTokensRejected(oldTokens);
        }
        JsonNode freshTokens = login("unlock@example.test");
        assertThat(request("GET", "/api/v1/auth/me", null, freshTokens.path("accessToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void pendingInvitationCannotActivateWhileLockedAndIsStillUsableAfterUnlock() throws Exception {
        UUID target = accounts.saveAndFlush(Account.pendingActivation("pending@example.test", "Pending",
                fixturePasswordHash, Set.of(Role.INTERVIEWER), START)).getId();
        String token = generator.create();
        jdbc.update("""
                INSERT INTO account_activation_tokens (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, generator.hash(token), target, Timestamp.from(START), Timestamp.from(START.plus(Duration.ofDays(1))));
        Map<String, Object> invitation = jdbc.queryForMap("SELECT * FROM account_activation_tokens WHERE user_id = ?", target);

        ok(lock(target, "Verify employee identity", adminToken));
        var activation = post("/api/v1/auth/activate-account", Map.of("token", token), null);
        assertThat(activation.statusCode()).isEqualTo(400);
        assertThat(body(activation).path("code").asText()).isEqualTo("ACTIVATION_TOKEN_INVALID");
        assertThat(jdbc.queryForMap("SELECT * FROM account_activation_tokens WHERE user_id = ?", target)).isEqualTo(invitation);
        assertThat(accounts.findById(target).orElseThrow().isEnabled()).isFalse();
        assertThat(ok(unlock(target, adminToken)).path("status").asText()).isEqualTo("PENDING_ACTIVATION");
        assertThat(loginResponse("pending@example.test").statusCode()).isEqualTo(401);
        ok(post("/api/v1/auth/activate-account", Map.of("token", token), null));
        login("pending@example.test");
    }

    @Test
    void unlockDoesNotEnableAnOtherwiseDisabledAccount() throws Exception {
        UUID target = account("disabled@example.test", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", target);
        ok(lock(target, "Disabled account review", adminToken));
        assertThat(ok(unlock(target, adminToken)).path("status").asText()).isEqualTo("DISABLED");
        assertThat(accounts.findById(target).orElseThrow().isEnabled()).isFalse();
        assertThat(loginResponse("disabled@example.test").statusCode()).isEqualTo(401);
    }

    @Test
    void unlockDoesNotClearBruteForceLockOrFailedAttemptCounter() throws Exception {
        UUID target = account("bruteforce@example.test", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET failed_login_attempts = 5, locked_until = ? WHERE id = ?",
                Timestamp.from(START.plus(Duration.ofMinutes(15))), target);
        Map<String, Object> before = accountData(target);
        ok(lock(target, "Manual hold", adminToken));
        assertThat(ok(unlock(target, adminToken)).path("status").asText()).isEqualTo("TEMPORARILY_LOCKED");
        assertThat(accountData(target)).isEqualTo(before);
        assertThat(loginResponse("bruteforce@example.test").statusCode()).isEqualTo(401);
        clock.set(START.plus(Duration.ofMinutes(15)));
        login("bruteforce@example.test");
    }

    @Test
    void resetCannotBypassAdministrativeLockAndForgotPasswordDoesNotRevealIt() throws Exception {
        UUID target = account("reset@example.test", Set.of(Role.RECRUITER));
        String token = resetToken(target);
        ok(lock(target, "Security investigation", adminToken));
        var reset = post("/api/v1/auth/reset-password", Map.of("token", token, "newPassword", "ChangedOnly456!"), null);
        assertThat(reset.statusCode()).isEqualTo(400);
        assertThat(body(reset).path("code").asText()).isEqualTo("RESET_TOKEN_INVALID");
        var locked = post("/api/v1/auth/forgot-password", Map.of("email", "reset@example.test"), null);
        var unknown = post("/api/v1/auth/forgot-password", Map.of("email", "unknown@example.test"), null);
        awaitMailJobs();
        assertThat(locked.statusCode()).isEqualTo(202);
        assertThat(body(locked)).isEqualTo(body(unknown));
        assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_tokens WHERE user_id = ?", Timestamp.class, target)).isNotNull();
        assertThat(accounts.findById(target).orElseThrow().getPasswordHash()).isEqualTo(fixturePasswordHash);
        ok(unlock(target, adminToken));
        assertThat(post("/api/v1/auth/reset-password", Map.of("token", token, "newPassword", "ChangedOnly456!"), null).statusCode()).isEqualTo(400);
        login("reset@example.test");
    }

    @Test
    void rejectsSelfLockWithoutRevokingSessionAndAllowsHarmlessSelfUnlock() throws Exception {
        var response = lock(adminId, "Accidental self lock", adminToken);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(body(response).path("code").asText()).isEqualTo("SELF_ACCOUNT_LOCK");
        assertThat(ok(unlock(adminId, adminToken)).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(request("GET", "/api/v1/auth/me", null, adminToken).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"  \\t  \"}"})
    void requiresANonblankReason(String payload) throws Exception {
        UUID target = account("invalid@example.test", Set.of(Role.INTERVIEWER));
        var response = request("PUT", lockPath(target), payload, adminToken);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT admin_locked_at FROM user_accounts WHERE id = ?", Timestamp.class, target)).isNull();
    }

    @Test
    void acceptsFiveHundredReasonCharactersAndRejectsLongerOrUnknownFields() throws Exception {
        UUID target = account("boundary@example.test", Set.of(Role.INTERVIEWER));
        var tooLong = lock(target, "x".repeat(501), adminToken);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(body(tooLong).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        var unknownField = request("PUT", lockPath(target), "{\"reason\":\"Review\",\"enabled\":false}", adminToken);
        assertThat(unknownField.statusCode()).isEqualTo(400);
        assertThat(body(unknownField).path("code").asText()).isEqualTo("INVALID_JSON");
        assertThat(ok(lock(target, "x".repeat(500), adminToken)).path("lockReason").asText()).hasSize(500);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void otherRolesCannotLockOrUnlock(Role role) throws Exception {
        account("nonadmin@example.test", Set.of(role));
        String token = login("nonadmin@example.test").path("accessToken").asText();
        assertThat(lock(adminId, "Unauthorized", token).statusCode()).isEqualTo(403);
        assertThat(unlock(adminId, token).statusCode()).isEqualTo(403);
    }

    @Test
    void bothCurrentAdminRoleAndCurrentWritePermissionAreRequired() throws Exception {
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        try {
            for (String token : List.of(hrToken, adminToken)) {
                assertThat(lock(adminId, "Unauthorized", token).statusCode()).isEqualTo(403);
                assertThat(unlock(adminId, token).statusCode()).isEqualTo(403);
            }
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
            restoreAdminWritePermission();
        }
    }

    @Test
    void anonymousAndRevokedSessionsCannotLockOrUnlock() throws Exception {
        assertThat(lock(adminId, "Anonymous", null).statusCode()).isEqualTo(401);
        assertThat(unlock(adminId, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
        assertThat(lock(adminId, "Revoked", adminToken).statusCode()).isEqualTo(401);
        assertThat(unlock(adminId, adminToken).statusCode()).isEqualTo(401);
    }

    @Test
    void missingAccountIs404AndMalformedIdentifierIs400ForBothOperations() throws Exception {
        for (String method : List.of("PUT", "DELETE")) {
            String payload = method.equals("PUT") ? "{\"reason\":\"Review\"}" : null;
            var missing = request(method, lockPath(UUID.randomUUID()), payload, adminToken);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(body(missing).path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(request(method, "/api/v1/accounts/not-a-uuid/lock", payload, adminToken).statusCode()).isEqualTo(400);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-role", "lost-permission", "locked-actor", "revoked-session", "expired-jwt"})
    void waitingRequestRechecksActorAuthorizationBeforeChangingTarget(String change) throws Exception {
        UUID target = account("waiting@example.test", Set.of(Role.INTERVIEWER));
        // Keep the FK's lock owner outside the request's actor/target pair to avoid a fixture-only deadlock.
        UUID lockOwner = account("waitlockowner@example.test", Set.of(Role.ADMIN));
        try {
            var response = whileActorLocked(target, connection -> {
                switch (change) {
                    case "lost-role" -> update(connection,
                            "UPDATE user_roles SET role = 'HR_MANAGER' WHERE user_id = ? AND role = 'ADMIN'", adminId);
                    case "lost-permission" -> update(connection,
                            "DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
                    case "locked-actor" -> update(connection,
                            "UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), lockOwner, adminId);
                    case "revoked-session" -> update(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
                    case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                    default -> throw new AssertionError("Unknown invalidation");
                }
            });
            assertThat(response.statusCode()).isEqualTo(change.startsWith("lost-") ? 403 : 401);
            assertThat(jdbc.queryForObject("SELECT admin_locked_at FROM user_accounts WHERE id = ?", Timestamp.class, target)).isNull();
        } finally {
            restoreAdminWritePermission();
        }
    }

    @Test
    void administratorsLockingEachOtherConcurrentlyLeaveExactlyOneActiveAdministrator() throws Exception {
        UUID secondAdmin = account("crossadmin@example.test", Set.of(Role.ADMIN));
        String secondToken = login("crossadmin@example.test").path("accessToken").asText();
        UUID firstAccount = jdbc.queryForObject("SELECT id FROM user_accounts ORDER BY id LIMIT 1", UUID.class);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, firstAccount);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> lock(secondAdmin, "First admin", adminToken));
                var second = executor.submit(() -> lock(adminId, "Second admin", secondToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    assertThat(List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode()))
                            .containsExactlyInAnyOrder(200, 401);
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE admin_locked_at IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void administratorLockedWhileAccountCreationWaitsCannotCreateAccountOrSendInvitation(boolean unlockBeforeCommit) throws Exception {
        UUID secondAdmin = account("lockowner@example.test", Set.of(Role.ADMIN));
        String payload = json.writeValueAsString(Map.of("email", "nevercreated@example.test",
                "fullName", "Never created", "roles", List.of("INTERVIEWER")));
        var response = whileActorLocked("POST", "/api/v1/accounts", payload, connection -> {
            update(connection,
                    "UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                    Timestamp.from(START), secondAdmin, adminId);
            update(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
            if (unlockBeforeCommit) {
                update(connection,
                        "UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL WHERE id = ?",
                        adminId);
            }
        });
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(accounts.existsByEmail("nevercreated@example.test")).isFalse();
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test
    void concurrentLoginCannotLeaveAnActiveSessionAfterLockCommits() throws Exception {
        UUID target = account("loginrace@example.test", Set.of(Role.INTERVIEWER));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, target);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var login = executor.submit(() -> loginResponse("loginrace@example.test"));
                var lock = executor.submit(() -> lock(target, "Lock during login", adminToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    ok(lock.get(10, TimeUnit.SECONDS));
                    var result = login.get(10, TimeUnit.SECONDS);
                    assertThat(result.statusCode()).isIn(200, 401);
                    if (result.statusCode() == 200) { assertTokensRejected(body(result)); }
                    assertNoActiveSessions(target);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    void refreshQueuedBeforeLockCannotLeaveUsableRotatedTokensAfterLockCommits() throws Exception {
        UUID target = account("refreshrace@example.test", Set.of(Role.INTERVIEWER));
        JsonNode tokens = login("refreshrace@example.test");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM auth_sessions WHERE user_id = ? FOR UPDATE")) {
                statement.setObject(1, target);
                try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); blockerPid = row.getInt(1); }
            }
            try (var executor = Executors.newFixedThreadPool(2)) {
                var refresh = executor.submit(() -> post("/api/v1/auth/refresh",
                        Map.of("refreshToken", tokens.path("refreshToken").asText()), null));
                awaitWaiters(blockerPid, 1);
                var lock = executor.submit(() -> lock(target, "Lock during refresh", adminToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    var refreshed = refresh.get(10, TimeUnit.SECONDS);
                    ok(lock.get(10, TimeUnit.SECONDS));
                    assertThat(refreshed.statusCode()).isIn(200, 401);
                    if (refreshed.statusCode() == 200) { assertTokensRejected(body(refreshed)); }
                    assertTokensRejected(tokens);
                    assertNoActiveSessions(target);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private HttpResponse<String> whileActorLocked(UUID target, LockedChange change) throws Exception {
        return whileActorLocked("PUT", lockPath(target), "{\"reason\":\"Waiting request\"}", change);
    }

    private HttpResponse<String> whileActorLocked(String method, String path, String payload, LockedChange change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> request(method, path, payload, adminToken));
                try {
                    // The JWT filter has accepted the request before its service waits on this row lock.
                    awaitWaiters(blockerPid, 1);
                    assertThat(response.isDone()).isFalse();
                    change.apply(connection);
                    connection.commit();
                    return response.get(10, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private void awaitWaiters(int blockerPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    WITH RECURSIVE blocked(pid) AS (
                        SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                        UNION
                        SELECT activity.pid FROM pg_stat_activity activity
                        JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                    )
                    SELECT count(*) FROM pg_stat_activity activity JOIN blocked ON blocked.pid = activity.pid
                    WHERE activity.datname = current_database() AND activity.wait_event_type = 'Lock'
                    """, Integer.class, blockerPid);
            if (observed >= expected) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("HTTP requests must reach PostgreSQL locks before release").isGreaterThanOrEqualTo(expected);
    }

    private void update(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void restoreAdminWritePermission() {
        jdbc.update("""
                INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')
                ON CONFLICT DO NOTHING
                """);
    }

    private Map<String, Object> accountData(UUID id) {
        Map<String, Object> values = new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", id));
        values.remove("admin_locked_at");
        values.remove("admin_lock_reason");
        values.remove("admin_locked_by");
        return values;
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Lock test", fixturePasswordHash, roles, START)).getId();
    }

    private String resetToken(UUID id) {
        String token = generator.create();
        jdbc.update("""
                INSERT INTO password_reset_tokens (id, user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), id, generator.hash(token), Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(30))));
        return token;
    }

    private void assertNoActiveSessions(UUID id) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, id)).isZero();
    }

    private void assertTokensRejected(JsonNode tokens) throws Exception {
        assertThat(request("GET", "/api/v1/auth/me", null, tokens.path("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/auth/refresh", Map.of("refreshToken", tokens.path("refreshToken").asText()), null).statusCode()).isEqualTo(401);
    }

    private void awaitMailJobs() throws Exception { mailExecutor.submit(() -> { }).get(15, TimeUnit.SECONDS); }

    private String lockPath(UUID id) { return "/api/v1/accounts/" + id + "/lock"; }

    private HttpResponse<String> lock(UUID id, String reason, String token) throws Exception {
        return request("PUT", lockPath(id), json.writeValueAsString(Map.of("reason", reason)), token);
    }

    private HttpResponse<String> unlock(UUID id, String token) throws Exception { return request("DELETE", lockPath(id), null, token); }

    private JsonNode login(String email) throws Exception { return ok(loginResponse(email)); }

    private HttpResponse<String> loginResponse(String email) throws Exception {
        return post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), null);
    }

    private HttpResponse<String> post(String path, Map<String, String> payload, String token) throws Exception {
        return request("POST", path, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> request(String method, String path, String content, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return body(response);
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    private static LocalSmtpServer startSmtp() {
        try { return new LocalSmtpServer(); }
        catch (IOException exception) { throw new IllegalStateException("Cannot start local SMTP fixture", exception); }
    }

    @FunctionalInterface
    private interface LockedChange { void apply(Connection connection) throws Exception; }
}
