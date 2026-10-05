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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        "app.cors.allowed-origins=http://localhost:5173",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountManagementIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");

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
        // Departments require a manager; clear account assignments before deleting departments.
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
    void searchesNameEmailAndDepartmentCaseInsensitively() throws Exception {
        UUID byName = account("name@example.test", "Nguyen Minh Search", Set.of(Role.INTERVIEWER));
        UUID byEmail = account("onlymail@example.test", "Other person", Set.of(Role.INTERVIEWER));
        UUID byDepartment = account("department@example.test", "Third person", Set.of(Role.INTERVIEWER));
        UUID department = department("ENG", "Engineering Platform", true);
        assignDepartment(byDepartment, department);

        assertThat(ids(search("  MINH SEARCH  "))).containsExactly(byName.toString());
        assertThat(ids(search("ONLYMAIL@"))).containsExactly(byEmail.toString());
        assertThat(ids(search("PLATFORM"))).containsExactly(byDepartment.toString());
    }

    @Test
    void searchTreatsWildcardAndSqlSyntaxAsLiteralInput() throws Exception {
        UUID literal = account("literal@example.test", "100%_Team\\Ops", Set.of(Role.INTERVIEWER));
        account("lookalike@example.test", "100xxTeamOps", Set.of(Role.INTERVIEWER));

        assertThat(ids(search("%_Team\\"))).containsExactly(literal.toString());
        assertThat(ids(search("' OR '1'='1"))).isEmpty();
        assertThat(accounts.count()).isEqualTo(3);
    }

    @Test
    void paginatesTwentyByDefaultWithStableOrderingAndNoMultiRoleDuplicates() throws Exception {
        for (int index = 0; index < 24; index++) {
            account("page" + index + "@example.test", "Page " + index,
                    Set.of(Role.INTERVIEWER, Role.HIRING_MANAGER));
        }
        UUID newest = account("newest@example.test", "Newest", Set.of(Role.INTERVIEWER));
        jdbc.update("UPDATE user_accounts SET created_at = ? WHERE id = ?", Timestamp.from(START.plusSeconds(1)), newest);
        List<String> expected = jdbc.query("SELECT id FROM user_accounts ORDER BY created_at DESC, id ASC",
                (row, number) -> row.getObject("id", UUID.class).toString());

        JsonNode first = ok(get("/api/v1/accounts", adminToken));
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(26);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(ids(ok(get("/api/v1/accounts", adminToken)))).containsExactlyElementsOf(ids(first));
        JsonNode second = ok(get("/api/v1/accounts?page=1", adminToken));
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(20, 26));
        JsonNode outside = ok(get("/api/v1/accounts?page=2", adminToken));
        assertThat(ids(outside)).isEmpty();
        assertThat(outside.path("totalElements").asLong()).isEqualTo(26);
    }

    @Test
    void combinesSearchRoleStatusAndDepartmentFilters() throws Exception {
        UUID target = account("match@example.test", "Shared needle", Set.of(Role.INTERVIEWER, Role.HIRING_MANAGER));
        UUID wrongRole = account("wrongrole@example.test", "Shared needle", Set.of(Role.RECRUITER));
        UUID wrongDepartment = account("wrongdepartment@example.test", "Shared needle", Set.of(Role.INTERVIEWER));
        UUID disabled = account("disabled@example.test", "Shared needle", Set.of(Role.INTERVIEWER));
        UUID department = department("ONE", "First department", true);
        UUID otherDepartment = department("TWO", "Second department", true);
        for (UUID id : List.of(target, wrongRole, disabled)) { assignDepartment(id, department); }
        assignDepartment(wrongDepartment, otherDepartment);
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", disabled);

        JsonNode response = ok(get("/api/v1/accounts?q=needle&role=INTERVIEWER&status=ACTIVE&departmentId="
                + department + "&page=0&size=1", adminToken));
        assertThat(ids(response)).containsExactly(target.toString());
        assertThat(response.path("totalElements").asLong()).isEqualTo(1);
        assertThat(response.path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void classifiesTemporaryLockAndPendingActivationWithoutConfusingDisabledAccounts() throws Exception {
        UUID locked = account("locked@example.test", "Locked", Set.of(Role.INTERVIEWER));
        UUID expiredLock = account("expiredlock@example.test", "Expired lock", Set.of(Role.INTERVIEWER));
        UUID boundaryLock = account("boundarylock@example.test", "Boundary lock", Set.of(Role.INTERVIEWER));
        UUID pending = account("pending@example.test", "Pending", Set.of(Role.INTERVIEWER));
        UUID expiredInvitation = account("expiredinvite@example.test", "Expired invitation", Set.of(Role.INTERVIEWER));
        UUID consumedInvitation = account("consumed@example.test", "Consumed invitation", Set.of(Role.INTERVIEWER));
        UUID disabled = account("disabled@example.test", "Disabled", Set.of(Role.INTERVIEWER));
        jdbc.update("UPDATE user_accounts SET locked_until = ? WHERE id = ?", Timestamp.from(START.plusSeconds(60)), locked);
        jdbc.update("UPDATE user_accounts SET locked_until = ? WHERE id = ?", Timestamp.from(START.minusSeconds(1)), expiredLock);
        jdbc.update("UPDATE user_accounts SET locked_until = ? WHERE id = ?", Timestamp.from(START), boundaryLock);
        for (UUID id : List.of(pending, expiredInvitation, consumedInvitation, disabled)) {
            jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", id);
        }
        invitation(pending, START.plusSeconds(3600), null);
        invitation(expiredInvitation, START.minusSeconds(1), null);
        invitation(consumedInvitation, START.plusSeconds(3600), START.minusSeconds(30));

        assertThat(ids(ok(get("/api/v1/accounts?status=TEMPORARILY_LOCKED", adminToken))))
                .containsExactly(locked.toString());
        assertThat(ids(ok(get("/api/v1/accounts?status=PENDING_ACTIVATION", adminToken))))
                .containsExactlyInAnyOrder(pending.toString(), expiredInvitation.toString());
        assertThat(ids(ok(get("/api/v1/accounts?status=DISABLED", adminToken))))
                .containsExactlyInAnyOrder(disabled.toString(), consumedInvitation.toString());
        assertThat(ids(ok(get("/api/v1/accounts?status=ACTIVE", adminToken))))
                .containsExactlyInAnyOrder(adminId.toString(), expiredLock.toString(), boundaryLock.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=abc", "page=2147483648", "size=0", "size=101",
            "size=abc", "role=UNKNOWN", "status=UNKNOWN", "departmentId=not-a-uuid"})
    void rejectsInvalidQueryParameters(String query) throws Exception {
        assertThat(get("/api/v1/accounts?" + query, adminToken).statusCode()).isEqualTo(400);
    }

    @Test
    void unknownDepartmentFilterReturnsAnEmptyPageAndMaximumPageSizeIsSupported() throws Exception {
        JsonNode empty = ok(get("/api/v1/accounts?departmentId=" + UUID.randomUUID() + "&size=100", adminToken));
        assertThat(ids(empty)).isEmpty();
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(empty.path("size").asInt()).isEqualTo(100);
    }

    @Test
    void listAndDetailExposeOnlyAccountFieldsAndCannotBeCached() throws Exception {
        UUID target = account("safe@example.test", "Safe person", Set.of(Role.INTERVIEWER));
        UUID department = department("SAFE", "Safe department", true);
        assignDepartment(target, department);
        jdbc.update("UPDATE user_accounts SET phone = ?, display_title = ? WHERE id = ?", "0912345678", "Developer", target);
        login("safe@example.test");

        for (String path : List.of("/api/v1/accounts", "/api/v1/accounts/" + target)) {
            var response = get(path, adminToken);
            JsonNode result = ok(response);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(response.body()).doesNotContain("password", "Password", "Hash", "refreshToken", "accessToken",
                    "failedLoginAttempts", "lockedUntil", fixturePasswordHash);
            if (path.endsWith(target.toString())) {
                assertThat(result.path("id").asText()).isEqualTo(target.toString());
                assertThat(result.path("email").asText()).isEqualTo("safe@example.test");
                assertThat(result.path("fullName").asText()).isEqualTo("Safe person");
                assertThat(result.path("phone").asText()).isEqualTo("0912345678");
                assertThat(result.path("displayTitle").asText()).isEqualTo("Developer");
                assertThat(result.path("departmentId").asText()).isEqualTo(department.toString());
                assertThat(result.path("departmentName").asText()).isEqualTo("Safe department");
                assertThat(result.path("roles").toString()).contains("INTERVIEWER");
                assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
                assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
            }
        }
    }

    @Test
    void missingAccountReturns404ForDetailAndUpdateWhileMalformedIdReturns400() throws Exception {
        String path = "/api/v1/accounts/" + UUID.randomUUID();
        for (var response : List.of(get(path, adminToken), put(path, Map.of("fullName", "New name"), adminToken))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(body(response).path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        }
        assertThat(get("/api/v1/accounts/not-a-uuid", adminToken).statusCode()).isEqualTo(400);
        assertThat(put("/api/v1/accounts/not-a-uuid", Map.of("fullName", "New name"), adminToken).statusCode())
                .isEqualTo(400);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "HR_MANAGER"}, mode = EnumSource.Mode.EXCLUDE)
    void ordinaryInternalRolesCannotReadOrUpdateAccounts(Role role) throws Exception {
        account("staff@example.test", "Staff", Set.of(role));
        String token = login("staff@example.test").path("accessToken").asText();
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/accounts/" + adminId, token).statusCode()).isEqualTo(403);
        assertThat(update(adminId, Map.of("fullName", "Changed"), token).statusCode()).isEqualTo(403);
    }

    @Test
    void hrManagerCanReadButCannotWriteEvenIfGrantedWritePermissionWithoutAdminRole() throws Exception {
        account("hr@example.test", "HR", Set.of(Role.HR_MANAGER));
        String token = login("hr@example.test").path("accessToken").asText();
        assertThat(get("/api/v1/accounts", token).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/accounts/" + adminId, token).statusCode()).isEqualTo(200);
        assertThat(update(adminId, Map.of("fullName", "Changed"), token).statusCode()).isEqualTo(403);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        try {
            assertThat(update(adminId, Map.of("fullName", "Changed"), token).statusCode()).isEqualTo(403);
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        }
    }

    @Test
    void currentRoleAndPermissionChangesApplyToAnAlreadyIssuedAccessToken() throws Exception {
        String originalName = accounts.findById(adminId).orElseThrow().getFullName();
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        try {
            assertThat(update(adminId, Map.of("fullName", "Changed"), adminToken).statusCode()).isEqualTo(403);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");
        }
        jdbc.update("UPDATE user_roles SET role = 'HR_MANAGER' WHERE user_id = ? AND role = 'ADMIN'", adminId);
        assertThat(get("/api/v1/accounts", adminToken).statusCode()).isEqualTo(200);
        assertThat(update(adminId, Map.of("fullName", "Changed"), adminToken).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE user_roles SET role = 'INTERVIEWER' WHERE user_id = ? AND role = 'HR_MANAGER'", adminId);
        assertThat(get("/api/v1/accounts", adminToken).statusCode()).isEqualTo(403);
        assertThat(accounts.findById(adminId).orElseThrow().getFullName()).isEqualTo(originalName);
    }

    @Test
    void losingAdminWhileUpdateWaitsForAccountLockIsRejectedWithoutChangingTarget() throws Exception {
        UUID target = account("concurrent@example.test", "Original name", Set.of(Role.INTERVIEWER));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var lock = connection.prepareStatement("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, adminId);
                try (var row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    blockerPid = row.getInt(1);
                }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var update = executor.submit(() -> update(target, Map.of("fullName", "Must not persist"), adminToken));
                try {
                    // The filter has accepted the JWT; PostgreSQL proves that the service is now waiting.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean waitingForActor = false;
                    while (System.nanoTime() < deadline) {
                        waitingForActor = Boolean.TRUE.equals(jdbc.queryForObject("""
                                SELECT EXISTS (
                                    SELECT 1 FROM pg_stat_activity
                                    WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock'
                                      AND query LIKE '%user_accounts%'
                                )
                                """, Boolean.class, blockerPid));
                        if (waitingForActor) { break; }
                        Thread.sleep(20);
                    }
                    assertThat(waitingForActor).as("PUT must reach the account lock before the role changes").isTrue();
                    assertThat(update.isDone()).isFalse();
                    try (var revoke = connection.prepareStatement(
                            "UPDATE user_roles SET role = 'HR_MANAGER' WHERE user_id = ? AND role = 'ADMIN'")) {
                        revoke.setObject(1, adminId);
                        assertThat(revoke.executeUpdate()).isEqualTo(1);
                    }
                    connection.commit();
                    assertThat(update.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(403);
                    assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", target)).isEqualTo(before);
                } finally {
                    // Release the row lock even if the observer/assertion fails before commit.
                    connection.rollback();
                }
            }
        }
    }

    @Test
    void absentAuthenticationOrRevokedSessionCannotReadOrUpdate() throws Exception {
        for (String path : List.of("/api/v1/accounts", "/api/v1/accounts/" + adminId)) {
            assertThat(get(path, null).statusCode()).isEqualTo(401);
        }
        assertThat(update(adminId, Map.of("fullName", "Changed"), null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
        assertThat(get("/api/v1/accounts", adminToken).statusCode()).isEqualTo(401);
        assertThat(update(adminId, Map.of("fullName", "Changed"), adminToken).statusCode()).isEqualTo(401);
    }

    @Test
    void adminUpdatesEditableFieldsWithoutChangingIdentityCredentialsRolesOrSessions() throws Exception {
        UUID target = account("edit@example.test", "Old name", Set.of(Role.INTERVIEWER, Role.HIRING_MANAGER));
        String targetToken = login("edit@example.test").path("accessToken").asText();
        UUID department = department("EDIT", "Updated department", true);
        Map<String, Object> identityBefore = jdbc.queryForMap("""
                SELECT id, email, password_hash, enabled, failed_login_attempts, locked_until, created_at
                FROM user_accounts WHERE id = ?
                """, target);
        List<Map<String, Object>> sessionsBefore = jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id");

        var response = update(target, Map.of("fullName", "  Người đã cập nhật  ", "phone", "+84912345678",
                "displayTitle", "  Backend Developer  ", "departmentId", department.toString()), adminToken);
        JsonNode result = ok(response);
        assertThat(result.path("fullName").asText()).isEqualTo("Người đã cập nhật");
        assertThat(result.path("phone").asText()).isEqualTo("0912345678");
        assertThat(result.path("displayTitle").asText()).isEqualTo("Backend Developer");
        assertThat(result.path("departmentId").asText()).isEqualTo(department.toString());
        assertThat(result.path("departmentName").asText()).isEqualTo("Updated department");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(jdbc.queryForMap("""
                SELECT id, email, password_hash, enabled, failed_login_attempts, locked_until, created_at
                FROM user_accounts WHERE id = ?
                """, target)).isEqualTo(identityBefore);
        assertThat(accounts.findById(target).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.INTERVIEWER, Role.HIRING_MANAGER);
        assertThat(jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id")).isEqualTo(sessionsBefore);
        assertThat(get("/api/v1/auth/me", targetToken).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/accounts/" + target, adminToken).body()).contains("Người đã cập nhật");
    }

    @Test
    void nullableFieldsCanBeClearedWithoutAffectingRequiredName() throws Exception {
        UUID target = account("clear@example.test", "Before", Set.of(Role.INTERVIEWER));
        UUID department = department("CLEAR", "Clear department", true);
        assertThat(update(target, Map.of("fullName", "Before", "phone", "0912345678", "displayTitle", "Developer",
                "departmentId", department.toString()), adminToken).statusCode()).isEqualTo(200);
        Map<String, Object> cleared = new LinkedHashMap<>();
        cleared.put("fullName", "  After  ");
        cleared.put("phone", null);
        cleared.put("displayTitle", null);
        cleared.put("departmentId", null);

        JsonNode response = ok(update(target, cleared, adminToken));
        assertThat(response.path("fullName").asText()).isEqualTo("After");
        for (String field : List.of("phone", "displayTitle", "departmentId", "departmentName")) {
            assertThat(response.path(field).isNull()).as(field).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id = ?", UUID.class, target)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "0351234567", "02412345678", "+842412345678"})
    void acceptsVietnameseMobileAndLandlineNumbers(String phone) throws Exception {
        JsonNode result = ok(update(adminId, Map.of("fullName", "Admin", "phone", phone), adminToken));
        assertThat(result.path("phone").asText()).isEqualTo(phone.startsWith("+84") ? "0" + phone.substring(3) : phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123456789", "091234567", "09123456789", "+12025550123", "abc", "0912 345 678"})
    void rejectsInvalidPhoneWithoutPartialUpdate(String phone) throws Exception {
        String before = accounts.findById(adminId).orElseThrow().getFullName();
        assertThat(update(adminId, Map.of("fullName", "Changed", "phone", phone), adminToken).statusCode()).isEqualTo(400);
        assertThat(accounts.findById(adminId).orElseThrow().getFullName()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{", "{\"fullName\":null}", "{\"fullName\":\"  \"}",
            "{\"fullName\":\"Changed\",\"email\":\"new@example.test\"}",
            "{\"fullName\":\"Changed\",\"roles\":[\"ADMIN\"]}",
            "{\"fullName\":\"Changed\",\"enabled\":false}",
            "{\"fullName\":\"Changed\",\"passwordHash\":\"replacement\"}",
            "{\"fullName\":\"Changed\",\"unrecognized\":\"value\"}",
            "{\"fullName\":\"Changed\",\"departmentId\":\"invalid\"}"
    })
    void rejectsInvalidJsonAndProtectedOrUnknownFieldsWithoutChangingAccount(String content) throws Exception {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", adminId);
        var response = request("PUT", "/api/v1/accounts/" + adminId, content, adminToken);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", adminId)).isEqualTo(before);
        assertThat(accounts.findById(adminId).orElseThrow().getRoles()).containsExactly(Role.ADMIN);
    }

    @Test
    void rejectsOverlongNameAndDisplayTitle() throws Exception {
        assertThat(update(adminId, Map.of("fullName", "a".repeat(256)), adminToken).statusCode()).isEqualTo(400);
        assertThat(update(adminId, Map.of("fullName", "Admin", "displayTitle", "a".repeat(121)), adminToken).statusCode())
                .isEqualTo(400);
    }

    @Test
    void invalidOrInactiveDepartmentIsRejectedButExistingInactiveAssignmentCanBeRetained() throws Exception {
        UUID target = account("departmentedit@example.test", "Before", Set.of(Role.INTERVIEWER));
        UUID inactive = department("INACTIVE", "Inactive department", false);
        for (UUID invalidDepartment : List.of(UUID.randomUUID(), inactive)) {
            var rejected = update(target, Map.of("fullName", "Changed", "departmentId", invalidDepartment.toString()), adminToken);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(body(rejected).path("code").asText()).isEqualTo("INVALID_DEPARTMENT");
            assertThat(accounts.findById(target).orElseThrow().getFullName()).isEqualTo("Before");
        }
        assignDepartment(target, inactive);
        JsonNode retained = ok(update(target, Map.of("fullName", "Retained", "departmentId", inactive.toString()), adminToken));
        assertThat(retained.path("departmentId").asText()).isEqualTo(inactive.toString());
    }

    @Test
    void configuredFrontendCanPreflightPutButUntrustedOriginCannot() throws Exception {
        for (String origin : List.of("http://localhost:5173", "https://untrusted.example.test")) {
            var preflight = HttpRequest.newBuilder(uri("/api/v1/accounts/" + adminId))
                    .header("Origin", origin).header("Access-Control-Request-Method", "PUT")
                    .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(preflight, HttpResponse.BodyHandlers.ofString());
            if (origin.equals("http://localhost:5173")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("PUT");
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            } else {
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }

    private UUID account(String email, String name, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, name, fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, String name, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active) VALUES (?, ?, ?, ?, ?)",
                id, code, name, adminId, active);
        return id;
    }

    private void assignDepartment(UUID accountId, UUID departmentId) {
        jdbc.update("UPDATE user_accounts SET department_id = ? WHERE id = ?", departmentId, accountId);
    }

    private void invitation(UUID accountId, Instant expiresAt, Instant consumedAt) {
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("""
                INSERT INTO account_activation_tokens (token_hash, user_id, created_at, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?)
                """, hash, accountId, Timestamp.from(START.minus(Duration.ofDays(2))), Timestamp.from(expiresAt),
                consumedAt == null ? null : Timestamp.from(consumedAt));
    }

    private JsonNode search(String query) throws Exception {
        return ok(get("/api/v1/accounts?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8), adminToken));
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private JsonNode login(String email) throws Exception {
        return ok(request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return request("GET", path, null, token);
    }

    private HttpResponse<String> update(UUID id, Map<String, ?> content, String token) throws Exception {
        return put("/api/v1/accounts/" + id, content, token);
    }

    private HttpResponse<String> put(String path, Map<String, ?> content, String token) throws Exception {
        return request("PUT", path, json.writeValueAsString(content), token);
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

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return body(response);
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
}
