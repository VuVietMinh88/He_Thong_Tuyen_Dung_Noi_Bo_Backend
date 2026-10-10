package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 284: what an assignment does when it has to wait for a lock, and when two HR users change the team at once.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionRecruiterLockIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private UUID adminId;
    private UUID hrId;
    private String hrToken;
    private String otherHrToken;
    private UUID requisitionId;
    private final List<UUID> r = new ArrayList<>();

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
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('HR_MANAGER', 'REQUISITIONS_WRITE_ALL') ON CONFLICT DO NOTHING");
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        account("hr2@example.test", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        otherHrToken = token("hr2@example.test");
        r.clear();
        for (int i = 0; i < 4; i++) {
            r.add(account("r" + i + "@example.test", Set.of(Role.RECRUITER)));
        }
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, 'IT', 'IT', ?, TRUE, ?)",
                departmentId, hrId, Timestamp.from(START));
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
        requisitionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 'NEW_HEADCOUNT', ?, ?, ?)
                """, requisitionId, positionId, departmentId, hrId, Timestamp.from(START), Timestamp.from(START));
    }

    // The request waits for the requisition row (a save of the requisition holds it), for the account of the person
    // assigned (a role change holds it) or for the caller's own account (an admin lock or a logout holds it, like
    // account administration). Whatever changed meanwhile is checked once the lock is granted. assign gives r0 the
    // primary role; unassign removes the supporting r1 from the team r0 + r1.
    @ParameterizedTest(name = "{0}: {1} held, then {2}")
    @CsvSource({
            "assign, requisition, nothing, 200", "assign, requisition, token-expired, 401",
            "assign, requisition, permission-lost, 403",
            "assign, assignee, nothing, 200", "assign, assignee, token-expired, 401", "assign, assignee, permission-lost, 403",
            "assign, actor, locked-actor, 401", "assign, actor, revoked-session, 401",
            "unassign, requisition, nothing, 200", "unassign, requisition, token-expired, 401",
            "unassign, requisition, permission-lost, 403",
            "unassign, actor, locked-actor, 401", "unassign, actor, revoked-session, 401"
    })
    void theCallerIsCheckedAgainAfterEachLockWait(String call, String held, String change, int expected)
            throws Exception {
        boolean removal = call.equals("unassign");
        if (removal) {
            expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
            expect(assign(Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        }
        int before = rows();
        String sql = held.equals("requisition")
                ? "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE"
                : "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE";
        UUID heldId = switch (held) {
            case "requisition" -> requisitionId;
            case "assignee" -> r.get(0);
            default -> hrId;
        };
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> removal
                        ? unassign(Map.of("recruiterId", r.get(1)), hrToken)
                        : assign(Map.of("recruiterId", r.get(0)), hrToken));
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                            + "AND permission_code = 'REQUISITIONS_WRITE_ALL'");
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, hrId);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), hrId);
                    default -> { }
                }
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(expected);
                if (expected == 401) {
                    assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("SESSION_INVALID");
                }
            }
        }
        // assign: nobody, then r0. unassign: r0 and r1, then r0.
        assertThat(rows()).isEqualTo(expected == 200 ? 1 : before);
    }

    @Test
    void anAssigneeWhoLosesTheRecruiterRoleWhileTheSaveWaitsIsRefused() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // A role change locks the account like this before it removes the role.
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR NO KEY UPDATE",
                    r.get(0));
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> assign(Map.of("recruiterId", r.get(0)), hrToken));
                awaitWaiters(blocker, 1);
                execute(connection, "DELETE FROM user_roles WHERE user_id = ? AND role = 'RECRUITER'", r.get(0));
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
                assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("INVALID_REQUISITION_RECRUITER");
            }
        }
        assertThat(rows()).isZero();
    }

    @Test
    void anAssigneeLockedByAnAdminWhileTheSaveWaitsIsRefused() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR NO KEY UPDATE",
                    r.get(0));
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> assign(Map.of("recruiterId", r.get(0)), hrToken));
                awaitWaiters(blocker, 1);
                execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', "
                        + "admin_locked_by = ? WHERE id = ?", Timestamp.from(START), adminId, r.get(0));
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
                assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("REQUISITION_RECRUITER_INACTIVE");
            }
        }
        assertThat(rows()).isZero();
    }

    @Test
    void twoHandoversAtOnceLeaveExactlyOnePrimary() throws Exception {
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
        var results = concurrently(
                () -> assign(Map.of("recruiterId", r.get(1)), hrToken),
                () -> assign(Map.of("recruiterId", r.get(2)), otherHrToken));
        assertThat(results).allMatch(status -> status == 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters WHERE assignment_role = 'PRIMARY'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT recruiter_id FROM requisition_recruiters", UUID.class))
                .isIn(r.get(1), r.get(2));
    }

    // The reason for commands (assign one person, remove one person) instead of saving the whole team: two HR users
    // adding different supporting recruiters at the same moment keep both.
    @Test
    void twoSupportingRecruitersAddedAtOnceAreBothKept() throws Exception {
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
        var results = concurrently(
                () -> assign(Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken),
                () -> assign(Map.of("recruiterId", r.get(2), "role", "SUPPORTING"), otherHrToken));
        assertThat(results).allMatch(status -> status == 200);
        assertThat(jdbc.queryForList("SELECT recruiter_id FROM requisition_recruiters WHERE assignment_role = 'SUPPORTING'",
                UUID.class)).containsExactlyInAnyOrder(r.get(1), r.get(2));
    }

    // One HR user removes the supporting r1 while another promotes r1 to primary. Whichever runs second sees the
    // first one's result: a removal first, then the promotion is a handover to r1; the promotion first, then removing
    // the new primary is 409. Either way r1 ends as the only recruiter.
    @Test
    void removingAndPromotingTheSameRecruiterAtOnceEndsWithThatRecruiterAsTheOnlyPrimary() throws Exception {
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
        expect(assign(Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        var results = concurrently(
                () -> unassign(Map.of("recruiterId", r.get(1)), hrToken),
                () -> assign(Map.of("recruiterId", r.get(1)), otherHrToken));
        assertThat(results.get(0)).isIn(200, 409);
        assertThat(results.get(1)).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT recruiter_id::text || ':' || assignment_role FROM requisition_recruiters",
                String.class)).containsExactly(r.get(1) + ":PRIMARY");
    }

    // Holds the requisition row so both requests reach it, then lets them run one after the other.
    @SafeVarargs
    private List<Integer> concurrently(java.util.concurrent.Callable<HttpResponse<String>>... calls) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE",
                    requisitionId);
            try (var executor = Executors.newFixedThreadPool(calls.length)) {
                List<Future<HttpResponse<String>>> responses = new ArrayList<>();
                for (var call : calls) {
                    responses.add(executor.submit(call));
                }
                awaitWaiters(blocker, calls.length);
                connection.commit();
                for (var response : responses) {
                    statuses.add(response.get(20, TimeUnit.SECONDS).statusCode());
                }
            }
        }
        return statuses;
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters", Integer.class);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Lock test", fixturePasswordHash, roles, START)).getId();
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private HttpResponse<String> assign(Map<String, Object> body, String token) throws Exception {
        return request("POST", "/api/v1/requisitions/" + requisitionId + "/assign", json.writeValueAsString(body), token);
    }

    private HttpResponse<String> unassign(Map<String, Object> body, String token) throws Exception {
        return request("POST", "/api/v1/requisitions/" + requisitionId + "/unassign", json.writeValueAsString(body), token);
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

    private int lock(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            assertThat(statement.executeUpdate()).isEqualTo(1);
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
}
