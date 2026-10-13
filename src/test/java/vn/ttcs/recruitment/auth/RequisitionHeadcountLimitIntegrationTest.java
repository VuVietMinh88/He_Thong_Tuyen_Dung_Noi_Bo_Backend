package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 274: a requisition save that asks more of the headcount plan of its department and year than is left is
// refused with 409, also when several drafts are saved at the same moment.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionHeadcountLimitIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 10 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
    private static final String CONFIRM = " Cần Trưởng phòng Nhân sự xác nhận vượt định biên kèm lý do.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private UUID hrId;
    private String hrToken;
    // The head of IT (HIRING_MANAGER, REQUISITIONS_*_SCOPED); SALES belongs to the HR manager.
    private String headToken;
    private UUID itId;
    private UUID salesId;
    // Standard band 15 000 000 – 25 000 000: proposals inside it need no justification.
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
        jdbc.update("DELETE FROM headcount_plans");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        UUID headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        hrToken = token("hr@example.test");
        headToken = token("head@example.test");
        itId = department("IT", headId);
        salesId = department("SALES", hrId);
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
    }

    @Test
    void withoutAPlanForTheDepartmentAndYearNothingIsLimited() throws Exception {
        plan(itId, 2027, 0, 0L);
        plan(salesId, 2026, 0, 0L);

        expect(create(body(itId, 999, null, "2026-12-01"), headToken), 201);
        expect(create(body(itId, 999, 25_000_000L, null), headToken), 201);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void aDraftThatFillsThePlanExactlyIsSavedAndTheNextOneIsRefused() throws Exception {
        plan(itId, 2026, 5, null);
        expect(create(body(itId, 3, null, "2026-12-01"), headToken), 201);
        expect(create(body(itId, 2, null, null), headToken), 201);

        var response = create(body(itId, 1, null, "2026-11-15"), headToken);
        JsonNode refused = conflict(response, "HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(refused.path("message").asText()).isEqualTo(
                "Phòng ban chỉ còn 0 chỉ tiêu headcount năm 2026 nhưng yêu cầu cần 1 người." + CONFIRM);
        assertThat(refused.path("fieldErrors").isEmpty()).isTrue();
        noStore(response);
        // HR_MANAGER (REQUISITIONS_WRITE_ALL) is refused too until it gives a reason (task 275).
        conflict(create(body(itId, 1, null, "2026-11-15"), hrToken), "HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void onlyRequisitionsOfThePlanDepartmentAndPlanYearCount() throws Exception {
        plan(itId, 2026, 1, null);
        // Needed in 2027: another plan year, which has no plan.
        expect(create(body(itId, 5, null, "2027-01-02"), headToken), 201);
        // Another department.
        expect(create(body(salesId, 5, null, "2026-12-01"), hrToken), 201);
        // No date: counted in the year it is created, 2026, and it fits.
        expect(create(body(itId, 1, null, null), headToken), 201);

        JsonNode refused = conflict(create(body(itId, 2, null, "2026-12-31"), headToken), "HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(refused.path("message").asText()).startsWith("Phòng ban chỉ còn 0 chỉ tiêu headcount năm 2026 "
                + "nhưng yêu cầu cần 2 người.");
    }

    @Test
    void aDraftWithoutDateIsCountedInTheBusinessYearOfItsCreation() throws Exception {
        plan(itId, 2027, 0, null);
        // 00:30 on 1 Jan 2027 in Vietnam, while UTC is still on 31 Dec 2026.
        clock.set(Instant.parse("2026-12-31T17:30:00Z"));
        headToken = token("head@example.test");

        JsonNode refused = conflict(create(body(itId, 1, null, null), headToken), "HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(refused.path("message").asText()).contains("năm 2027");
        // A date in 2028 counts in 2028, which has no plan.
        expect(create(body(itId, 1, null, "2028-01-01"), headToken), 201);
    }

    @Test
    void theSalaryBudgetCountsTheYearlyCostOfTheProposalWithoutShowingTheBudget() throws Exception {
        plan(itId, 2026, 100, 600_000_000L);
        // 2 × 25 000 000 × 12 = 600 000 000: exactly the budget.
        expect(create(body(itId, 2, 25_000_000L, "2026-12-01"), headToken), 201);
        // No proposed salary yet: costs nothing, the people still count.
        expect(create(body(itId, 1, null, "2026-12-01"), headToken), 201);

        var response = create(body(itId, 1, 15_000_000L, "2026-12-01"), headToken);
        JsonNode refused = conflict(response, "SALARY_BUDGET_EXCEEDED");
        assertThat(refused.path("message").asText())
                .isEqualTo("Yêu cầu vượt ngân sách lương năm 2026 của phòng ban." + CONFIRM);
        assertThat(refused.path("message").asText()).doesNotContainPattern("[0-9]{5,}");
        noStore(response);

        // Both limits at once: the headcount code, and the message mentions the budget too.
        jdbc.update("UPDATE headcount_plans SET headcount_limit = 3");
        JsonNode both = conflict(create(body(itId, 1, 15_000_000L, "2026-12-01"), headToken),
                "HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(both.path("message").asText()).isEqualTo("Phòng ban chỉ còn 0 chỉ tiêu headcount năm 2026 "
                + "nhưng yêu cầu cần 1 người. Yêu cầu cũng vượt ngân sách lương năm 2026." + CONFIRM);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void updatesAreOnlyRefusedWhenTheyAskMoreOfThePlanThanBefore() throws Exception {
        plan(itId, 2026, 3, 600_000_000L);
        UUID first = id(create(body(itId, 2, 25_000_000L, "2026-12-01"), headToken));
        UUID second = id(create(body(itId, 1, null, "2026-12-01"), headToken));

        // The plan is full, but editing the text asks nothing more.
        Map<String, Object> edited = body(itId, 2, 25_000_000L, "2026-12-01");
        edited.put("jobDescription", "Bổ sung mô tả.");
        expect(update(first, edited, headToken), 200);
        conflict(update(first, body(itId, 3, 25_000_000L, "2026-12-01"), headToken), "HEADCOUNT_LIMIT_EXCEEDED");
        // Fewer people, then back to the old number: both fit.
        expect(update(first, body(itId, 1, 25_000_000L, "2026-12-01"), headToken), 200);
        expect(update(first, body(itId, 2, 25_000_000L, "2026-12-01"), headToken), 200);

        // HR lowers the plan below what is used: the drafts stay editable while they ask nothing more.
        jdbc.update("UPDATE headcount_plans SET headcount_limit = 1");
        expect(update(first, edited, headToken), 200);
        conflict(update(first, body(itId, 3, 25_000_000L, "2026-12-01"), headToken), "HEADCOUNT_LIMIT_EXCEEDED");
        // A lower salary costs less: fine. Then HR lowers the budget below it (2 × 20 000 000 × 12 = 480 000 000):
        // the same salary is still fine, a higher one costs more than before and is refused by the budget.
        expect(update(first, body(itId, 2, 20_000_000L, "2026-12-01"), headToken), 200);
        jdbc.update("UPDATE headcount_plans SET salary_budget = 400000000");
        expect(update(first, body(itId, 2, 20_000_000L, "2026-12-01"), headToken), 200);
        conflict(update(first, body(itId, 2, 21_000_000L, "2026-12-01"), headToken), "SALARY_BUDGET_EXCEEDED");

        // Moving a draft into another plan year or department asks that plan for all of its people.
        plan(itId, 2027, 0, null);
        conflict(update(second, body(itId, 1, null, "2027-02-01"), headToken), "HEADCOUNT_LIMIT_EXCEEDED");
        plan(salesId, 2026, 0, null);
        conflict(update(second, body(salesId, 1, null, "2026-12-01"), hrToken), "HEADCOUNT_LIMIT_EXCEEDED");
        // Into a year without plan: allowed.
        expect(update(second, body(itId, 1, null, "2028-02-01"), headToken), 200);
        assertThat(jdbc.queryForObject("SELECT headcount FROM recruitment_requisitions WHERE id = ?", Integer.class,
                first)).isEqualTo(2);
    }

    @Test
    void twoDraftsSavedAtOnceForTheLastPlaceGetOne201AndOne409() throws Exception {
        plan(itId, 2026, 1, null);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Holding the plan row makes both saves wait at the plan lock, so they reach it together.
            int blockerPid = lock(connection, "SELECT pg_backend_pid() FROM headcount_plans WHERE department_id = ? "
                    + "FOR UPDATE", itId);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<HttpResponse<String>>> responses = new ArrayList<>();
                responses.add(executor.submit(() -> create(body(itId, 1, null, "2026-12-01"), headToken)));
                responses.add(executor.submit(() -> create(body(itId, 1, null, "2026-12-02"), hrToken)));
                awaitWaiters(blockerPid, 2);
                connection.rollback();
                List<Integer> statuses = new ArrayList<>();
                for (var response : responses) {
                    var result = response.get(20, TimeUnit.SECONDS);
                    statuses.add(result.statusCode());
                    if (result.statusCode() == 409) {
                        assertThat(json.readTree(result.body()).path("code").asText())
                                .isEqualTo("HEADCOUNT_LIMIT_EXCEEDED");
                    }
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            }
        }
        assertThat(count()).isOne();
    }

    @Test
    void aSaveWaitingForAPlanChangeIsCheckedAgainstTheNewLimit() throws Exception {
        plan(itId, 2026, 5, null);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // HR lowers the plan to 0 in a transaction that is still open, as PUT /headcount-plans/{id} would.
            int hrPid;
            try (var statement = connection.prepareStatement(
                    "UPDATE headcount_plans SET headcount_limit = 0 WHERE department_id = ? RETURNING pg_backend_pid()")) {
                statement.setObject(1, itId);
                try (var row = statement.executeQuery()) { row.next(); hrPid = row.getInt(1); }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(body(itId, 1, null, "2026-12-01"), headToken));
                awaitWaiters(hrPid, 1);
                connection.commit();
                conflict(response.get(10, TimeUnit.SECONDS), "HEADCOUNT_LIMIT_EXCEEDED");
            }
        }
        assertThat(count()).isZero();
    }

    @Test
    void validationAndScopeErrorsComeBeforeThePlan() throws Exception {
        plan(itId, 2026, 0, 0L);
        plan(salesId, 2026, 0, 0L);
        // SALES is outside the head's scope: 403, not 409.
        assertThat(expect(create(body(salesId, 1, null, "2026-12-01"), headToken), 403).path("code").asText())
                .isEqualTo("FORBIDDEN");
        assertThat(expect(create(body(itId, 1, null, "2026-10-09"), headToken), 400).path("code").asText())
                .isEqualTo("NEEDED_BY_IN_PAST");
        // Outside the standard band without justification.
        assertThat(expect(create(body(itId, 1, 30_000_000L, "2026-12-01"), headToken), 400).path("code").asText())
                .isEqualTo("SALARY_JUSTIFICATION_REQUIRED");
        assertThat(count()).isZero();
    }

    private Map<String, Object> body(UUID department, int headcount, Long proposedSalaryMax, String neededBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", department);
        body.put("headcount", headcount);
        body.put("reason", "NEW_HEADCOUNT");
        if (proposedSalaryMax != null) {
            body.put("proposedSalaryMax", proposedSalaryMax);
        }
        body.put("neededBy", neededBy);
        return body;
    }

    private void plan(UUID department, int year, int limit, Long budget) {
        jdbc.update("""
                INSERT INTO headcount_plans (id, department_id, plan_year, headcount_limit, salary_budget, created_at,
                    updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), department, year, limit, budget, Timestamp.from(START), Timestamp.from(START),
                hrId);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Headcount test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, Timestamp.from(START));
        return id;
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private UUID id(HttpResponse<String> response) {
        return UUID.fromString(expect(response, 201).path("id").asText());
    }

    private HttpResponse<String> create(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> update(UUID id, Map<String, Object> body, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(body), token);
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

    private JsonNode conflict(HttpResponse<String> response, String code) {
        JsonNode body = expect(response, 409);
        assertThat(body.path("code").asText()).isEqualTo(code);
        return body;
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int lock(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
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
}
