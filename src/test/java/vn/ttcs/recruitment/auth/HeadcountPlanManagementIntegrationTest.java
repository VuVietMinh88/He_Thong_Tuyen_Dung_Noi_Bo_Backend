package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.time.LocalDate;
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

// Task 273: HR declares and reads headcount plans; the requisition form reads what is left of one plan.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HeadcountPlanManagementIntegrationTest {
    private static final String BASE = "/api/v1/headcount-plans";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 10 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
    private static final long FIVE_BILLION_VND = 5_000_000_000L;
    private static final long BUDGET_CEILING = 1_000_000_000_000_000L;
    private static final List<String> PLAN_FIELDS = List.of("id", "departmentId", "departmentCode", "departmentName",
            "year", "headcountLimit", "headcountUsed", "headcountRemaining", "salaryBudget", "salaryBudgetUsed",
            "salaryBudgetRemaining", "createdAt", "updatedAt", "updatedBy");

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
    private UUID otherHrId;
    private String otherHrToken;
    // The head of IT (HIRING_MANAGER): manages IT and, through it, IT_DEV; not SALES.
    private UUID headId;
    private String headToken;
    private UUID itId;
    private UUID itDevId;
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
        hrToken = token("hr@example.test");
        otherHrId = account("hr2@example.test", Set.of(Role.HR_MANAGER));
        otherHrToken = token("hr2@example.test");
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        itId = department("IT", null, headId, true);
        itDevId = department("IT_DEV", itId, hrId, true);
        salesId = department("SALES", null, hrId, true);
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
    }

    @Test
    void hrCreatesAPlanWithItsUsageAndTheDatabaseKeepsWhoChangedItLast() throws Exception {
        clock.set(START.plusNanos(123_456_789));
        Instant expectedTime = START.plusNanos(123_456_000);
        // Already saved before the plan: 2 people at 25 000 000 a month = 600 000 000 a year.
        draft(itId, 2, 25_000_000L, LocalDate.of(2026, 12, 1));

        var response = post(plan(itId, 2026, 5, FIVE_BILLION_VND), hrToken);
        JsonNode created = expect(response, 201);
        noStore(response);
        assertThat(created.size()).isEqualTo(PLAN_FIELDS.size());
        PLAN_FIELDS.forEach(field -> assertThat(created.has(field)).as(field).isTrue());
        assertThat(created.path("departmentId").asText()).isEqualTo(itId.toString());
        assertThat(created.path("departmentCode").asText()).isEqualTo("IT");
        assertThat(created.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(created.path("year").asInt()).isEqualTo(2026);
        assertThat(created.path("headcountLimit").asInt()).isEqualTo(5);
        assertThat(created.path("headcountUsed").asLong()).isEqualTo(2);
        assertThat(created.path("headcountRemaining").asLong()).isEqualTo(3);
        assertThat(created.path("salaryBudget").asLong()).isEqualTo(FIVE_BILLION_VND);
        assertThat(created.path("salaryBudgetUsed").asLong()).isEqualTo(600_000_000L);
        assertThat(created.path("salaryBudgetRemaining").asLong()).isEqualTo(FIVE_BILLION_VND - 600_000_000L);
        assertThat(Instant.parse(created.path("createdAt").asText())).isEqualTo(expectedTime);
        assertThat(Instant.parse(created.path("updatedAt").asText())).isEqualTo(expectedTime);
        assertThat(created.path("updatedBy").asText()).isEqualTo(hrId.toString());

        UUID id = UUID.fromString(created.path("id").asText());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM headcount_plans WHERE id = ?", id);
        assertThat(row.get("department_id")).isEqualTo(itId);
        assertThat(row.get("plan_year")).isEqualTo(2026);
        assertThat(row.get("headcount_limit")).isEqualTo(5);
        assertThat(row.get("salary_budget")).isEqualTo(FIVE_BILLION_VND);
        assertThat(row.get("updated_by")).isEqualTo(hrId);
        // GET returns exactly what POST returned.
        assertThat(expect(get(BASE + "/" + id, hrToken), 200)).isEqualTo(created);
    }

    @Test
    void aPlanWithoutBudgetHasNoBudgetLimitAndASecondPlanForTheSameYearIsRefused() throws Exception {
        Map<String, Object> body = plan(itId, 2026, 0, null);
        body.remove("salaryBudget");
        JsonNode created = expect(post(body, hrToken), 201);
        assertThat(created.path("headcountLimit").asInt()).isZero();
        assertThat(created.path("salaryBudget").isNull()).isTrue();
        assertThat(created.path("salaryBudgetRemaining").isNull()).isTrue();
        assertThat(created.path("salaryBudgetUsed").asLong()).isZero();

        JsonNode duplicate = expect(post(plan(itId, 2026, 7, FIVE_BILLION_VND), otherHrToken), 409);
        assertThat(duplicate.path("code").asText()).isEqualTo("HEADCOUNT_PLAN_EXISTS");
        // Another year, and the same year for another department, are other plans.
        expect(post(plan(itId, 2027, 7, null), hrToken), 201);
        expect(post(plan(salesId, 2026, 7, null), hrToken), 201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isEqualTo(3);
    }

    @Test
    void theDepartmentMustExistAndStillBeActive() throws Exception {
        JsonNode unknown = expect(post(plan(UUID.randomUUID(), 2026, 5, null), hrToken), 400);
        assertThat(unknown.path("code").asText()).isEqualTo("INVALID_HEADCOUNT_PLAN_DEPARTMENT");
        assertThat(unknown.path("fieldErrors").path("departmentId").asText()).isEqualTo("Phòng ban không tồn tại.");

        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", salesId);
        JsonNode inactive = expect(post(plan(salesId, 2026, 5, null), hrToken), 400);
        assertThat(inactive.path("code").asText()).isEqualTo("HEADCOUNT_PLAN_DEPARTMENT_INACTIVE");
        assertThat(inactive.path("fieldErrors").has("departmentId")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();
    }

    @Test
    void rejectsMissingAndOutOfRangeValuesButAcceptsTheLimits() throws Exception {
        for (String field : List.of("departmentId", "year", "headcountLimit")) {
            Map<String, Object> body = plan(itId, 2026, 5, null);
            body.remove(field);
            fieldErrors(post(body, hrToken), field);
        }
        fieldErrors(post(plan(itId, 1999, 5, null), hrToken), "year");
        fieldErrors(post(plan(itId, 2101, 5, null), hrToken), "year");
        fieldErrors(post(plan(itId, 2026, -1, null), hrToken), "headcountLimit");
        fieldErrors(post(plan(itId, 2026, 100_000, null), hrToken), "headcountLimit");
        fieldErrors(post(plan(itId, 2026, 5, -1L), hrToken), "salaryBudget");
        fieldErrors(post(plan(itId, 2026, 5, BUDGET_CEILING + 1), hrToken), "salaryBudget");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();

        expect(post(plan(itId, 2000, 0, 0L), hrToken), 201);
        expect(post(plan(itId, 2100, 99_999, BUDGET_CEILING), hrToken), 201);
    }

    @Test
    void rejectsNumbersThatAreNotJsonWholeNumbersAndFieldsTheServerDecides() throws Exception {
        String base = "{\"departmentId\":\"" + itId + "\",";
        for (String raw : List.of("\"year\":2026.5,\"headcountLimit\":5", "\"year\":\"2026\",\"headcountLimit\":5",
                "\"year\":2026,\"headcountLimit\":5.0", "\"year\":2026,\"headcountLimit\":5,\"salaryBudget\":1.5",
                "\"year\":2026,\"headcountLimit\":5,\"headcountUsed\":0",
                "\"year\":2026,\"headcountLimit\":5,\"id\":\"" + UUID.randomUUID() + "\"")) {
            JsonNode body = expect(request("POST", BASE, base + raw + "}", hrToken), 400);
            assertThat(body.path("code").asText()).as(raw).isEqualTo("INVALID_JSON");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();
    }

    @Test
    void updateReplacesBothNumbersButNeverTheDepartmentOrTheYear() throws Exception {
        JsonNode created = expect(post(plan(itId, 2026, 5, FIVE_BILLION_VND), hrToken), 201);
        UUID id = UUID.fromString(created.path("id").asText());
        draft(itId, 3, null, LocalDate.of(2026, 11, 1));
        // Within the 15 minutes of the access tokens.
        clock.set(START.plus(Duration.ofMinutes(5)));

        // Lower than what is already used: allowed, the remaining number turns negative.
        JsonNode updated = expect(put(id, Map.of("headcountLimit", 2, "salaryBudget", 1_000_000L), otherHrToken), 200);
        assertThat(updated.path("headcountLimit").asInt()).isEqualTo(2);
        assertThat(updated.path("headcountUsed").asLong()).isEqualTo(3);
        assertThat(updated.path("headcountRemaining").asLong()).isEqualTo(-1);
        assertThat(updated.path("salaryBudget").asLong()).isEqualTo(1_000_000L);
        assertThat(updated.path("departmentId").asText()).isEqualTo(itId.toString());
        assertThat(updated.path("year").asInt()).isEqualTo(2026);
        assertThat(updated.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
        assertThat(Instant.parse(updated.path("updatedAt").asText())).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(updated.path("updatedBy").asText()).isEqualTo(otherHrId.toString());

        // Leaving salaryBudget out removes the budget limit.
        JsonNode noBudget = expect(put(id, Map.of("headcountLimit", 4), hrToken), 200);
        assertThat(noBudget.path("salaryBudget").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT salary_budget IS NULL FROM headcount_plans WHERE id = ?",
                Boolean.class, id)).isTrue();

        for (String field : List.of("departmentId", "year")) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("headcountLimit", 4));
            body.put(field, field.equals("year") ? 2027 : salesId);
            assertThat(expect(put(id, body, hrToken), 400).path("code").asText()).isEqualTo("INVALID_JSON");
        }
        fieldErrors(put(id, Map.of(), hrToken), "headcountLimit");
        assertThat(expect(put(UUID.randomUUID(), Map.of("headcountLimit", 4), hrToken), 404).path("code").asText())
                .isEqualTo("HEADCOUNT_PLAN_NOT_FOUND");
        assertThat(jdbc.queryForMap("SELECT department_id, plan_year, headcount_limit FROM headcount_plans"))
                .isEqualTo(Map.of("department_id", itId, "plan_year", 2026, "headcount_limit", 4));
    }

    @Test
    void listShowsTheNewestYearFirstThenDepartmentsByCodeWithFiltersAndPages() throws Exception {
        UUID sales2026 = id(post(plan(salesId, 2026, 1, null), hrToken));
        UUID it2026 = id(post(plan(itId, 2026, 2, null), hrToken));
        UUID it2027 = id(post(plan(itId, 2027, 3, null), hrToken));
        UUID itDev2026 = id(post(plan(itDevId, 2026, 4, null), hrToken));
        draft(itDevId, 1, null, LocalDate.of(2026, 11, 1));

        JsonNode all = expect(get(BASE, hrToken), 200);
        assertThat(ids(all)).containsExactly(it2027, it2026, itDev2026, sales2026);
        assertThat(all.path("totalElements").asLong()).isEqualTo(4);
        assertThat(all.path("totalPages").asLong()).isEqualTo(1);
        // Each item carries its own usage: only IT_DEV has a requisition.
        assertThat(all.path("items").get(2).path("headcountUsed").asLong()).isEqualTo(1);
        assertThat(all.path("items").get(1).path("headcountUsed").asLong()).isZero();

        assertThat(ids(expect(get(BASE + "?year=2026", hrToken), 200))).containsExactly(it2026, itDev2026, sales2026);
        assertThat(ids(expect(get(BASE + "?departmentId=" + itId, hrToken), 200))).containsExactly(it2027, it2026);
        assertThat(ids(expect(get(BASE + "?year=2027&departmentId=" + salesId, hrToken), 200))).isEmpty();
        JsonNode second = expect(get(BASE + "?page=1&size=3", hrToken), 200);
        assertThat(ids(second)).containsExactly(sales2026);
        assertThat(second.path("totalPages").asLong()).isEqualTo(2);

        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?year=1999", "?year=2101",
                "?page=2147483647&size=100")) {
            assertThat(expect(get(BASE + query, hrToken), 400).path("code").asText()).as(query)
                    .isEqualTo("VALIDATION_ERROR");
        }
        assertThat(expect(get(BASE + "?year=abc", hrToken), 400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(expect(get(BASE + "/" + UUID.randomUUID(), hrToken), 404).path("code").asText())
                .isEqualTo("HEADCOUNT_PLAN_NOT_FOUND");
    }

    @Test
    void remainingShowsHeadcountToRequisitionReadersAndTheBudgetOnlyToHr() throws Exception {
        expect(post(plan(itId, 2026, 5, FIVE_BILLION_VND), hrToken), 201);
        draft(itId, 2, 25_000_000L, LocalDate.of(2026, 12, 1));

        JsonNode forHr = expect(get(BASE + "/remaining?departmentId=" + itId + "&year=2026", hrToken), 200);
        assertThat(forHr.path("departmentId").asText()).isEqualTo(itId.toString());
        assertThat(forHr.path("year").asInt()).isEqualTo(2026);
        assertThat(forHr.path("planned").asBoolean()).isTrue();
        assertThat(forHr.path("headcountLimit").asInt()).isEqualTo(5);
        assertThat(forHr.path("headcountUsed").asLong()).isEqualTo(2);
        assertThat(forHr.path("headcountRemaining").asLong()).isEqualTo(3);
        assertThat(forHr.path("salaryBudgetLimited").asBoolean()).isTrue();
        assertThat(forHr.path("salaryBudget").asLong()).isEqualTo(FIVE_BILLION_VND);
        assertThat(forHr.path("salaryBudgetUsed").asLong()).isEqualTo(600_000_000L);
        assertThat(forHr.path("salaryBudgetRemaining").asLong()).isEqualTo(4_400_000_000L);

        // The head of IT sees the people, never a salary key.
        var response = get(BASE + "/remaining?departmentId=" + itId + "&year=2026", headToken);
        JsonNode forHead = expect(response, 200);
        noStore(response);
        assertThat(forHead.path("headcountRemaining").asLong()).isEqualTo(3);
        for (String field : List.of("salaryBudgetLimited", "salaryBudget", "salaryBudgetUsed", "salaryBudgetRemaining")) {
            assertThat(forHead.has(field)).as(field).isFalse();
        }
        // ADMIN reads every requisition but has no headcount plan permission: people only, any department.
        JsonNode forAdmin = expect(get(BASE + "/remaining?departmentId=" + salesId + "&year=2026", adminToken), 200);
        assertThat(forAdmin.path("planned").asBoolean()).isFalse();
        assertThat(forAdmin.has("salaryBudget")).isFalse();
    }

    @Test
    void remainingWithoutAPlanHasNoLimitAndTheYearDefaultsToTheCurrentBusinessYear() throws Exception {
        // 00:30 on 1 Jan 2027 in Vietnam, while UTC is still in 2026.
        clock.set(Instant.parse("2026-12-31T17:30:00Z"));
        headToken = token("head@example.test");
        hrToken = token("hr@example.test");
        draft(itDevId, 4, null, LocalDate.of(2027, 2, 1));

        JsonNode result = expect(get(BASE + "/remaining?departmentId=" + itDevId, headToken), 200);
        assertThat(result.path("year").asInt()).isEqualTo(2027);
        assertThat(result.path("planned").asBoolean()).isFalse();
        assertThat(result.path("headcountLimit").isNull()).isTrue();
        assertThat(result.path("headcountRemaining").isNull()).isTrue();
        assertThat(result.path("headcountUsed").asLong()).isEqualTo(4);

        JsonNode forHr = expect(get(BASE + "/remaining?departmentId=" + itDevId, hrToken), 200);
        assertThat(forHr.path("salaryBudgetLimited").asBoolean()).isFalse();
        assertThat(forHr.has("salaryBudget")).isFalse();
        assertThat(forHr.has("salaryBudgetRemaining")).isFalse();
        assertThat(forHr.path("salaryBudgetUsed").asLong()).isZero();
    }

    @Test
    void remainingChecksParametersThenTheDepartmentThenTheScope() throws Exception {
        for (String query : List.of("", "?year=2026", "?departmentId=" + itId + "&year=1999",
                "?departmentId=" + itId + "&year=2101", "?departmentId=not-a-uuid")) {
            assertThat(expect(get(BASE + "/remaining" + query, headToken), 400).path("code").asText()).as(query)
                    .isEqualTo("VALIDATION_ERROR");
        }
        assertThat(expect(get(BASE + "/remaining?departmentId=" + UUID.randomUUID(), headToken), 404)
                .path("code").asText()).isEqualTo("DEPARTMENT_NOT_FOUND");
        // SALES is not managed by the head of IT.
        forbidden(get(BASE + "/remaining?departmentId=" + salesId, headToken));
        expect(get(BASE + "/remaining?departmentId=" + salesId, hrToken), 200);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyHeadcountPlanPermissionsReachTheHrEndpoints(Role role) throws Exception {
        UUID planId = id(post(plan(salesId, 2026, 1, null), hrToken));
        account("role@example.test", Set.of(role));
        String token = token("role@example.test");
        Set<String> grants = RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name());

        int list = get(BASE, token).statusCode();
        int one = get(BASE + "/" + planId, token).statusCode();
        int create = post(plan(itId, 2026, 1, null), token).statusCode();
        int update = put(planId, Map.of("headcountLimit", 2), token).statusCode();
        boolean reader = grants.contains("HEADCOUNT_PLANS_READ_ALL");
        boolean writer = grants.contains("HEADCOUNT_PLANS_WRITE_ALL");
        assertThat(List.of(list, one)).allMatch(status -> status == (reader ? 200 : 403));
        assertThat(create).isEqualTo(writer ? 201 : 403);
        assertThat(update).isEqualTo(writer ? 200 : 403);
        assertThat(role == Role.HR_MANAGER).isEqualTo(reader && writer);
    }

    @Test
    void anonymousCallersAndAccountsWithoutRolesCannotUseThePlans() throws Exception {
        assertThat(get(BASE, null).statusCode()).isEqualTo(401);
        assertThat(get(BASE + "/remaining?departmentId=" + itId, null).statusCode()).isEqualTo(401);
        assertThat(post(plan(itId, 2026, 1, null), null).statusCode()).isEqualTo(401);
        account("nobody@example.test", Set.of());
        String token = token("nobody@example.test");
        forbidden(get(BASE, token));
        forbidden(get(BASE + "/remaining?departmentId=" + itId, token));
        forbidden(post(plan(itId, 2026, 1, null), token));
    }

    @Test
    void twoHrManagersCreatingTheSamePlanAtOnceGetOne201AndOne409() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Holding the department FOR UPDATE makes both requests wait at their FOR SHARE, so they then run together.
            int blockerPid = lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE", itId);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<HttpResponse<String>>> responses = new ArrayList<>();
                responses.add(executor.submit(() -> post(plan(itId, 2026, 5, null), hrToken)));
                responses.add(executor.submit(() -> post(plan(itId, 2026, 6, null), otherHrToken)));
                awaitWaiters(blockerPid, 2);
                connection.rollback();
                List<Integer> statuses = new ArrayList<>();
                for (var response : responses) {
                    var result = response.get(20, TimeUnit.SECONDS);
                    statuses.add(result.statusCode());
                    if (result.statusCode() == 409) {
                        assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("HEADCOUNT_PLAN_EXISTS");
                    }
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isOne();
    }

    // Without this wait, a draft saved while no plan existed could commit after the plan, uncounted by a draft saved
    // under the plan: together they would go over it without any HR confirmation.
    @Test
    void creatingAPlanWaitsForTheRequisitionSavesInFlightOfItsDepartment() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // A requisition save holds its department FOR SHARE until it commits; this one saves 3 people.
            int savePid = lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR SHARE", itId);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, needed_by,
                        created_by, created_at, updated_at)
                    VALUES (?, ?, ?, 3, 'NEW_HEADCOUNT', DATE '2026-12-01', ?, ?, ?)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, positionId);
                statement.setObject(3, itId);
                statement.setObject(4, headId);
                statement.setTimestamp(5, Timestamp.from(START));
                statement.setTimestamp(6, Timestamp.from(START));
                statement.executeUpdate();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> post(plan(itId, 2026, 1, null), hrToken));
                awaitWaiters(savePid, 1);
                connection.commit();
                JsonNode created = expect(response.get(10, TimeUnit.SECONDS), 201);
                // The plan sees the committed draft.
                assertThat(created.path("headcountUsed").asLong()).isEqualTo(3);
                assertThat(created.path("headcountRemaining").asLong()).isEqualTo(-2);
            }
        }
    }

    @Test
    void theWritePermissionIsCheckedAgainAfterWaitingForTheActorAccountLock() throws Exception {
        UUID planId = id(post(plan(itId, 2026, 5, null), hrToken));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE", hrId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> put(planId, Map.of("headcountLimit", 9), hrToken));
                awaitWaiters(blockerPid, 1);
                jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                        + "AND permission_code = 'HEADCOUNT_PLANS_WRITE_ALL'");
                connection.commit();
                forbidden(response.get(10, TimeUnit.SECONDS));
            }
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'HEADCOUNT_PLANS_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(jdbc.queryForObject("SELECT headcount_limit FROM headcount_plans", Integer.class)).isEqualTo(5);
    }

    @Test
    void theCallerIsCheckedAgainAfterWaitingForThePlanRow() throws Exception {
        UUID planId = id(post(plan(itId, 2026, 5, null), hrToken));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // A requisition save of IT 2026 would hold the plan row like this.
            int blockerPid = lock(connection, "SELECT pg_backend_pid() FROM headcount_plans WHERE id = ? FOR UPDATE", planId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> put(planId, Map.of("headcountLimit", 9), hrToken));
                awaitWaiters(blockerPid, 1);
                jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                        + "AND permission_code = 'HEADCOUNT_PLANS_WRITE_ALL'");
                connection.commit();
                forbidden(response.get(10, TimeUnit.SECONDS));
            }
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'HEADCOUNT_PLANS_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(jdbc.queryForObject("SELECT headcount_limit FROM headcount_plans", Integer.class)).isEqualTo(5);
    }

    @Test
    void theCallerIsCheckedAgainAfterWaitingForTheDepartmentRow() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // A department edit holds the row FOR UPDATE; meanwhile the access token expires.
            int blockerPid = lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE", itId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> post(plan(itId, 2026, 5, null), hrToken));
                awaitWaiters(blockerPid, 1);
                clock.set(START.plus(Duration.ofMinutes(16)));
                connection.commit();
                var result = response.get(10, TimeUnit.SECONDS);
                assertThat(expect(result, 401).path("code").asText()).isEqualTo("SESSION_INVALID");
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();
    }

    private Map<String, Object> plan(UUID department, int year, int limit, Long budget) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("departmentId", department);
        body.put("year", year);
        body.put("headcountLimit", limit);
        body.put("salaryBudget", budget);
        return body;
    }

    private void draft(UUID department, int headcount, Long max, LocalDate neededBy) {
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason,
                    proposed_salary_max, needed_by, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'NEW_HEADCOUNT', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), positionId, department, headcount, max, neededBy, headId,
                Timestamp.from(START), Timestamp.from(START));
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Headcount test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID parent, UUID manager, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO departments (id, code, name, parent_id, manager_user_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, code, "Phòng " + code, parent, manager, active, Timestamp.from(START));
        return id;
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private UUID id(HttpResponse<String> response) {
        return UUID.fromString(expect(response, 201).path("id").asText());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(UUID.fromString(item.path("id").asText())));
        return result;
    }

    private HttpResponse<String> post(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> put(UUID id, Map<String, Object> body, String token) throws Exception {
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

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403);
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        noStore(response);
    }

    private void fieldErrors(HttpResponse<String> response, String... fields) {
        JsonNode body = expect(response, 400);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(fields.length);
        for (String field : fields) {
            assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        }
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
