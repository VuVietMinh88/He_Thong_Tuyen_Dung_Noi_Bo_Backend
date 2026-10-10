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

import static org.assertj.core.api.Assertions.assertThat;

// Task 278: POST /api/v1/requisitions/{id}/copy saves a new draft with the content of a requisition the caller may read.
// Task 279: the copy is a fresh draft: nothing of the source's life (creator, times, HR exceptions, a passed date).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionCopyIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 10 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
    // Leading spaces, blank lines and the final line break must be copied exactly.
    private static final String JOB_DESCRIPTION = "  Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL\n";
    private static final String REQUIREMENTS = "Tối thiểu 2 năm kinh nghiệm Java.\r\nĐọc hiểu tài liệu tiếng Anh.";
    // Every column a copy takes from its source.
    private static final List<String> CONTENT = List.of("position_id", "department_id", "headcount", "reason",
            "proposed_salary_min", "proposed_salary_max", "salary_justification", "needed_by", "job_description",
            "candidate_requirements");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private UUID hrId;
    private String hrToken;
    private UUID headId;
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
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
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
    void copiesTheWholeContentIntoANewDraftOfTheCallerAndLeavesTheSourceUnchanged() throws Exception {
        UUID source = id(create(full(itId), headToken));
        Map<String, Object> sourceBefore = row(source);
        clock.set(START.plus(Duration.ofMinutes(5)).plusNanos(987_654_321));
        Instant copiedAt = START.plus(Duration.ofMinutes(5)).plusNanos(987_654_000);

        var response = copy(source, null, headToken);
        JsonNode copy = expect(response, 201);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        UUID id = UUID.fromString(copy.path("id").asText());
        assertThat(id).isNotEqualTo(source);
        assertThat(copy.path("jobDescription").asText()).isEqualTo(JOB_DESCRIPTION);
        assertThat(copy.path("candidateRequirements").asText()).isEqualTo(REQUIREMENTS);
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.path("createdBy").asText()).isEqualTo(headId.toString());
        assertThat(Instant.parse(copy.path("createdAt").asText())).isEqualTo(copiedAt);
        assertThat(Instant.parse(copy.path("updatedAt").asText())).isEqualTo(copiedAt);

        Map<String, Object> copied = row(id);
        Map<String, Object> original = row(source);
        for (String column : CONTENT) {
            assertThat(copied.get(column)).as(column).isEqualTo(original.get(column));
        }
        assertThat(original).isEqualTo(sourceBefore);
        // GET returns the copy as POST returned it.
        assertThat(expect(get(BASE + "/" + id, headToken), 200)).isEqualTo(copy);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void hrCopiesARequisitionOfAnyDepartmentAndBecomesTheCreatorOfTheCopy() throws Exception {
        UUID source = id(create(full(itId), headToken));
        JsonNode copy = expect(copy(source, null, hrToken), 201);
        assertThat(copy.path("createdBy").asText()).isEqualTo(hrId.toString());
        assertThat(copy.path("departmentId").asText()).isEqualTo(itId.toString());
        // The head still sees the copy: drafts belong to the department, not to their creator.
        expect(get(BASE + "/" + copy.path("id").asText(), headToken), 200);
    }

    @Test
    void theCallerMustBeAllowedToReadTheSource() throws Exception {
        UUID sales = id(create(full(salesId), hrToken));
        // SALES is outside the head's scope: 403, nothing saved.
        assertThat(expect(copy(sales, null, headToken), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(expect(copy(UUID.randomUUID(), null, headToken), 404).path("code").asText())
                .isEqualTo("REQUISITION_NOT_FOUND");
        assertThat(expect(request("POST", BASE + "/not-a-uuid/copy", null, headToken), 400).path("code").asText())
                .isEqualTo("VALIDATION_ERROR");
        // WRITE never implies READ: a writer without REQUISITIONS_READ_* cannot copy what it cannot read, and cannot
        // even tell an existing id from an unknown one.
        UUID it = id(create(full(itId), headToken));
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_READ_SCOPED'");
        try {
            assertThat(expect(copy(it, null, headToken), 403).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(expect(copy(UUID.randomUUID(), null, headToken), 403).path("code").asText())
                    .isEqualTo("FORBIDDEN");
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HIRING_MANAGER', 'REQUISITIONS_READ_SCOPED')");
        }
        // An interviewer has no requisition permission at all.
        account("interviewer@example.test", Set.of(Role.INTERVIEWER));
        assertThat(copy(it, null, token("interviewer@example.test")).statusCode()).isEqualTo(403);
        assertThat(copy(it, null, null).statusCode()).isEqualTo(401);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void readAndWriteScopesAreBothApplied() throws Exception {
        UUID sales = id(create(full(salesId), hrToken));
        // The head of IT may read every requisition but still only write for IT: copying SALES is refused.
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HIRING_MANAGER', 'REQUISITIONS_READ_ALL')");
        try {
            expect(get(BASE + "/" + sales, headToken), 200);
            assertThat(expect(copy(sales, null, headToken), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_READ_ALL'");
        }
        assertThat(count()).isOne();
    }

    @Test
    void theCopyIsCheckedLikeANewDraft() throws Exception {
        UUID source = id(create(full(itId), headToken));

        jdbc.update("UPDATE positions SET active = FALSE");
        assertThat(expect(copy(source, null, headToken), 400).path("code").asText())
                .isEqualTo("REQUISITION_POSITION_INACTIVE");
        jdbc.update("UPDATE positions SET active = TRUE");

        // The standard band changed since the source was saved: its proposal now needs a justification it lacks.
        UUID unjustified = id(create(body(itId, 2, 20_000_000L, 25_000_000L, null), headToken));
        jdbc.update("UPDATE positions SET salary_max = 22000000");
        assertThat(expect(copy(unjustified, null, headToken), 400).path("code").asText())
                .isEqualTo("SALARY_JUSTIFICATION_REQUIRED");
        jdbc.update("UPDATE positions SET salary_max = 25000000");

        // A row written outside the API, over the 999 people a requisition may ask for.
        jdbc.update("UPDATE recruitment_requisitions SET headcount = 1000 WHERE id = ?", unjustified);
        JsonNode invalid = expect(copy(unjustified, null, headToken), 400);
        assertThat(invalid.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.path("fieldErrors").path("headcount").asText()).isEqualTo("Số lượng cần tuyển tối đa 999 người.");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void theCopyCountsAgainstTheHeadcountPlanAndOnlyHrMayGoOverItWithAReason() throws Exception {
        UUID source = id(create(body(itId, 2, null, null, "2026-12-01"), headToken));
        jdbc.update("""
                INSERT INTO headcount_plans (id, department_id, plan_year, headcount_limit, created_at, updated_at,
                    updated_by)
                VALUES (?, ?, 2026, 3, ?, ?, ?)
                """, UUID.randomUUID(), itId, Timestamp.from(START), Timestamp.from(START), hrId);

        assertThat(expect(copy(source, null, headToken), 409).path("code").asText())
                .isEqualTo("HEADCOUNT_LIMIT_EXCEEDED");
        assertThat(expect(copy(source, "Cần gấp", headToken), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        JsonNode copy = expect(copy(source, "Mở rộng dự án", hrToken), 201);
        assertThat(jdbc.queryForObject("SELECT requisition_id FROM requisition_headcount_overrides", UUID.class))
                .isEqualTo(UUID.fromString(copy.path("id").asText()));
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void theBodyIsOptionalAndOnlyCarriesTheOverrideReason() throws Exception {
        UUID source = id(create(full(itId), headToken));
        expect(request("POST", BASE + "/" + source + "/copy", null, headToken), 201);
        expect(request("POST", BASE + "/" + source + "/copy", "{}", headToken), 201);
        expect(request("POST", BASE + "/" + source + "/copy", "null", headToken), 201);
        // What a browser fetch() sends without options: no body and no Content-Type.
        var bare = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getRequiredProperty("local.server.port") + BASE + "/" + source + "/copy"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + headToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        expect(client.send(bare, HttpResponse.BodyHandlers.ofString()), 201);
        for (String body : List.of("{\"headcount\":5}", "{\"departmentId\":\"" + salesId + "\"}", "[]", "{")) {
            assertThat(expect(request("POST", BASE + "/" + source + "/copy", body, headToken), 400)
                    .path("code").asText()).as(body).isEqualTo("INVALID_JSON");
        }
        JsonNode tooLong = expect(copy(source, "a".repeat(1_001), hrToken), 400);
        assertThat(tooLong.path("fieldErrors").path("headcountOverrideReason").asText())
                .isEqualTo("Lý do vượt định biên tối đa 1.000 ký tự.");
        assertThat(count()).isEqualTo(5);
    }

    @Test
    void aCopyStartsAsAFreshDraftWithoutTheHistoryOfItsSource() throws Exception {
        // The source went over the plan with the HR manager's confirmation, so it has one exception in its history.
        jdbc.update("""
                INSERT INTO headcount_plans (id, department_id, plan_year, headcount_limit, created_at, updated_at,
                    updated_by)
                VALUES (?, ?, 2026, 0, ?, ?, ?)
                """, UUID.randomUUID(), itId, Timestamp.from(START), Timestamp.from(START), hrId);
        Map<String, Object> over = full(itId);
        over.put("headcountOverrideReason", "Dự án gấp");
        UUID source = id(create(over, hrToken));
        // HR then raises the plan, so a copy fits without any confirmation.
        jdbc.update("UPDATE headcount_plans SET headcount_limit = 10");
        clock.set(START.plus(Duration.ofMinutes(5)));

        JsonNode copy = expect(copy(source, null, headToken), 201);
        UUID id = UUID.fromString(copy.path("id").asText());
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.path("createdBy").asText()).isEqualTo(headId.toString());
        assertThat(Instant.parse(copy.path("createdAt").asText())).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(expect(get(BASE + "/" + id + "/headcount-overrides", headToken), 200).path("items").isEmpty())
                .isTrue();
        assertThat(expect(get(BASE + "/" + source + "/headcount-overrides", headToken), 200).path("items").size())
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_headcount_overrides WHERE requisition_id = ?",
                Integer.class, id)).isZero();

        // The source's confirmation does not carry over: when the plan is full again, a copy needs its own.
        jdbc.update("UPDATE headcount_plans SET headcount_limit = 4");
        assertThat(expect(copy(source, null, headToken), 409).path("code").asText())
                .isEqualTo("HEADCOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void aNeededByDateThatHasPassedIsLeftEmptyButTodayAndLaterAreCopied() throws Exception {
        UUID passed = id(create(body(itId, 1, null, null, "2026-10-15"), headToken));
        // Yesterday in Vietnam, but still today in UTC: only the business date makes it a passed date.
        UUID yesterday = id(create(body(itId, 1, null, null, "2026-10-19"), headToken));
        UUID today = id(create(body(itId, 1, null, null, "2026-10-20"), headToken));
        UUID later = id(create(body(itId, 1, null, null, "2026-12-01"), headToken));
        // 00:30 on 20 Oct 2026 in Vietnam, while UTC is still on 19 Oct: "today" is the business date.
        clock.set(Instant.parse("2026-10-19T17:30:00Z"));
        headToken = token("head@example.test");

        JsonNode fromPassed = expect(copy(passed, null, headToken), 201);
        assertThat(fromPassed.path("neededBy").isNull()).isTrue();
        assertThat(expect(copy(yesterday, null, headToken), 201).path("neededBy").isNull()).isTrue();
        assertThat(expect(copy(today, null, headToken), 201).path("neededBy").asText()).isEqualTo("2026-10-20");
        assertThat(expect(copy(later, null, headToken), 201).path("neededBy").asText()).isEqualTo("2026-12-01");
        // The source keeps its date.
        assertThat(jdbc.queryForObject("SELECT needed_by::text FROM recruitment_requisitions WHERE id = ?",
                String.class, passed)).isEqualTo("2026-10-15");
    }

    private Map<String, Object> full(UUID department) {
        Map<String, Object> body = body(department, 2, 15_000_000L, 25_000_000L, "2026-12-31");
        body.put("reason", "REPLACEMENT");
        body.put("salaryJustification", "Cần người có kinh nghiệm.");
        body.put("jobDescription", JOB_DESCRIPTION);
        body.put("candidateRequirements", REQUIREMENTS);
        return body;
    }

    private Map<String, Object> body(UUID department, int headcount, Long min, Long max, String neededBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", department);
        body.put("headcount", headcount);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", min);
        body.put("proposedSalaryMax", max);
        body.put("neededBy", neededBy);
        return body;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", id);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Copy test", fixturePasswordHash, roles, START)).getId();
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

    private HttpResponse<String> copy(UUID source, String reason, String token) throws Exception {
        String body = reason == null ? null : json.writeValueAsString(Map.of("headcountOverrideReason", reason));
        return request("POST", BASE + "/" + source + "/copy", body, token);
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
}
