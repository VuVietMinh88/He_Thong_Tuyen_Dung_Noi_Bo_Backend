package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
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
import java.sql.SQLException;
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

// The writes of RequisitionService (POST /requisitions and PUT /requisitions/{id}) check the caller after the account
// and session locks and, since this fix, again after every row lock they wait for: the requisition row (update
// only) and the chosen position and department rows (create and update, locked FOR SHARE, so they wait while HR
// edits those rows). Meanwhile the access token may expire or a permission may be removed. Such a write must be
// refused and must change nothing (the same rule as DepartmentService, task 198).
// RequisitionManagementIntegrationTest already covers the wait for the caller's own account lock.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionWriteLockIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; only the token expiry case moves the clock.
    private static final Instant START = Instant.parse("2026-10-10T00:00:00Z");
    private static final int HEADCOUNT_BEFORE = 2;
    private static final int HEADCOUNT_AFTER = 5;

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // The department head (HIRING_MANAGER, scope SCOPED) who manages the fixture department and writes the drafts.
    private String headToken;
    private UUID departmentId;
    private UUID positionId;

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
        grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = UUID.fromString(login("admin@example.test").path("user").path("id").asText());
        String hash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        UUID headId = accounts.saveAndFlush(new Account("head@example.test", "Head", hash,
                Set.of(Role.HIRING_MANAGER), START)).getId();
        headToken = login("head@example.test").path("accessToken").asText();
        departmentId = department("IT", headId);
        positionId = position("DEV_JUNIOR");
    }

    // The head's write passed the checks at the start and then waits for a row that another transaction holds:
    // the requisition row (update), or the position or department row that HR is editing (create and update). With
    // nothing changed it goes on when that transaction ends. When the token expired or the permission was removed
    // during the wait, it is refused (401 or 403) and no requisition changes.
    @ParameterizedTest(name = "{0} waiting for the {1} row, then {2}")
    @CsvSource({
            "create, position, nothing", "create, position, token-expiry", "create, position, permission-lost",
            "create, department, nothing", "create, department, token-expiry", "create, department, permission-lost",
            "update, requisition, nothing", "update, requisition, token-expiry", "update, requisition, permission-lost",
            "update, position, nothing", "update, position, token-expiry", "update, position, permission-lost",
            "update, department, nothing", "update, department, token-expiry", "update, department, permission-lost"
    })
    void aWriteWaitingForARowChecksTheCallerAgain(String operation, String lock, String change) throws Exception {
        boolean create = operation.equals("create");
        UUID requisition = create ? null
                : UUID.fromString(expect(request("POST", BASE, draftBody(HEADCOUNT_BEFORE), headToken), 201)
                        .path("id").asText());
        List<Map<String, Object>> before = requisitionRows();
        String table = switch (lock) {
            case "requisition" -> "recruitment_requisitions";
            case "position" -> "positions";
            default -> "departments";
        };
        UUID row = switch (lock) {
            case "requisition" -> requisition;
            case "position" -> positionId;
            default -> departmentId;
        };
        // The requisition is read FOR UPDATE, so a FOR SHARE blocks it; the position and the department are read FOR
        // SHARE, so only HR's FOR UPDATE (the edit of that row) blocks them.
        String mode = lock.equals("requisition") ? "FOR SHARE" : "FOR UPDATE";
        String lockSql = "SELECT pg_backend_pid() FROM " + table + " WHERE id = ? " + mode;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var statement = connection.prepareStatement(lockSql)) {
                statement.setObject(1, row);
                try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); blockerPid = result.getInt(1); }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create
                        ? request("POST", BASE, draftBody(HEADCOUNT_AFTER), headToken)
                        : request("PUT", BASE + "/" + requisition, draftBody(HEADCOUNT_AFTER), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "token-expiry" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        // A migration that removes the grant from the role locks no account.
                        case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
                        default -> { }
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    switch (change) {
                        case "nothing" -> assertThat(expect(result, create ? 201 : 200).path("headcount").asInt())
                                .isEqualTo(HEADCOUNT_AFTER);
                        case "token-expiry" -> assertThat(expect(result, 401).path("code").asText()).isEqualTo("SESSION_INVALID");
                        default -> assertThat(expect(result, 403).path("code").asText()).isEqualTo("FORBIDDEN");
                    }
                } finally {
                    connection.rollback();
                    grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
                }
            }
        }
        if (change.equals("nothing")) {
            assertThat(requisitionRows()).isNotEqualTo(before);
        } else {
            assertThat(requisitionRows()).isEqualTo(before);
        }
    }

    private List<Map<String, Object>> requisitionRows() {
        return jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
    }

    private String draftBody(int headcount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", departmentId);
        body.put("headcount", headcount);
        body.put("reason", "NEW_HEADCOUNT");
        return json.writeValueAsString(body);
    }

    private UUID department(String code, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, Timestamp.from(START));
        return id;
    }

    private UUID position(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, id, code, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private void grant(String role, String permission) {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?) ON CONFLICT DO NOTHING",
                role, permission);
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
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

    // Counts the requests waiting, directly or through another waiting request, for the other connection's locks.
    private void awaitWaiters(int blockerPid, int expected) throws SQLException, InterruptedException {
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
        assertThat(observed).as("the HTTP request must reach the PostgreSQL lock before it is released").isGreaterThanOrEqualTo(expected);
    }
}
