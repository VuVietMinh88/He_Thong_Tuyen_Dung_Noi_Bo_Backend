package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.sql.SQLException;
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

// Task 249: a SCOPED caller (department head) creates and updates requisitions only for the departments they manage,
// including every department below them; ALL callers (ADMIN, HR_MANAGER) write for any department.
// Department tree of the fixture: IT (managed by head) > IT_DEV (lead) > IT_QA (qa); SALES (sales) is separate.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionDepartmentScopeIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // 07:00 on 7 Oct in Vietnam. Access tokens last 15 minutes from START; no test here moves the clock.
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    // Standard salary band of the fixture position, in whole VND.
    private static final long BAND_MIN = 15_000_000L;
    private static final long BAND_MAX = 25_000_000L;

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
    private String hrToken;
    private UUID headId;
    private String headToken;
    private UUID leadId;
    private String leadToken;
    private String qaToken;
    private String salesToken;
    private UUID itId;
    private UUID itDevId;
    private UUID itQaId;
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
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode adminLogin = login("admin@example.test");
        adminToken = adminLogin.path("accessToken").asText();
        UUID adminId = UUID.fromString(adminLogin.path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        leadId = account("lead@example.test", Set.of(Role.HIRING_MANAGER));
        leadToken = token("lead@example.test");
        UUID qaId = account("qa@example.test", Set.of(Role.HIRING_MANAGER));
        qaToken = token("qa@example.test");
        UUID salesManagerId = account("sales@example.test", Set.of(Role.HIRING_MANAGER));
        salesToken = token("sales@example.test");
        itId = department("IT", null, headId);
        itDevId = department("IT_DEV", itId, leadId);
        itQaId = department("IT_QA", itDevId, qaId);
        salesId = department("SALES", null, salesManagerId);
        positionId = position("DEV_JUNIOR");
    }

    @Test
    void aDepartmentHeadCreatesDraftsForTheirDepartmentAndEveryDepartmentBelowIt() throws Exception {
        UUID headIt = createdIn(itId, headToken, headId);
        UUID headDev = createdIn(itDevId, headToken, headId);
        UUID headQa = createdIn(itQaId, headToken, headId);
        // lead manages IT_DEV: IT_DEV and its child IT_QA, but not the parent IT (next test).
        UUID leadDev = createdIn(itDevId, leadToken, leadId);
        UUID leadQa = createdIn(itQaId, leadToken, leadId);
        assertThat(count()).isEqualTo(5);

        // The list follows the same scope: every draft below the caller's departments, whoever created it.
        assertThat(listIds(headToken)).containsExactlyInAnyOrder(headIt, headDev, headQa, leadDev, leadQa);
        assertThat(listIds(leadToken)).containsExactlyInAnyOrder(headDev, headQa, leadDev, leadQa);
        assertThat(listIds(qaToken)).containsExactlyInAnyOrder(headQa, leadQa);
        assertThat(listIds(salesToken)).isEmpty();
        assertThat(listIds(hrToken)).containsExactlyInAnyOrder(headIt, headDev, headQa, leadDev, leadQa);

        // The active flag decides whether a department can be chosen, not who is responsible for it: with IT_DEV
        // closed, head still reaches the open IT_QA below it.
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", itDevId);
        UUID belowClosed = createdIn(itQaId, headToken, headId);
        assertThat(listIds(headToken)).contains(belowClosed);
    }

    @Test
    void aDepartmentHeadCannotCreateADraftForAnUnrelatedOrAParentDepartment() throws Exception {
        Map<String, Object> complete = payload(positionId, salesId);
        complete.put("proposedSalaryMin", BAND_MIN);
        complete.put("proposedSalaryMax", BAND_MAX);
        complete.put("neededBy", "2026-12-31");
        complete.put("jobDescription", "Phát triển API tuyển dụng.");
        forbidden(create(complete, headToken));
        // A parent department is not below the caller's department.
        forbidden(create(payload(positionId, itId), leadToken));
        forbidden(create(payload(positionId, itId), qaToken));
        forbidden(create(payload(positionId, itDevId), qaToken));
        // Another head's department, in both directions.
        forbidden(create(payload(positionId, itQaId), salesToken));
        forbidden(create(payload(positionId, salesId), leadToken));
        assertThat(count()).isZero();
    }

    @Test
    void allScopeCallersWriteRequisitionsForEveryDepartment() throws Exception {
        UUID hrSales = createdIn(salesId, hrToken, null);
        UUID adminQa = createdIn(itQaId, adminToken, null);
        // One role with REQUISITIONS_WRITE_ALL is enough: this HIRING_MANAGER manages nothing but is also HR_MANAGER.
        UUID both = account("both@example.test", Set.of(Role.HIRING_MANAGER, Role.HR_MANAGER));
        UUID bothSales = createdIn(salesId, token("both@example.test"), both);
        assertThat(listIds(hrToken)).containsExactlyInAnyOrder(hrSales, adminQa, bothSales);

        // ALL callers may also move any draft between any departments.
        UUID headDraft = createdIn(itId, headToken, headId);
        assertThat(put(headDraft, changed(positionId, salesId), hrToken).statusCode()).isEqualTo(200);
        assertThat(row(headDraft).get("department_id")).isEqualTo(salesId);
        assertThat(put(headDraft, changed(positionId, itQaId), adminToken).statusCode()).isEqualTo(200);
        assertThat(row(headDraft).get("department_id")).isEqualTo(itQaId);
        // The creator stays head although HR and ADMIN moved the draft.
        assertThat(row(headDraft).get("created_by")).isEqualTo(headId);
    }

    @Test
    void aDepartmentHeadMovesADraftOnlyBetweenDepartmentsTheyManage() throws Exception {
        UUID draft = createdIn(itId, headToken, headId);
        JsonNode moved = expect(put(draft, changed(positionId, itQaId), headToken), 200);
        assertThat(moved.path("departmentId").asText()).isEqualTo(itQaId.toString());

        // Moving it out of head's departments is refused and the row stays as it was.
        Map<String, Object> before = row(draft);
        forbidden(put(draft, changed(positionId, salesId), headToken));
        assertThat(row(draft)).isEqualTo(before);

        // The draft is now in IT_QA, so lead reaches it too, but may not move it up to IT, above lead's department.
        forbidden(put(draft, changed(positionId, itId), leadToken));
        assertThat(row(draft)).isEqualTo(before);
        assertThat(expect(put(draft, changed(positionId, itDevId), leadToken), 200).path("departmentId").asText())
                .isEqualTo(itDevId.toString());
        // IT_DEV is below IT, so head still reads it; SALES never does.
        assertThat(expect(get(draft, headToken), 200).path("departmentId").asText()).isEqualTo(itDevId.toString());
        forbidden(get(draft, salesToken));
    }

    @Test
    void aDepartmentHeadCannotReadEditOrTakeOverADraftOfAnotherDepartment() throws Exception {
        UUID salesDraft = createdIn(salesId, hrToken, null);
        Map<String, Object> before = row(salesDraft);

        forbidden(get(salesDraft, headToken));
        assertThat(listIds(headToken)).doesNotContain(salesDraft);
        // Choosing head's own department in the body does not help: the saved department is checked first.
        forbidden(put(salesDraft, changed(positionId, itId), headToken));
        assertThat(row(salesDraft)).isEqualTo(before);

        // The SALES head reads and edits it, but cannot hand it over to IT either.
        assertThat(listIds(salesToken)).containsExactly(salesDraft);
        forbidden(put(salesDraft, changed(positionId, itId), salesToken));
        assertThat(row(salesDraft)).isEqualTo(before);
        assertThat(expect(put(salesDraft, changed(positionId, salesId), salesToken), 200).path("headcount").asInt())
                .isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleChoosesOnlyTheDepartmentsItsRequisitionScopeAllows(Role role) throws Exception {
        UUID caller = account("role@example.test", Set.of(role));
        String token = token("role@example.test");
        UUID own = department("OWN", null, caller);
        Set<String> grants = RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name());
        boolean all = grants.contains("REQUISITIONS_WRITE_ALL");
        boolean writer = all || grants.contains("REQUISITIONS_WRITE_SCOPED");
        // Written out as well, so this test does not only repeat the seed.
        assertThat(all).isEqualTo(role == Role.ADMIN || role == Role.HR_MANAGER);
        assertThat(writer).isEqualTo(role != Role.INTERVIEWER);

        // Create: every writer may choose the department it manages; only ALL may choose another one.
        // This includes a RECRUITER or APPROVER that HR made a department manager: the scope follows the permission
        // codes and the department tree, not the role name (backend interpretation, BA/PO question 2).
        var inOwn = create(payload(positionId, own), token);
        if (writer) { expect(inOwn, 201); } else { forbidden(inOwn); }
        var inSales = create(payload(positionId, salesId), token);
        if (all) { expect(inSales, 201); } else { forbidden(inSales); }

        // Update a draft of OWN: keeping it there works for every writer; moving it to SALES only for ALL.
        UUID draft = createdIn(own, hrToken, null);
        var keep = put(draft, changed(positionId, own), token);
        if (writer) { expect(keep, 200); } else { forbidden(keep); }
        Map<String, Object> before = row(draft);
        var move = put(draft, changed(positionId, salesId), token);
        if (all) {
            expect(move, 200);
            assertThat(row(draft).get("department_id")).isEqualTo(salesId);
        } else {
            forbidden(move);
            assertThat(row(draft)).isEqualTo(before);
        }
    }

    // Backend interpretation (waiting for BA/PO): REQUISITIONS_*_SCOPED means "departments I manage" for every
    // SCOPED role, so a RECRUITER or APPROVER who manages no department cannot create any requisition.
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"RECRUITER", "APPROVER", "HIRING_MANAGER"})
    void scopedCallersWhoManageNoDepartmentCannotCreateARequisitionAnywhere(Role role) throws Exception {
        account("nobody@example.test", Set.of(role));
        String token = token("nobody@example.test");
        for (UUID department : List.of(itId, itDevId, itQaId, salesId)) {
            forbidden(create(payload(positionId, department), token));
        }
        assertThat(count()).isZero();
    }

    // Order of checks for a department outside the caller's scope: errors about the body itself, and an unknown or
    // closed department, come first (like 404 before 403); the 403 comes before the salary band is compared.
    @Test
    void theChosenDepartmentIsCheckedAfterItExistsAndIsActiveAndBeforeTheSalaryBand() throws Exception {
        Map<String, Object> missingHeadcount = payload(positionId, salesId);
        missingHeadcount.remove("headcount");
        error(create(missingHeadcount, headToken), "VALIDATION_ERROR", "headcount");
        Map<String, Object> inverted = payload(positionId, salesId);
        inverted.put("proposedSalaryMin", BAND_MAX);
        inverted.put("proposedSalaryMax", BAND_MIN);
        error(create(inverted, headToken), "REQUISITION_SALARY_RANGE_INVALID", "proposedSalaryMax");
        Map<String, Object> yesterday = payload(positionId, salesId);
        yesterday.put("neededBy", "2026-10-06");
        error(create(yesterday, headToken), "NEEDED_BY_IN_PAST", "neededBy");
        error(create(payload(UUID.randomUUID(), salesId), headToken), "INVALID_REQUISITION_POSITION", "positionId");
        // An unknown department is a form error for every caller, not a 403.
        error(create(payload(positionId, UUID.randomUUID()), headToken), "INVALID_REQUISITION_DEPARTMENT", "departmentId");
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", salesId);
        error(create(payload(positionId, salesId), headToken), "REQUISITION_DEPARTMENT_INACTIVE", "departmentId");
        jdbc.update("UPDATE departments SET active = TRUE WHERE id = ?", salesId);
        // Outside the band without a justification: the answer is 403, the band is never compared.
        Map<String, Object> offBand = payload(positionId, salesId);
        offBand.put("proposedSalaryMin", BAND_MIN - 1);
        forbidden(create(offBand, headToken));
        assertThat(count()).isZero();

        // The same order when a draft would be moved out of the caller's departments.
        UUID draft = createdIn(itId, headToken, headId);
        Map<String, Object> before = row(draft);
        Map<String, Object> moveYesterday = changed(positionId, salesId);
        moveYesterday.put("neededBy", "2026-10-06");
        error(put(draft, moveYesterday, headToken), "NEEDED_BY_IN_PAST", "neededBy");
        Map<String, Object> moveOffBand = changed(positionId, salesId);
        moveOffBand.put("proposedSalaryMax", BAND_MAX + 1);
        forbidden(put(draft, moveOffBand, headToken));
        assertThat(row(draft)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
    }

    // The scope of the chosen department is read after its row is locked FOR SHARE. Here another transaction is
    // moving IT_DEV from IT to SALES: the request waits for it, and then follows its outcome. If the scope were read
    // before waiting, head would still be allowed after the commit.
    @ParameterizedTest
    @CsvSource({"POST, true", "POST, false", "PUT, true", "PUT, false"})
    void theChosenDepartmentIsCheckedAfterWaitingForItsRowLock(String method, boolean commit) throws Exception {
        UUID draft = method.equals("PUT") ? createdIn(itId, headToken, headId) : null;
        Map<String, Object> before = draft == null ? null : row(draft);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = backendPid(connection);
            assertThat(update(connection, "UPDATE departments SET parent_id = ? WHERE id = ?", salesId, itDevId))
                    .isEqualTo(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> draft == null
                        ? create(changed(positionId, itDevId), headToken)
                        : put(draft, changed(positionId, itDevId), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (commit) { connection.commit(); } else { connection.rollback(); }
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (commit) {
                        // IT_DEV now belongs to SALES, so it is outside head's departments.
                        forbidden(result);
                    } else {
                        assertThat(expect(result, draft == null ? 201 : 200).path("departmentId").asText())
                                .isEqualTo(itDevId.toString());
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        if (draft == null) {
            assertThat(count()).isEqualTo(commit ? 0 : 1);
        } else if (commit) {
            assertThat(row(draft)).isEqualTo(before);
        } else {
            assertThat(row(draft).get("department_id")).isEqualTo(itDevId);
        }
    }

    private Map<String, Object> payload(UUID position, UUID department) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positionId", position);
        result.put("departmentId", department);
        result.put("headcount", 2);
        result.put("reason", "NEW_HEADCOUNT");
        return result;
    }

    // A valid update body that differs from payload(), so a successful PUT is visible in the row.
    private Map<String, Object> changed(UUID position, UUID department) {
        Map<String, Object> result = payload(position, department);
        result.put("headcount", 5);
        result.put("reason", "REPLACEMENT");
        return result;
    }

    // Creates a draft that must succeed, checks where it was saved and by whom, and returns its id.
    private UUID createdIn(UUID department, String token, UUID expectedCreator) throws Exception {
        var response = create(payload(positionId, department), token);
        JsonNode created = expect(response, 201);
        assertThat(created.path("departmentId").asText()).isEqualTo(department.toString());
        if (expectedCreator != null) {
            assertThat(created.path("createdBy").asText()).isEqualTo(expectedCreator.toString());
        }
        return UUID.fromString(created.path("id").asText());
    }

    private List<UUID> listIds(String token) throws Exception {
        List<UUID> result = new ArrayList<>();
        expect(request("GET", BASE + "?size=100", null, token), 200).path("items")
                .forEach(item -> result.add(UUID.fromString(item.path("id").asText())));
        return result;
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Requisition test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID parent, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO departments (id, code, name, parent_id, manager_user_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?)
                """, id, code, "Phòng " + code, parent, manager, Timestamp.from(START));
        return id;
    }

    private UUID position(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Lập trình viên', 'Junior', ?, ?, TRUE, ?, ?)
                """, id, code, BAND_MIN, BAND_MAX, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", id);
    }

    private HttpResponse<String> create(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> put(UUID id, Map<String, Object> body, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> get(UUID id, String token) throws Exception {
        return request("GET", BASE + "/" + id, null, token);
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private String token(String email) throws Exception {
        return login(email).path("accessToken").asText();
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

    // A 400 with this code whose fieldErrors names exactly this request field.
    private void error(HttpResponse<String> response, String code, String field) {
        JsonNode body = expect(response, 400);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        noStore(response);
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403);
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        noStore(response);
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            return statement.executeUpdate();
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
