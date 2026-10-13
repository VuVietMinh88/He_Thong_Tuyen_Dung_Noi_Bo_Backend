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

// Task 274: POST /requisitions and PUT /requisitions/{id} lock the headcount plan row of the department and year (FOR
// UPDATE) before they compare the save with the plan. That lock waits while another save of the same department and
// year is still running, and meanwhile the access token may expire or a permission may be removed. The caller is
// checked again after the lock, before the plan decides anything (the same rule as the other row locks of the
// requisition writes and as DepartmentService, task 198): such a save must be refused and must change nothing.
// Task 275: the right to go over the plan (HEADCOUNT_PLANS_WRITE_ALL) is part of that check, so it is read again too.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionHeadcountPlanLockIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 10 Oct 2026 in Vietnam. Access tokens last 15 minutes; only the token expiry case moves the clock.
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
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
    // The head of IT (HIRING_MANAGER, scope SCOPED) who manages the fixture department and writes the drafts.
    private String headToken;
    // The HR manager (REQUISITIONS_WRITE_ALL and HEADCOUNT_PLANS_WRITE_ALL): the only one who may go over the plan.
    private String hrToken;
    private UUID hrId;
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
        grant("HR_MANAGER", "HEADCOUNT_PLANS_WRITE_ALL");
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
        UUID adminId = UUID.fromString(login("admin@example.test").path("user").path("id").asText());
        String hash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = accounts.saveAndFlush(new Account("hr@example.test", "HR", hash, Set.of(Role.HR_MANAGER), START)).getId();
        UUID headId = accounts.saveAndFlush(new Account("head@example.test", "Head", hash,
                Set.of(Role.HIRING_MANAGER), START)).getId();
        headToken = login("head@example.test").path("accessToken").asText();
        hrToken = login("hr@example.test").path("accessToken").asText();
        departmentId = department("IT", headId);
        positionId = position("DEV_JUNIOR");
        // Room for every save of the first test: 2 people before, 5 after.
        plan(departmentId, 2026, 10);
    }

    // The head's save passed the checks at the start and then waits for the plan row that another transaction holds.
    // With nothing changed it goes on when that transaction ends. When the token expired or the permission was removed
    // during the wait, it is refused (401 or 403) and no requisition changes.
    @ParameterizedTest(name = "{0} waiting for the plan row, then {1}")
    @CsvSource({
            "create, nothing", "create, token-expiry", "create, permission-lost",
            "update, nothing", "update, token-expiry", "update, permission-lost"
    })
    void aSaveWaitingForThePlanChecksTheCallerAgain(String operation, String change) throws Exception {
        boolean create = operation.equals("create");
        UUID requisition = create ? null : saved(headToken, HEADCOUNT_BEFORE, null);
        List<Map<String, Object>> before = requisitionRows();
        try {
            var result = saveWhilePlanIsLocked(create, requisition, headToken, draftBody(HEADCOUNT_AFTER, null), () -> {
                switch (change) {
                    case "token-expiry" -> clock.set(START.plus(Duration.ofMinutes(15)));
                    // A migration that removes the grant from the role locks no account.
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
                    default -> { }
                }
            });
            switch (change) {
                case "nothing" -> assertThat(expect(result, create ? 201 : 200).path("headcount").asInt())
                        .isEqualTo(HEADCOUNT_AFTER);
                case "token-expiry" -> assertThat(expect(result, 401).path("code").asText()).isEqualTo("SESSION_INVALID");
                default -> assertThat(expect(result, 403).path("code").asText()).isEqualTo("FORBIDDEN");
            }
        } finally {
            grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
        }
        if (change.equals("nothing")) {
            assertThat(requisitionRows()).isNotEqualTo(before);
        } else {
            assertThat(requisitionRows()).isEqualTo(before);
        }
    }

    // Task 275: the HR manager saves over the plan with a reason and waits for the plan row. If HEADCOUNT_PLANS_WRITE_ALL
    // is removed meanwhile, the save is refused with 403 and neither the requisition nor its exception is written; the
    // right is not taken from the permissions read before the wait.
    @ParameterizedTest(name = "{0} over the plan waiting for the plan row, then {1}")
    @CsvSource({"create, nothing", "create, override-lost", "update, nothing", "update, override-lost"})
    void anOverrideWaitingForThePlanChecksTheRightToOverrideAgain(String operation, String change) throws Exception {
        boolean create = operation.equals("create");
        // A plan of one person: the draft saved first fills it, and the save of five people is over it.
        jdbc.update("UPDATE headcount_plans SET headcount_limit = 1");
        UUID requisition = create ? null : saved(hrToken, 1, null);
        List<Map<String, Object>> before = requisitionRows();
        try {
            var result = saveWhilePlanIsLocked(create, requisition, hrToken,
                    draftBody(HEADCOUNT_AFTER, "Mở rộng dự án trọng điểm."), () -> {
                        if (change.equals("override-lost")) {
                            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'HEADCOUNT_PLANS_WRITE_ALL'");
                        }
                    });
            if (change.equals("nothing")) {
                assertThat(expect(result, create ? 201 : 200).path("headcount").asInt()).isEqualTo(HEADCOUNT_AFTER);
                assertThat(overrideCount()).isOne();
            } else {
                assertThat(expect(result, 403).path("code").asText()).isEqualTo("FORBIDDEN");
                assertThat(overrideCount()).isZero();
            }
        } finally {
            grant("HR_MANAGER", "HEADCOUNT_PLANS_WRITE_ALL");
        }
        if (change.equals("nothing")) {
            assertThat(requisitionRows()).isNotEqualTo(before);
        } else {
            assertThat(requisitionRows()).isEqualTo(before);
        }
    }

    // Sends the save as the given caller while another transaction holds the plan row, runs whileWaiting once the
    // request is waiting for it, then lets the other transaction end and returns the answer.
    private HttpResponse<String> saveWhilePlanIsLocked(boolean create, UUID requisition, String token, String body,
                                                       Runnable whileWaiting) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            // Another save of the same department and year holds the plan row until it commits.
            try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM headcount_plans "
                    + "WHERE department_id = ? AND plan_year = 2026 FOR SHARE")) {
                statement.setObject(1, departmentId);
                try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); blockerPid = result.getInt(1); }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create
                        ? request("POST", BASE, body, token)
                        : request("PUT", BASE + "/" + requisition, body, token));
                try {
                    awaitWaiters(blockerPid, 1);
                    whileWaiting.run();
                    connection.commit();
                    return response.get(10, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private UUID saved(String token, int headcount, String overrideReason) throws Exception {
        return UUID.fromString(expect(request("POST", BASE, draftBody(headcount, overrideReason), token), 201)
                .path("id").asText());
    }

    private List<Map<String, Object>> requisitionRows() {
        return jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
    }

    private int overrideCount() {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_headcount_overrides", Integer.class);
    }

    private String draftBody(int headcount, String overrideReason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", departmentId);
        body.put("headcount", headcount);
        body.put("reason", "NEW_HEADCOUNT");
        // The plan year is the year of the needed-by date.
        body.put("neededBy", "2026-12-01");
        if (overrideReason != null) {
            body.put("headcountOverrideReason", overrideReason);
        }
        return json.writeValueAsString(body);
    }

    private void plan(UUID department, int year, int limit) {
        jdbc.update("""
                INSERT INTO headcount_plans (id, department_id, plan_year, headcount_limit, salary_budget, created_at,
                    updated_at, updated_by)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
                """, UUID.randomUUID(), department, year, limit, Timestamp.from(START), Timestamp.from(START), hrId);
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
