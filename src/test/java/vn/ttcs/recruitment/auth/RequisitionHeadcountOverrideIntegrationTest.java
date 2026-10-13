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

// Task 275: only the HR manager may save a requisition over the headcount plan, with a reason; who did it, when, why
// and the numbers are stored and can be read back.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionHeadcountOverrideIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 10 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
    private static final String REASON = "Dự án chuyển đổi số cần thêm người ngay trong quý 4.";

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
    private String headToken;
    private UUID itId;
    private UUID salesId;
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
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = token("admin@example.test");
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
    void hrSavesOverThePlanWithAReasonAndTheExceptionIsStoredWithItsNumbers() throws Exception {
        plan(itId, 3, 900_000_000L);
        expect(create(body(itId, 3, null, null), headToken), 201);
        clock.set(START.plusNanos(123_456_789));

        Map<String, Object> over = body(itId, 2, 25_000_000L, REASON);
        JsonNode created = expect(create(over, hrToken), 201);
        UUID id = UUID.fromString(created.path("id").asText());
        // The reason is not a field of the requisition.
        assertThat(created.has("headcountOverrideReason")).isFalse();
        assertThat(created.path("headcount").asInt()).isEqualTo(2);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM requisition_headcount_overrides");
        assertThat(row.get("requisition_id")).isEqualTo(id);
        assertThat(row.get("department_id")).isEqualTo(itId);
        assertThat(row.get("plan_year")).isEqualTo(2026);
        assertThat(row.get("headcount_limit")).isEqualTo(3);
        assertThat(row.get("salary_budget")).isEqualTo(900_000_000L);
        assertThat(row.get("headcount_used")).isEqualTo(3L);
        assertThat(row.get("salary_cost_used")).isEqualTo(0L);
        assertThat(row.get("requested_headcount")).isEqualTo(2);
        assertThat(row.get("requested_salary_cost")).isEqualTo(600_000_000L);
        assertThat(row.get("headcount_exceeded")).isEqualTo(true);
        assertThat(row.get("salary_budget_exceeded")).isEqualTo(false);
        assertThat(row.get("reason")).isEqualTo(REASON);
        assertThat(row.get("overridden_by")).isEqualTo(hrId);
        // The same moment as the requisition's createdAt (microseconds, like PostgreSQL).
        assertThat(((Timestamp) row.get("overridden_at")).toInstant()).isEqualTo(START.plusNanos(123_456_000));
        assertThat(Instant.parse(created.path("createdAt").asText())).isEqualTo(START.plusNanos(123_456_000));

        // HR reads the exception with the salary amounts; the head of IT reads it without them.
        JsonNode forHr = expect(get(BASE + "/" + id + "/headcount-overrides", hrToken), 200).path("items");
        assertThat(forHr.size()).isOne();
        JsonNode item = forHr.get(0);
        assertThat(item.path("reason").asText()).isEqualTo(REASON);
        assertThat(item.path("overriddenBy").asText()).isEqualTo(hrId.toString());
        assertThat(Instant.parse(item.path("overriddenAt").asText())).isEqualTo(START.plusNanos(123_456_000));
        assertThat(item.path("departmentId").asText()).isEqualTo(itId.toString());
        assertThat(item.path("year").asInt()).isEqualTo(2026);
        assertThat(item.path("headcountExceeded").asBoolean()).isTrue();
        assertThat(item.path("salaryBudgetExceeded").asBoolean()).isFalse();
        assertThat(item.path("headcountLimit").asInt()).isEqualTo(3);
        assertThat(item.path("headcountUsed").asLong()).isEqualTo(3);
        assertThat(item.path("requestedHeadcount").asInt()).isEqualTo(2);
        assertThat(item.path("salaryBudget").asLong()).isEqualTo(900_000_000L);
        assertThat(item.path("salaryCostUsed").asLong()).isZero();
        assertThat(item.path("requestedSalaryCost").asLong()).isEqualTo(600_000_000L);
        var response = get(BASE + "/" + id + "/headcount-overrides", headToken);
        JsonNode forHead = expect(response, 200).path("items").get(0);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(forHead.path("reason").asText()).isEqualTo(REASON);
        for (String field : List.of("salaryBudget", "salaryCostUsed", "requestedSalaryCost")) {
            assertThat(forHead.has(field)).as(field).isFalse();
        }
    }

    @Test
    void onlyTheHrManagerMayGoOverThePlanAndAlwaysWithAReason() throws Exception {
        plan(itId, 0, null);
        // The head of IT and ADMIN (REQUISITIONS_WRITE_ALL, no HEADCOUNT_PLANS_WRITE_ALL) may not, even with a reason.
        forbidden(create(body(itId, 1, null, REASON), headToken));
        forbidden(create(body(itId, 1, null, REASON), adminToken));
        // Without a reason, or with a blank one, HR gets the 409 of task 274 too.
        conflict(create(body(itId, 1, null, null), hrToken), "HEADCOUNT_LIMIT_EXCEEDED");
        conflict(create(body(itId, 1, null, " \n\t "), hrToken), "HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(count("recruitment_requisitions")).isZero();
        assertThat(count("requisition_headcount_overrides")).isZero();

        expect(create(body(itId, 1, null, REASON), hrToken), 201);
        assertThat(count("requisition_headcount_overrides")).isOne();
    }

    @Test
    void aReasonOnASaveThatFitsThePlanIsIgnored() throws Exception {
        plan(itId, 5, null);
        expect(create(body(itId, 1, null, REASON), hrToken), 201);
        // No plan at all for SALES.
        expect(create(body(salesId, 1, null, REASON), hrToken), 201);
        // A hiring manager giving a reason that is not needed is not refused either.
        expect(create(body(itId, 1, null, REASON), headToken), 201);
        assertThat(count("requisition_headcount_overrides")).isZero();
    }

    @Test
    void hrConfirmsAHeadsDraftThatAsksForMoreAndEveryConfirmationIsKeptNewestFirst() throws Exception {
        plan(itId, 2, null);
        UUID draft = id(create(body(itId, 2, null, null), headToken));
        conflict(update(draft, body(itId, 3, null, null), headToken), "HEADCOUNT_LIMIT_EXCEEDED");

        clock.set(START.plusSeconds(60));
        expect(update(draft, body(itId, 3, null, "Lần 1"), hrToken), 200);
        clock.set(START.plusSeconds(120));
        expect(update(draft, body(itId, 4, null, "Lần 2"), hrToken), 200);
        // The head can still edit the text: it asks nothing more of the plan.
        Map<String, Object> edited = body(itId, 4, null, null);
        edited.put("jobDescription", "Cập nhật mô tả.");
        expect(update(draft, edited, headToken), 200);

        JsonNode items = expect(get(BASE + "/" + draft + "/headcount-overrides", headToken), 200).path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("reason").asText()).isEqualTo("Lần 2");
        assertThat(items.get(0).path("headcountUsed").asLong()).isZero();
        assertThat(items.get(0).path("requestedHeadcount").asInt()).isEqualTo(4);
        assertThat(Instant.parse(items.get(0).path("overriddenAt").asText())).isEqualTo(START.plusSeconds(120));
        assertThat(items.get(1).path("reason").asText()).isEqualTo("Lần 1");
        assertThat(items.get(1).path("requestedHeadcount").asInt()).isEqualTo(3);
        // The update keeps the requisition's own data: still created by the head.
        assertThat(jdbc.queryForObject("SELECT headcount FROM recruitment_requisitions", Integer.class)).isEqualTo(4);
    }

    @Test
    void aBudgetOnlyExceptionRecordsWhichLimitWasExceeded() throws Exception {
        plan(itId, 10, 300_000_000L);
        // 1 × 25 000 000 × 12 = 300 000 000 fits; a second one does not fit the budget.
        expect(create(body(itId, 1, 25_000_000L, null), headToken), 201);
        conflict(create(body(itId, 1, 25_000_000L, null), headToken), "SALARY_BUDGET_EXCEEDED");
        expect(create(body(itId, 1, 25_000_000L, REASON), hrToken), 201);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM requisition_headcount_overrides");
        assertThat(row.get("headcount_exceeded")).isEqualTo(false);
        assertThat(row.get("salary_budget_exceeded")).isEqualTo(true);
        assertThat(row.get("salary_cost_used")).isEqualTo(300_000_000L);
    }

    @Test
    void theReasonIsLimitedTo1000CharactersWithoutNul() throws Exception {
        plan(itId, 0, null);
        JsonNode tooLong = expect(create(body(itId, 1, null, "a".repeat(1_001)), hrToken), 400);
        assertThat(tooLong.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(tooLong.path("fieldErrors").path("headcountOverrideReason").asText())
                .isEqualTo("Lý do vượt định biên tối đa 1.000 ký tự.");
        JsonNode nul = expect(create(body(itId, 1, null, "Lý do\u0000"), hrToken), 400);
        assertThat(nul.path("fieldErrors").path("headcountOverrideReason").asText())
                .isEqualTo("Lý do vượt định biên chứa ký tự không hợp lệ.");
        expect(create(body(itId, 1, null, "b".repeat(1_000)), hrToken), 201);
        assertThat(jdbc.queryForObject("SELECT length(reason) FROM requisition_headcount_overrides", Integer.class))
                .isEqualTo(1_000);
    }

    @Test
    void theHistoryIsReadLikeTheRequisitionItself() throws Exception {
        UUID sales = id(create(body(salesId, 1, null, null), hrToken));
        assertThat(expect(get(BASE + "/" + sales + "/headcount-overrides", hrToken), 200).path("items").isEmpty())
                .isTrue();
        // SALES is outside the head's scope; an unknown requisition is 404 for everyone.
        forbidden(get(BASE + "/" + sales + "/headcount-overrides", headToken));
        assertThat(expect(get(BASE + "/" + UUID.randomUUID() + "/headcount-overrides", headToken), 404)
                .path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
        // ADMIN reads every requisition but not the plans: no salary keys.
        plan(salesId, 0, 0L);
        expect(update(sales, body(salesId, 2, 20_000_000L, REASON), hrToken), 200);
        JsonNode forAdmin = expect(get(BASE + "/" + sales + "/headcount-overrides", adminToken), 200)
                .path("items").get(0);
        assertThat(forAdmin.has("salaryBudget")).isFalse();
        assertThat(forAdmin.path("salaryBudgetExceeded").asBoolean()).isTrue();
    }

    @Test
    void aScopedReaderOnlySeesTheExceptionsOfDepartmentsTheyManage() throws Exception {
        plan(itId, 0, null);
        UUID draft = id(create(body(itId, 1, null, REASON), hrToken));
        // HR moves the draft to MARKETING, whose head does not manage IT.
        UUID marketingHead = account("marketing@example.test", Set.of(Role.HIRING_MANAGER));
        UUID marketing = department("MARKETING", marketingHead);
        expect(update(draft, body(marketing, 1, null, null), hrToken), 200);

        String marketingToken = token("marketing@example.test");
        assertThat(expect(get(BASE + "/" + draft + "/headcount-overrides", marketingToken), 200).path("items").isEmpty())
                .isTrue();
        // HR (ALL) still sees the IT exception.
        JsonNode forHr = expect(get(BASE + "/" + draft + "/headcount-overrides", hrToken), 200).path("items");
        assertThat(forHr.size()).isOne();
        assertThat(forHr.get(0).path("departmentId").asText()).isEqualTo(itId.toString());
        // The head of IT no longer reads the requisition at all.
        forbidden(get(BASE + "/" + draft + "/headcount-overrides", headToken));
    }

    @Test
    void aDepartmentWithExceptionHistoryIsNotDeletedEvenAfterItsRequisitionMoved() throws Exception {
        plan(itId, 0, null);
        UUID draft = id(create(body(itId, 1, null, REASON), hrToken));
        expect(update(draft, body(salesId, 1, null, null), hrToken), 200);

        JsonNode refused = expect(request("DELETE", "/api/v1/departments/" + itId, null, hrToken), 409);
        assertThat(refused.path("code").asText()).isEqualTo("DEPARTMENT_IN_USE");
        assertThat(count("headcount_plans")).isOne();
    }

    @Test
    void thePermissionToGoOverThePlanIsReadAfterWaitingForTheAccountLock() throws Exception {
        plan(itId, 0, null);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var statement = connection.prepareStatement(
                    "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, hrId);
                try (var row = statement.executeQuery()) { row.next(); blockerPid = row.getInt(1); }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(body(itId, 1, null, REASON), hrToken));
                awaitWaiters(blockerPid);
                jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                        + "AND permission_code = 'HEADCOUNT_PLANS_WRITE_ALL'");
                connection.commit();
                forbidden(response.get(10, TimeUnit.SECONDS));
            }
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'HEADCOUNT_PLANS_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(count("recruitment_requisitions")).isZero();
        assertThat(count("requisition_headcount_overrides")).isZero();
    }

    private Map<String, Object> body(UUID department, int headcount, Long proposedSalaryMax, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", department);
        body.put("headcount", headcount);
        body.put("reason", "NEW_HEADCOUNT");
        if (proposedSalaryMax != null) {
            body.put("proposedSalaryMax", proposedSalaryMax);
        }
        body.put("neededBy", "2026-12-01");
        if (reason != null) {
            body.put("headcountOverrideReason", reason);
        }
        return body;
    }

    private void plan(UUID department, int limit, Long budget) {
        jdbc.update("""
                INSERT INTO headcount_plans (id, department_id, plan_year, headcount_limit, salary_budget, created_at,
                    updated_at, updated_by)
                VALUES (?, ?, 2026, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), department, limit, budget, Timestamp.from(START), Timestamp.from(START), hrId);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Override test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, Timestamp.from(START));
        return id;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        return request("GET", path, null, token);
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

    private void conflict(HttpResponse<String> response, String code) {
        assertThat(expect(response, 409).path("code").asText()).isEqualTo(code);
    }

    private void forbidden(HttpResponse<String> response) {
        assertThat(expect(response, 403).path("code").asText()).isEqualTo("FORBIDDEN");
    }

    private void awaitWaiters(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))
                    """, Integer.class, blockerPid);
            if (observed >= 1) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("The HTTP request must reach the account lock before release").isPositive();
    }
}
