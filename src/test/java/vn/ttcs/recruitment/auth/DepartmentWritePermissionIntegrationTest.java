package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
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
import java.sql.SQLException;
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

// Task 198: only a caller whose roles currently grant ORGANIZATION_WRITE_ALL (by default ADMIN and HR_MANAGER)
// creates, updates or deletes departments. SecurityConfiguration checks the permission on the URL, and
// DepartmentService checks it again after every lock the write waits for, so a permission removed (or an access
// token that expired) during the wait is refused and nothing is written.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DepartmentWritePermissionIntegrationTest {
    private static final String BASE = "/api/v1/departments";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; only the token expiry cases move the clock.
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final long TREE_LOCK = 195196;

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID managerId;

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
        restoreDefaultWriteGrants();
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode adminLogin = login("admin@example.test");
        adminToken = adminLogin.path("accessToken").asText();
        UUID adminId = UUID.fromString(adminLogin.path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        managerId = account("manager@example.test", Set.of(Role.HIRING_MANAGER));
    }

    // role = null is an account without any role, which the account API allows (role-permission-matrix.md 7.4).
    // The caller manages the department in every case: managing a department gives no right to change it.
    @ParameterizedTest(name = "{0}")
    @NullSource
    @EnumSource(Role.class)
    void onlyRolesWithOrganizationWriteAllCreateUpdateAndDeleteDepartments(Role role) throws Exception {
        UUID caller = account("caller@example.test", role == null ? Set.of() : Set.of(role));
        String token = token("caller@example.test");
        UUID target = department("TARGET", caller);
        List<Map<String, Object>> before = departmentRows();

        // Reading needs ORGANIZATION_READ_ALL, which every internal role has. Without a role there is nothing.
        assertThat(request("GET", BASE + "/" + target, null, token).statusCode()).isEqualTo(role == null ? 403 : 200);

        var created = create(payload("NEW", caller), token);
        var updated = update(target, payload("RENAMED", caller), token);
        var deleted = delete(target, token);

        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            UUID createdId = UUID.fromString(expect(created, 201).path("id").asText());
            assertThat(expect(updated, 200).path("code").asText()).isEqualTo("RENAMED");
            assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
            // TARGET was renamed and then deleted; only the new department is left.
            assertThat(departmentRows()).singleElement().satisfies(row -> {
                assertThat(row.get("id")).isEqualTo(createdId);
                assertThat(row.get("code")).isEqualTo("NEW");
            });
        } else {
            forbidden(created);
            forbidden(updated);
            forbidden(deleted);
            assertThat(departmentRows()).isEqualTo(before);
        }
    }

    // The rule follows the role_permissions rows of the caller's roles, read again on every request, not the role
    // names: the same access tokens behave differently as soon as a grant is added or removed.
    @Test
    void writeAccessFollowsTheCurrentGrantsNotTheRoleName() throws Exception {
        account("head@example.test", Set.of(Role.HIRING_MANAGER));
        String headToken = token("head@example.test");
        UUID target = department("TARGET", managerId);
        forbidden(create(payload("HEAD", managerId), headToken));

        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HIRING_MANAGER', 'ORGANIZATION_WRITE_ALL')");
        UUID created = UUID.fromString(expect(create(payload("HEAD", managerId), headToken), 201).path("id").asText());
        expect(update(target, payload("RENAMED", managerId), headToken), 200);
        assertThat(delete(created, headToken).statusCode()).isEqualTo(204);

        revokeHrWriteGrant();
        List<Map<String, Object>> before = departmentRows();
        forbidden(create(payload("HR", managerId), hrToken));
        forbidden(update(target, payload("BY_HR", managerId), hrToken));
        forbidden(delete(target, hrToken));
        assertThat(departmentRows()).isEqualTo(before);
        // ORGANIZATION_READ_ALL is a separate grant, so HR still reads the organization chart.
        expect(request("GET", BASE + "/tree", null, hrToken), 200);
    }

    // The admin moves HR to RECRUITER through the account role API after HR's access token was issued. The same
    // token still reads but no longer writes; giving the role back restores the writes, again without a new login.
    @Test
    void roleChangesThroughTheAccountApiApplyToTheNextRequestOfTheSameToken() throws Exception {
        UUID target = department("TARGET", managerId);
        expect(request("PUT", "/api/v1/accounts/" + hrId + "/roles/RECRUITER", null, adminToken), 200);
        JsonNode roles = expect(request("DELETE", "/api/v1/accounts/" + hrId + "/roles/HR_MANAGER", null, adminToken), 200);
        assertThat(roles.path("roles").toString()).isEqualTo("[\"RECRUITER\"]");

        List<Map<String, Object>> before = departmentRows();
        forbidden(create(payload("NEW", managerId), hrToken));
        forbidden(update(target, payload("RENAMED", managerId), hrToken));
        forbidden(delete(target, hrToken));
        assertThat(departmentRows()).isEqualTo(before);
        expect(request("GET", BASE + "/" + target, null, hrToken), 200);

        expect(request("PUT", "/api/v1/accounts/" + hrId + "/roles/HR_MANAGER", null, adminToken), 200);
        assertThat(expect(update(target, payload("RENAMED", managerId), hrToken), 200).path("code").asText())
                .isEqualTo("RENAMED");
    }

    // A write that passed the URL check can still wait for a lock held by another transaction:
    // - account: the caller's account row, for example while the admin changes the caller's roles (that API locks it);
    // - tree: the department tree lock, held by another department write;
    // - department: the department row, held FOR SHARE while an account assignment or a requisition save of that
    //   department commits (only PUT and DELETE lock an existing department).
    // HR loses ORGANIZATION_WRITE_ALL during the wait, so the write is refused and nothing changes.
    @ParameterizedTest(name = "{0} waiting for the {1} lock")
    @CsvSource({
            "POST, account", "PUT, account", "DELETE, account",
            "POST, tree", "PUT, tree", "DELETE, tree",
            "PUT, department", "DELETE, department"
    })
    void writePermissionLostWhileWaitingForALockIsRefused(String method, String lock) throws Exception {
        UUID target = department("TARGET", managerId);
        List<Map<String, Object>> before = departmentRows();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = hold(connection, lock, target);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> write(method, target, hrToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (lock.equals("account")) {
                        // What DELETE /accounts/{id}/roles/HR_MANAGER does while it holds HR's account lock.
                        assertThat(execute(connection, "DELETE FROM user_roles WHERE user_id = ? AND role = 'HR_MANAGER'",
                                hrId)).isEqualTo(1);
                    } else {
                        // A migration that removes the grant from the role locks no account.
                        revokeHrWriteGrant();
                    }
                    connection.commit();
                    forbidden(response.get(10, TimeUnit.SECONDS));
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(departmentRows()).isEqualTo(before);
    }

    // The same wait for the department row. With nothing changed, the write goes on once the other transaction
    // commits. When the access token expired during the wait, the write is refused with 401 and nothing changes.
    @ParameterizedTest(name = "{0} after {1}")
    @CsvSource({"PUT, nothing", "DELETE, nothing", "PUT, token-expiry", "DELETE, token-expiry"})
    void aWriteWaitingForTheDepartmentRowChecksTheTokenAgain(String method, String change) throws Exception {
        UUID target = department("TARGET", managerId);
        List<Map<String, Object>> before = departmentRows();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = hold(connection, "department", target);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> write(method, target, hrToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (change.equals("token-expiry")) {
                        clock.set(START.plus(Duration.ofMinutes(15)));
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("nothing")) {
                        assertThat(result.statusCode()).as(result.body()).isEqualTo(method.equals("PUT") ? 200 : 204);
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        if (change.equals("token-expiry")) {
            assertThat(departmentRows()).isEqualTo(before);
        } else if (method.equals("PUT")) {
            assertThat(departmentRows()).singleElement().satisfies(row -> assertThat(row.get("code")).isEqualTo("RENAMED"));
        } else {
            assertThat(departmentRows()).isEmpty();
        }
    }

    // The opposite order: HR's PUT already holds HR's account lock and waits for the tree lock when the admin removes
    // HR_MANAGER through the API. The role change waits for the PUT, so the PUT (checked while HR still had the role)
    // completes, and HR's next request with the same token is refused.
    @Test
    void aRoleRemovalWaitsForTheWriteInProgressAndAppliesToTheNextRequest() throws Exception {
        UUID target = department("TARGET", managerId);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = hold(connection, "tree", target);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var edit = executor.submit(() -> update(target, payload("RENAMED", managerId), hrToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    var removal = executor.submit(() -> request("DELETE",
                            "/api/v1/accounts/" + hrId + "/roles/HR_MANAGER", null, adminToken));
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    expect(edit.get(10, TimeUnit.SECONDS), 200);
                    assertThat(expect(removal.get(10, TimeUnit.SECONDS), 200).path("roles").isEmpty()).isTrue();
                } finally {
                    connection.rollback();
                }
            }
        }
        forbidden(update(target, payload("AGAIN", managerId), hrToken));
        forbidden(delete(target, hrToken));
        assertThat(departmentRows()).singleElement().satisfies(row -> assertThat(row.get("code")).isEqualTo("RENAMED"));
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Permission test", fixturePasswordHash, roles, START)).getId();
    }

    // Created by the bootstrap admin, so the fixture does not depend on the caller under test.
    private UUID department(String code, UUID manager) throws Exception {
        return UUID.fromString(expect(create(payload(code, manager), adminToken), 201).path("id").asText());
    }

    private Map<String, Object> payload(String code, UUID manager) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", "Phòng " + code);
        result.put("parentId", null);
        result.put("managerUserId", manager);
        result.put("active", true);
        return result;
    }

    private List<Map<String, Object>> departmentRows() {
        return jdbc.queryForList("SELECT * FROM departments ORDER BY id");
    }

    private void revokeHrWriteGrant() {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
    }

    // The V3 grants of ORGANIZATION_WRITE_ALL: ADMIN and HR_MANAGER, nobody else.
    private void restoreDefaultWriteGrants() {
        jdbc.update("DELETE FROM role_permissions WHERE permission_code = 'ORGANIZATION_WRITE_ALL' AND role_code NOT IN ('ADMIN', 'HR_MANAGER')");
        jdbc.update("""
                INSERT INTO role_permissions (role_code, permission_code)
                VALUES ('ADMIN', 'ORGANIZATION_WRITE_ALL'), ('HR_MANAGER', 'ORGANIZATION_WRITE_ALL')
                ON CONFLICT DO NOTHING
                """);
    }

    private HttpResponse<String> write(String method, UUID target, String token) throws Exception {
        return switch (method) {
            case "POST" -> create(payload("NEW", managerId), token);
            case "PUT" -> update(target, payload("RENAMED", managerId), token);
            case "DELETE" -> delete(target, token);
            default -> throw new IllegalArgumentException("Unexpected method " + method);
        };
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private String token(String email) throws Exception {
        return login(email).path("accessToken").asText();
    }

    private HttpResponse<String> create(Map<String, Object> payload, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> update(UUID id, Map<String, Object> payload, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> delete(UUID id, String token) throws Exception {
        return request("DELETE", BASE + "/" + id, null, token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403);
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    // Takes, in the other connection, the lock that a write of the target department may have to wait for.
    private int hold(Connection connection, String lock, UUID department) throws SQLException {
        return switch (lock) {
            case "account" -> backendPid(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE", hrId);
            case "tree" -> backendPid(connection, "SELECT pg_backend_pid(), pg_advisory_xact_lock(?)", TREE_LOCK);
            case "department" -> backendPid(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR SHARE", department);
            default -> throw new IllegalArgumentException("Unexpected lock " + lock);
        };
    }

    private int backendPid(Connection connection, String sql, Object value) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            return statement.executeUpdate();
        }
    }

    // Counts the requests waiting, directly or through another waiting request, for the other connection's locks.
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
}
