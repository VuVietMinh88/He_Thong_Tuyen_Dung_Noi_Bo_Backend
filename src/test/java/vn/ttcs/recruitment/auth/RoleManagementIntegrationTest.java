package vn.ttcs.recruitment.auth;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "app.cors.allowed-origins=http://localhost:5173",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoleManagementIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

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
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
    }

    @Test
    void grantsAndRevokesIndividualRolesWithoutDuplicatesOrReplacingOtherRoles() throws Exception {
        UUID target = account("multi@example.test", Set.of(Role.RECRUITER));
        String path = rolePath(target, "HR_MANAGER");

        for (int attempt = 0; attempt < 2; attempt++) {
            var response = request("PUT", path, null, adminToken);
            JsonNode result = ok(response);
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.path("userId").asText()).isEqualTo(target.toString());
            assertThat(strings(result.path("roles"))).containsExactly("HR_MANAGER", "RECRUITER");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, target))
                .isEqualTo(2);

        for (int attempt = 0; attempt < 2; attempt++) {
            var response = request("DELETE", path, null, adminToken);
            assertThat(strings(ok(response).path("roles"))).containsExactly("RECRUITER");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.RECRUITER);
    }

    @Test
    void sameAccessTokenSeesUnionOfRolesAndImmediatePermissionRemoval() throws Exception {
        UUID target = account("live@example.test", Set.of(Role.INTERVIEWER));
        String token = login("live@example.test").path("accessToken").asText();
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(403);

        ok(request("PUT", rolePath(target, "HR_MANAGER"), null, adminToken));
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(200);
        assertThat(strings(ok(get("/api/v1/auth/me", token)).path("roles")))
                .containsExactlyInAnyOrder("HR_MANAGER", "INTERVIEWER");
        assertThat(strings(ok(get("/api/v1/auth/permissions", token)).path("permissions")))
                .contains("USER_ADMIN_READ_ALL", "EVALUATIONS_WRITE_SCOPED")
                .doesNotContain("USER_ADMIN_WRITE_ALL");

        ok(request("DELETE", rolePath(target, "HR_MANAGER"), null, adminToken));
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(403);
        assertThat(strings(ok(get("/api/v1/auth/permissions", token)).path("permissions")))
                .contains("EVALUATIONS_WRITE_SCOPED").doesNotContain("USER_ADMIN_READ_ALL");
    }

    @Test
    void sameAccessTokenCanAdministerAfterPromotionAndCannotAfterDemotion() throws Exception {
        UUID promoted = account("promoted@example.test", Set.of(Role.INTERVIEWER));
        UUID target = account("target@example.test", Set.of(Role.INTERVIEWER));
        String token = login("promoted@example.test").path("accessToken").asText();
        String path = rolePath(target, "RECRUITER");
        assertThat(request("PUT", path, null, token).statusCode()).isEqualTo(403);

        ok(request("PUT", rolePath(promoted, "ADMIN"), null, adminToken));
        ok(request("PUT", path, null, token));
        ok(request("DELETE", rolePath(promoted, "ADMIN"), null, adminToken));
        assertThat(request("DELETE", path, null, token).statusCode()).isEqualTo(403);
        assertThat(accounts.findById(target).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.INTERVIEWER, Role.RECRUITER);
    }

    @Test
    void cannotRevokeOwnAdminEvenWhenAnotherAdministratorExists() throws Exception {
        account("otheradmin@example.test", Set.of(Role.ADMIN));
        ok(request("PUT", rolePath(adminId, "HR_MANAGER"), null, adminToken));

        var rejected = request("DELETE", rolePath(adminId, "ADMIN"), null, adminToken);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(body(rejected).path("code").asText()).isEqualTo("SELF_ADMIN_REVOCATION");
        assertThat(accounts.findById(adminId).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.ADMIN, Role.HR_MANAGER);

        assertThat(strings(ok(request("DELETE", rolePath(adminId, "HR_MANAGER"), null, adminToken)).path("roles")))
                .containsExactly("ADMIN");
        assertThat(strings(ok(request("PUT", rolePath(adminId, "ADMIN"), null, adminToken)).path("roles")))
                .containsExactly("ADMIN");
    }

    @Test
    void finalNonAdminRoleCanBeRemovedAndRestoredUsingTheExistingSession() throws Exception {
        UUID target = account("norole@example.test", Set.of(Role.INTERVIEWER));
        String token = login("norole@example.test").path("accessToken").asText();
        List<Map<String, Object>> sessionsBefore = jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id");

        assertThat(strings(ok(request("DELETE", rolePath(target, "INTERVIEWER"), null, adminToken)).path("roles")))
                .isEmpty();
        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/profile", token).statusCode()).isEqualTo(403);
        ok(request("PUT", rolePath(target, "RECRUITER"), null, adminToken));
        assertThat(get("/api/v1/profile", token).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id")).isEqualTo(sessionsBefore);
    }

    @Test
    void changesOnlyRoleAssignmentsAndPreservesProfileCredentialsLockAndSessions() throws Exception {
        UUID target = account("preserved@example.test", Set.of(Role.INTERVIEWER));
        login("preserved@example.test");
        jdbc.update("""
                UPDATE user_accounts SET phone = '0912345678', display_title = 'Developer',
                    failed_login_attempts = 5, locked_until = ? WHERE id = ?
                """, Timestamp.from(START.plusSeconds(900)), target);
        Map<String, Object> accountBefore = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target);
        List<Map<String, Object>> sessionsBefore = jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id");

        ok(request("PUT", rolePath(target, "APPROVER"), null, adminToken));
        ok(request("DELETE", rolePath(target, "INTERVIEWER"), null, adminToken));
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.APPROVER);

        ok(request("PUT", rolesPath(target), rolesBody(target, "HIRING_MANAGER"), adminToken));
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.HIRING_MANAGER);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target)).isEqualTo(accountBefore);
        assertThat(jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id")).isEqualTo(sessionsBefore);
    }

    @Test
    void canPreparePendingAccountRolesWithoutActivatingItOrChangingItsInvitation() throws Exception {
        UUID target = accounts.saveAndFlush(Account.pendingActivation("pending@example.test", "Pending",
                fixturePasswordHash, Set.of(Role.INTERVIEWER), START)).getId();
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("""
                INSERT INTO account_activation_tokens (token_hash, user_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, hash, target, Timestamp.from(START), Timestamp.from(START.plus(Duration.ofDays(1))));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target);
        Map<String, Object> invitation = jdbc.queryForMap("SELECT * FROM account_activation_tokens WHERE user_id = ?", target);

        ok(request("PUT", rolePath(target, "RECRUITER"), null, adminToken));
        ok(request("DELETE", rolePath(target, "INTERVIEWER"), null, adminToken));
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.RECRUITER);

        ok(request("PUT", rolesPath(target), rolesBody(target, "APPROVER", "INTERVIEWER"), adminToken));
        assertThat(accounts.findById(target).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.APPROVER, Role.INTERVIEWER);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target)).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT * FROM account_activation_tokens WHERE user_id = ?", target)).isEqualTo(invitation);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void nonAdministratorsCannotGrantOrRevokeRoles(Role role) throws Exception {
        account("staff@example.test", Set.of(role));
        String token = login("staff@example.test").path("accessToken").asText();
        for (String method : List.of("PUT", "DELETE")) {
            assertThat(request(method, rolePath(adminId, "RECRUITER"), null, token).statusCode()).isEqualTo(403);
        }
        assertThat(request("PUT", rolesPath(adminId), rolesBody(adminId, "RECRUITER"), token).statusCode()).isEqualTo(403);
        assertThat(accounts.findById(adminId).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
    }

    @Test
    void hrManagerCannotGiveItselfAdministratorRolesThroughTheSetEndpoint() throws Exception {
        UUID hr = account("hrself@example.test", Set.of(Role.HR_MANAGER));
        String token = login("hrself@example.test").path("accessToken").asText();
        List<Map<String, Object>> before = roleRows(hr);

        var rejected = request("PUT", rolesPath(hr), rolesBody(hr, "ADMIN", "HR_MANAGER"), token);
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(body(rejected).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(roleRows(hr)).isEqualTo(before);
    }

    @Test
    void writePermissionAloneDoesNotReplaceTheRequiredAdministratorRole() throws Exception {
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String token = login("hr@example.test").path("accessToken").asText();
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        try {
            for (String method : List.of("PUT", "DELETE")) {
                assertThat(request(method, rolePath(adminId, "RECRUITER"), null, token).statusCode()).isEqualTo(403);
            }
            assertThat(request("PUT", rolesPath(adminId), rolesBody(adminId, "RECRUITER"), token).statusCode()).isEqualTo(403);
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        }
        assertThat(accounts.findById(adminId).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
    }

    @Test
    void administratorMustAlsoHaveTheCurrentWritePermission() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        try {
            for (String method : List.of("PUT", "DELETE")) {
                assertThat(request(method, rolePath(adminId, "RECRUITER"), null, adminToken).statusCode()).isEqualTo(403);
            }
            assertThat(request("PUT", rolesPath(adminId), rolesBody(adminId, "ADMIN", "RECRUITER"), adminToken).statusCode())
                    .isEqualTo(403);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");
        }
        assertThat(accounts.findById(adminId).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
    }

    @Test
    void anonymousAndRevokedSessionsCannotChangeRoles() throws Exception {
        for (String method : List.of("PUT", "DELETE")) {
            assertThat(request(method, rolePath(adminId, "RECRUITER"), null, null).statusCode()).isEqualTo(401);
        }
        assertThat(request("PUT", rolesPath(adminId), rolesBody(adminId, "ADMIN", "RECRUITER"), null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
        for (String method : List.of("PUT", "DELETE")) {
            assertThat(request(method, rolePath(adminId, "RECRUITER"), null, adminToken).statusCode()).isEqualTo(401);
        }
        assertThat(request("PUT", rolesPath(adminId), rolesBody(adminId, "ADMIN", "RECRUITER"), adminToken).statusCode())
                .isEqualTo(401);
        assertThat(accounts.findById(adminId).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "SUPER_ADMIN", "admin"})
    void unknownExternalOrIncorrectlyCasedRolesAreRejected(String role) throws Exception {
        for (String method : List.of("PUT", "DELETE")) {
            assertThat(request(method, rolePath(adminId, role), null, adminToken).statusCode()).isEqualTo(400);
        }
        assertThat(accounts.findById(adminId).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
    }

    @Test
    void missingAccountsReturn404AndMalformedIdentifiersReturn400() throws Exception {
        for (String method : List.of("PUT", "DELETE")) {
            var absent = request(method, rolePath(UUID.randomUUID(), "RECRUITER"), null, adminToken);
            assertThat(absent.statusCode()).isEqualTo(404);
            assertThat(body(absent).path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(request(method, "/api/v1/accounts/not-a-uuid/roles/RECRUITER", null, adminToken).statusCode())
                    .isEqualTo(400);
        }
        UUID missing = UUID.randomUUID();
        var absent = request("PUT", rolesPath(missing), rolesBody(missing, "RECRUITER"), adminToken);
        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(body(absent).path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        var malformed = request("PUT", "/api/v1/accounts/not-a-uuid/roles", rolesBody(adminId, "RECRUITER"), adminToken);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(body(malformed).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "SET"})
    void losingAdminWhileWaitingForTheAccountLockCannotChangeTargetRoles(String method) throws Exception {
        UUID target = account("waiting@example.test", Set.of(Role.INTERVIEWER));
        String set = rolesBody(target, "RECRUITER");
        Callable<HttpResponse<String>> call = switch (method) {
            case "PUT" -> () -> request("PUT", rolePath(target, "RECRUITER"), null, adminToken);
            case "DELETE" -> () -> request("DELETE", rolePath(target, "INTERVIEWER"), null, adminToken);
            // The set endpoint drops INTERVIEWER and adds RECRUITER in the same request.
            default -> () -> request("PUT", rolesPath(target), set, adminToken);
        };
        var response = whileActorLocked(call, connection -> {
            try (var change = connection.prepareStatement(
                    "UPDATE user_roles SET role = 'HR_MANAGER' WHERE user_id = ? AND role = 'ADMIN'")) {
                change.setObject(1, adminId);
                assertThat(change.executeUpdate()).isEqualTo(1);
            }
        });
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.INTERVIEWER);
    }

    @Test
    void losingWritePermissionWhileWaitingIsRejectedBeforeGranting() throws Exception {
        UUID target = account("permissionwait@example.test", Set.of(Role.INTERVIEWER));
        try {
            var response = whileActorLocked("PUT", target, "RECRUITER", connection -> {
                try (var revoke = connection.prepareStatement(
                        "DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'")) {
                    assertThat(revoke.executeUpdate()).isEqualTo(1);
                }
            });
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.INTERVIEWER);
        } finally {
            jdbc.update("""
                    INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')
                    ON CONFLICT DO NOTHING
                    """);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked-session", "expired-session", "expired-jwt", "disabled-actor"})
    void authenticationInvalidatedWhileWaitingIsRecheckedBeforeGranting(String change) throws Exception {
        UUID target = account("authwait@example.test", Set.of(Role.INTERVIEWER));
        var response = whileActorLocked("PUT", target, "RECRUITER", connection -> {
            switch (change) {
                case "revoked-session" -> {
                    try (var update = connection.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?")) {
                        update.setTimestamp(1, Timestamp.from(START));
                        update.setObject(2, adminId);
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                }
                case "expired-session" -> {
                    try (var update = connection.prepareStatement("UPDATE auth_sessions SET expires_at = ? WHERE user_id = ?")) {
                        update.setTimestamp(1, Timestamp.from(START.plusSeconds(1)));
                        update.setObject(2, adminId);
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                    clock.set(START.plusSeconds(1));
                }
                case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                case "disabled-actor" -> {
                    try (var update = connection.prepareStatement("UPDATE user_accounts SET enabled = FALSE WHERE id = ?")) {
                        update.setObject(1, adminId);
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                }
                default -> throw new AssertionError("Unknown authentication change");
            }
        });
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.INTERVIEWER);
    }

    @Test
    void concurrentGrantsByDifferentAdministratorsPreserveBothRoleAssignments() throws Exception {
        UUID target = account("concurrent@example.test", Set.of(Role.INTERVIEWER));
        account("secondadmin@example.test", Set.of(Role.ADMIN));
        String secondToken = login("secondadmin@example.test").path("accessToken").asText();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, target);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> request("PUT", rolePath(target, "RECRUITER"), null, adminToken));
                var second = executor.submit(() -> request("PUT", rolePath(target, "APPROVER"), null, secondToken));
                try {
                    awaitAccountWaiters(blockerPid, 2);
                    connection.commit();
                    ok(first.get(10, TimeUnit.SECONDS));
                    ok(second.get(10, TimeUnit.SECONDS));
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(accounts.findById(target).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.INTERVIEWER, Role.RECRUITER, Role.APPROVER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "SET"})
    void simultaneousCrossDemotionsKeepOneAdministratorAndRejectTheNowUnauthorizedActor(String method) throws Exception {
        UUID secondAdmin = account("crossadmin@example.test", Set.of(Role.ADMIN));
        String secondToken = login("crossadmin@example.test").path("accessToken").asText();
        // Each administrator removes the other's ADMIN; the set endpoint leaves INTERVIEWER in its place.
        Callable<HttpResponse<String>> demoteSecond = demotion(method, secondAdmin, adminToken);
        Callable<HttpResponse<String>> demoteFirst = demotion(method, adminId, secondToken);
        // PostgreSQL's UUID ordering is the same ordering used by the repository's row-lock query.
        UUID firstAccount = jdbc.queryForObject("SELECT id FROM user_accounts ORDER BY id LIMIT 1", UUID.class);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, firstAccount);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(demoteSecond);
                var second = executor.submit(demoteFirst);
                try {
                    awaitAccountWaiters(blockerPid, 2);
                    connection.commit();
                    assertThat(List.of(first.get(10, TimeUnit.SECONDS).statusCode(), second.get(10, TimeUnit.SECONDS).statusCode()))
                            .containsExactlyInAnyOrder(200, 403);
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
    }

    @Test
    void replacesTheWholeRoleSetInOneRequestAndKeepsTheRowsOfUnchangedRoles() throws Exception {
        UUID target = account("replace@example.test", Set.of(Role.RECRUITER, Role.INTERVIEWER));
        login("replace@example.test");
        Map<String, Object> kept = roleRows(target).getFirst();
        Map<String, Object> accountBefore = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target);
        List<Map<String, Object>> sessionsBefore = jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id");

        var response = request("PUT", rolesPath(target), rolesBody(target, "INTERVIEWER", "APPROVER", "HR_MANAGER"), adminToken);
        JsonNode result = ok(response);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.path("userId").asText()).isEqualTo(target.toString());
        assertThat(strings(result.path("roles"))).containsExactly("APPROVER", "HR_MANAGER", "INTERVIEWER");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");

        assertThat(accounts.findById(target).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.APPROVER, Role.HR_MANAGER, Role.INTERVIEWER);
        // INTERVIEWER stays the same row: only RECRUITER is deleted and the two new roles are inserted.
        assertThat(kept.get("role")).isEqualTo("INTERVIEWER");
        assertThat(roleRows(target)).contains(kept);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target)).isEqualTo(accountBefore);
        assertThat(jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id")).isEqualTo(sessionsBefore);
    }

    @Test
    void sendingTheCurrentSetAgainChangesNoRoleRows() throws Exception {
        UUID target = account("unchanged@example.test", Set.of(Role.RECRUITER, Role.INTERVIEWER));
        List<Map<String, Object>> before = roleRows(target);

        // Order and repeated codes do not matter in either list: both are sets.
        var response = request("PUT", rolesPath(target),
                rolesBody(List.of("RECRUITER", "INTERVIEWER", "INTERVIEWER"), "RECRUITER", "INTERVIEWER", "RECRUITER"),
                adminToken);
        assertThat(strings(ok(response).path("roles"))).containsExactly("INTERVIEWER", "RECRUITER");
        assertThat(roleRows(target)).isEqualTo(before);
    }

    @Test
    void savingASetBuiltFromAnOutdatedViewIsRejectedWithoutUndoingTheOtherChange() throws Exception {
        UUID target = account("outdated@example.test", Set.of(Role.RECRUITER));
        account("otherwriter@example.test", Set.of(Role.ADMIN));
        String otherToken = login("otherwriter@example.test").path("accessToken").asText();
        // The page loads RECRUITER; another administrator then grants INTERVIEWER; the page saves RECRUITER + APPROVER.
        String loaded = rolesBody(target, "RECRUITER", "APPROVER");
        ok(request("PUT", rolePath(target, "INTERVIEWER"), null, otherToken));
        List<Map<String, Object>> before = roleRows(target);

        var rejected = request("PUT", rolesPath(target), loaded, adminToken);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
        assertThat(rejected.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode error = body(rejected);
        assertThat(error.path("code").asText()).isEqualTo("ACCOUNT_ROLES_CHANGED");
        assertThat(error.path("message").asText())
                .isEqualTo("Vai trò của tài khoản đã thay đổi (có thể người khác vừa sửa). Vui lòng tải lại trước khi lưu.");
        assertThat(roleRows(target)).isEqualTo(before);

        // After reloading, the same choice keeps the other administrator's grant unless the user removes it.
        String reloaded = rolesBody(target, "RECRUITER", "APPROVER", "INTERVIEWER");
        assertThat(strings(ok(request("PUT", rolesPath(target), reloaded, adminToken)).path("roles")))
                .containsExactly("APPROVER", "INTERVIEWER", "RECRUITER");
    }

    @Test
    void revocationCommittedWhileTheSaveWaitsForTheTargetLockIsNotUndone() throws Exception {
        UUID target = account("revokedwait@example.test", Set.of(Role.ADMIN, Role.HR_MANAGER));
        // The page loaded ADMIN + HR_MANAGER and adds INTERVIEWER; meanwhile another administrator revokes ADMIN.
        String loaded = rolesBody(target, "ADMIN", "HR_MANAGER", "INTERVIEWER");
        var response = whileLocked(target, () -> request("PUT", rolesPath(target), loaded, adminToken), connection -> {
            try (var revoke = connection.prepareStatement("DELETE FROM user_roles WHERE user_id = ? AND role = 'ADMIN'")) {
                revoke.setObject(1, target);
                assertThat(revoke.executeUpdate()).isEqualTo(1);
            }
        });
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(body(response).path("code").asText()).isEqualTo("ACCOUNT_ROLES_CHANGED");
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactly(Role.HR_MANAGER);
    }

    @Test
    void anAccountWithoutRolesIsGivenRolesWithAnEmptyExpectedSet() throws Exception {
        UUID target = account("emptyset@example.test", Set.of(Role.INTERVIEWER));
        ok(request("DELETE", rolePath(target, "INTERVIEWER"), null, adminToken));

        var stale = request("PUT", rolesPath(target), rolesBody(List.of("INTERVIEWER"), "RECRUITER"), adminToken);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(roleRows(target)).isEmpty();
        assertThat(strings(ok(request("PUT", rolesPath(target), rolesBody(List.of(), "RECRUITER"), adminToken))
                .path("roles"))).containsExactly("RECRUITER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"expectedRoles\":[\"INTERVIEWER\"]}",
            "{\"roles\":null,\"expectedRoles\":[\"INTERVIEWER\"]}",
            "{\"roles\":[],\"expectedRoles\":[\"INTERVIEWER\"]}",
            "{\"roles\":[null],\"expectedRoles\":[\"INTERVIEWER\"]}",
            "{\"roles\":[\"RECRUITER\",null],\"expectedRoles\":[\"INTERVIEWER\"]}"})
    void missingEmptyOrNullRolesAreRejectedWithoutChanges(String content) throws Exception {
        UUID target = account("required@example.test", Set.of(Role.INTERVIEWER));
        List<Map<String, Object>> before = roleRows(target);

        var response = request("PUT", rolesPath(target), content, adminToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode error = body(response);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").size()).isEqualTo(1);
        assertThat(error.path("fieldErrors").path("roles").asText()).isEqualTo("Tài khoản phải có ít nhất một vai trò.");
        assertThat(roleRows(target)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"roles\":[\"RECRUITER\"]}", "{\"roles\":[\"RECRUITER\"],\"expectedRoles\":null}",
            "{\"roles\":[\"RECRUITER\"],\"expectedRoles\":[null]}",
            "{\"roles\":[\"RECRUITER\"],\"expectedRoles\":[\"INTERVIEWER\",null]}"})
    void missingOrNullExpectedRolesAreRejectedWithoutChanges(String content) throws Exception {
        UUID target = account("expected@example.test", Set.of(Role.INTERVIEWER));
        List<Map<String, Object>> before = roleRows(target);

        var response = request("PUT", rolesPath(target), content, adminToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode error = body(response);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").size()).isEqualTo(1);
        assertThat(error.path("fieldErrors").path("expectedRoles").asText())
                .isEqualTo("Thiếu danh sách vai trò hiện tại của tài khoản đang xem.");
        assertThat(roleRows(target)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"roles\":[\"CANDIDATE\"]}", "{\"roles\":[\"SUPER_ADMIN\"]}",
            "{\"roles\":[\"RECRUITER\",\"admin\"]}", "{\"roles\":[0]}", "{\"roles\":[\"0\"]}",
            "{\"roles\":[true]}", "{\"roles\":\"RECRUITER\"}", "{\"roles\":[\"RECRUITER\"],\"enabled\":true}",
            "{\"roles\":[\"RECRUITER\"],\"userId\":null}", "{\"roles\":[\"RECRUITER\"]",
            "{\"roles\":[\"RECRUITER\"],\"expectedRoles\":[\"CANDIDATE\"]}",
            "{\"roles\":[\"RECRUITER\"],\"expectedRoles\":[4]}",
            "{\"roles\":[\"RECRUITER\"],\"expectedRoles\":\"INTERVIEWER\"}"})
    void unknownCodesPositionsAndUnknownFieldsAreInvalidJson(String content) throws Exception {
        UUID target = account("strict@example.test", Set.of(Role.INTERVIEWER));
        List<Map<String, Object>> before = roleRows(target);

        var response = request("PUT", rolesPath(target), content, adminToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(body(response).path("code").asText()).isEqualTo("INVALID_JSON");
        assertThat(roleRows(target)).isEqualTo(before);
    }

    @Test
    void cannotDropOwnAdminFromTheSetEvenWhenAddingRolesOrAnotherAdministratorExists() throws Exception {
        account("setadmin@example.test", Set.of(Role.ADMIN));
        List<Map<String, Object>> before = roleRows(adminId);

        for (String content : List.of(rolesBody(adminId, "HR_MANAGER", "RECRUITER"), rolesBody(adminId, "INTERVIEWER"))) {
            var rejected = request("PUT", rolesPath(adminId), content, adminToken);
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(rejected.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            JsonNode error = body(rejected);
            assertThat(error.path("code").asText()).isEqualTo("SELF_ADMIN_REVOCATION");
            assertThat(error.path("message").asText()).isEqualTo("Bạn không thể thu hồi vai trò ADMIN của chính mình.");
            assertThat(roleRows(adminId)).isEqualTo(before);
        }

        assertThat(strings(ok(request("PUT", rolesPath(adminId), rolesBody(adminId, "ADMIN", "HR_MANAGER"), adminToken))
                .path("roles"))).containsExactly("ADMIN", "HR_MANAGER");
        assertThat(strings(ok(request("PUT", rolesPath(adminId), rolesBody(adminId, "ADMIN"), adminToken)).path("roles")))
                .containsExactly("ADMIN");
    }

    @Test
    void administratorMayDropAnotherAdministratorsAdminWhoseTokenLosesItOnTheNextCall() throws Exception {
        UUID other = account("demoted@example.test", Set.of(Role.ADMIN, Role.INTERVIEWER));
        UUID target = account("bystander@example.test", Set.of(Role.INTERVIEWER));
        String otherToken = login("demoted@example.test").path("accessToken").asText();
        ok(request("PUT", rolesPath(target), rolesBody(target, "INTERVIEWER", "RECRUITER"), otherToken));

        assertThat(strings(ok(request("PUT", rolesPath(other), rolesBody(other, "INTERVIEWER"), adminToken)).path("roles")))
                .containsExactly("INTERVIEWER");
        assertThat(request("PUT", rolesPath(target), rolesBody(target, "INTERVIEWER"), otherToken).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/accounts", otherToken).statusCode()).isEqualTo(403);
        assertThat(accounts.findById(target).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.INTERVIEWER, Role.RECRUITER);
    }

    @Test
    void sameAccessTokenGetsThePermissionsOfTheReplacedSetOnTheNextCall() throws Exception {
        UUID target = account("switched@example.test", Set.of(Role.INTERVIEWER));
        String token = login("switched@example.test").path("accessToken").asText();
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(403);

        ok(request("PUT", rolesPath(target), rolesBody(target, "HR_MANAGER", "INTERVIEWER"), adminToken));
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(200);
        assertThat(strings(ok(get("/api/v1/auth/permissions", token)).path("permissions")))
                .contains("USER_ADMIN_READ_ALL", "EVALUATIONS_WRITE_SCOPED");

        ok(request("PUT", rolesPath(target), rolesBody(target, "APPROVER"), adminToken));
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(403);
        assertThat(strings(ok(get("/api/v1/auth/me", token)).path("roles"))).containsExactly("APPROVER");
        assertThat(strings(ok(get("/api/v1/auth/permissions", token)).path("permissions")))
                .contains("REQUISITIONS_READ_SCOPED").doesNotContain("USER_ADMIN_READ_ALL", "EVALUATIONS_WRITE_SCOPED");
    }

    @Test
    void configuredFrontendCanPreflightDeleteAndUntrustedOriginCannot() throws Exception {
        for (String origin : List.of("http://localhost:5173", "https://untrusted.example.test")) {
            var preflight = HttpRequest.newBuilder(uri(rolePath(adminId, "RECRUITER")))
                    .header("Origin", origin).header("Access-Control-Request-Method", "DELETE")
                    .header("Access-Control-Request-Headers", "Authorization")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(preflight, HttpResponse.BodyHandlers.ofString());
            if (origin.equals("http://localhost:5173")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("DELETE");
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            } else {
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }

    private HttpResponse<String> whileActorLocked(String method, UUID target, String role,
                                                  LockedChange change) throws Exception {
        return whileActorLocked(() -> request(method, rolePath(target, role), null, adminToken), change);
    }

    private HttpResponse<String> whileActorLocked(Callable<HttpResponse<String>> call, LockedChange change) throws Exception {
        return whileLocked(adminId, call, change);
    }

    private HttpResponse<String> whileLocked(UUID lockedAccount, Callable<HttpResponse<String>> call,
                                             LockedChange change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, lockedAccount);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var request = executor.submit(call);
                try {
                    // Observe the real PostgreSQL wait, so invalidation happens after the JWT filter accepted it.
                    awaitAccountWaiters(blockerPid, 1);
                    assertThat(request.isDone()).isFalse();
                    change.apply(connection);
                    connection.commit();
                    return request.get(10, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var lock = connection.prepareStatement("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, id);
            try (var row = lock.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private void awaitAccountWaiters(int blockerPid, int expected) throws Exception {
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
                    SELECT count(*) FROM pg_stat_activity activity
                    JOIN blocked ON blocked.pid = activity.pid
                    WHERE activity.datname = current_database() AND activity.wait_event_type = 'Lock'
                      AND activity.query LIKE '%user_accounts%'
                    """, Integer.class, blockerPid);
            if (observed >= expected) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("requests must reach the account locks before releasing them").isGreaterThanOrEqualTo(expected);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Role test", fixturePasswordHash, roles, START)).getId();
    }

    // Removes the ADMIN of an administrator who holds only ADMIN. The body is built now, before the call waits.
    private Callable<HttpResponse<String>> demotion(String method, UUID target, String token) throws Exception {
        if (method.equals("DELETE")) {
            return () -> request("DELETE", rolePath(target, "ADMIN"), null, token);
        }
        String set = rolesBody(target, "INTERVIEWER");
        return () -> request("PUT", rolesPath(target), set, token);
    }

    private String rolePath(UUID accountId, String role) {
        return "/api/v1/accounts/" + accountId + "/roles/" + role;
    }

    private String rolesPath(UUID accountId) {
        return "/api/v1/accounts/" + accountId + "/roles";
    }

    // What the edit page sends right after loading the account: the new set and the roles it holds now (none for an
    // account that does not exist).
    private String rolesBody(UUID accountId, String... roles) throws Exception {
        List<String> loaded = accounts.findById(accountId)
                .map(account -> account.getRoles().stream().map(Role::name).sorted().toList())
                .orElse(List.of());
        return rolesBody(loaded, roles);
    }

    private String rolesBody(List<String> expectedRoles, String... roles) throws Exception {
        return json.writeValueAsString(Map.of("roles", List.of(roles), "expectedRoles", expectedRoles));
    }

    // xmin is the transaction that wrote the row, so a row deleted and inserted again shows a new value.
    private List<Map<String, Object>> roleRows(UUID accountId) {
        return jdbc.queryForList("SELECT role, xmin::text AS version FROM user_roles WHERE user_id = ? ORDER BY role",
                accountId);
    }

    private JsonNode login(String email) throws Exception {
        return ok(request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return request("GET", path, null, token);
    }

    private HttpResponse<String> request(String method, String path, String content, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return body(response);
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    @FunctionalInterface
    private interface LockedChange {
        void apply(Connection connection) throws Exception;
    }
}
