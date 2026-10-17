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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 257: what submitting a requisition does when it has to wait for a lock.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionSubmitLockIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String deviceId = UUID.randomUUID().toString();
    private String fixturePasswordHash;
    private UUID adminId;
    private UUID hrId;
    private UUID headId;
    private String headToken;
    private UUID departmentId;
    private UUID flowId;
    private UUID requisitionId;

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
                + "VALUES ('HIRING_MANAGER', 'REQUISITIONS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
        jdbc.execute("TRUNCATE requisition_workflow_events, approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("DELETE FROM requisition_headcount_overrides");
        jdbc.update("DELETE FROM headcount_plans");
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
        hrId = account("hr@example.test", Role.HR_MANAGER);
        headId = account("head@example.test", Role.HIRING_MANAGER);
        account("approver@example.test", Role.APPROVER);
        headToken = token("head@example.test");
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, 'IT', 'IT', ?, TRUE, ?)",
                departmentId, headId, Timestamp.from(START));
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
        flowId = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", flowId,
                    departmentId);
            version(jdbc, flowId, 1, "HR_MANAGER");
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", departmentId);
        body.put("headcount", 1);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", 20_000_000);
        body.put("proposedSalaryMax", 22_000_000);
        body.put("neededBy", "2026-12-01");
        body.put("jobDescription", "Phát triển dịch vụ tuyển dụng.");
        body.put("candidateRequirements", "Ba năm kinh nghiệm Java.");
        requisitionId = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(body),
                headToken), 201).path("id").asText());
    }

    // The request waits for the requisition row (a save of the requisition holds it), for the department row (a
    // department change or a new approval-flow version holds it) or for the caller's own account (an admin lock or a
    // logout holds it). Whatever changed meanwhile is checked once the lock is granted, including the department
    // scope: the head who no longer manages the department is refused.
    @ParameterizedTest(name = "{0} held, then {1}")
    @CsvSource({
            "requisition, nothing, 200", "requisition, token-expired, 401", "requisition, permission-lost, 403",
            "department, nothing, 200", "department, token-expired, 401", "department, permission-lost, 403",
            "department, manager-changed, 403", "actor, locked-actor, 401", "actor, revoked-session, 401"
    })
    void theCallerIsCheckedAgainAfterEachLockWait(String held, String change, int expected) throws Exception {
        String sql = switch (held) {
            case "requisition" -> "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";
            case "department" -> "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE";
            default -> "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE";
        };
        UUID heldId = switch (held) {
            case "requisition" -> requisitionId;
            case "department" -> departmentId;
            default -> headId;
        };
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> submit(headToken));
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' "
                            + "AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
                    case "manager-changed" -> execute(connection,
                            "UPDATE departments SET manager_user_id = ? WHERE id = ?", hrId, departmentId);
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, headId);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), headId);
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
        assertThat(events()).isEqualTo(expected == 200 ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class,
                requisitionId)).isEqualTo(expected == 200 ? "PENDING_APPROVAL" : "DRAFT");
    }

    // HR publishes version 2 of the department's flow (locking the department row) while the submit waits for that
    // row: the submit then sees version 2 and records it, never a version being published.
    @Test
    void aVersionPublishedWhileTheSubmitWaitsIsTheOneRecorded() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE", departmentId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> submit(headToken));
                awaitWaiters(blocker, 1);
                execute(connection, "INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) "
                        + "VALUES (?, 2, 'Phiên bản 2', ?, ?)", flowId, hrId, Timestamp.from(START));
                execute(connection, "INSERT INTO approval_flow_steps (flow_id, version, position, approver_role) "
                        + "VALUES (?, 2, 1, 'APPROVER')", flowId);
                execute(connection, "UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ? AND version = 2",
                        flowId);
                execute(connection, "UPDATE approval_flows SET current_version = 2 WHERE id = ?", flowId);
                connection.commit();
                expect(response.get(15, TimeUnit.SECONDS), 200);
            }
        }
        assertThat(jdbc.queryForObject("SELECT flow_version FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, requisitionId)).isEqualTo(2);
    }

    private int events() {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events", Integer.class);
    }

    private static void version(JdbcTemplate jdbc, UUID flow, int version, String role) {
        jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, ?, 'IT', "
                + "(SELECT id FROM user_accounts WHERE email = 'hr@example.test'), ?)", flow, version, Timestamp.from(START));
        jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, approver_role) VALUES (?, ?, 1, ?)",
                flow, version, role);
        jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ? AND version = ?", flow, version);
    }

    private UUID account(String email, Role role) {
        return accounts.saveAndFlush(new Account(email, "Lock test", fixturePasswordHash, Set.of(role), START)).getId();
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private HttpResponse<String> submit(String token) throws Exception {
        return request("POST", "/api/v1/requisitions/" + requisitionId + "/submit", null, token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (method.equals("POST") && path.equals("/api/v1/auth/login")) {
            builder.header("X-Device-Id", deviceId);
        }
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
