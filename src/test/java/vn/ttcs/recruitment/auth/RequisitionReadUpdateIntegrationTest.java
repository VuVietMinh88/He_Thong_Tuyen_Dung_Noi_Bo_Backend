package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 245: GET /api/v1/requisitions, GET /api/v1/requisitions/{id} and PUT /api/v1/requisitions/{id}.
// Department tree of the fixture: IT (managed by head) > IT_DEV (lead) > IT_QA (qa); SALES (sales) is separate.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionReadUpdateIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START, so every test keeps the clock inside that window.
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final long THREE_BILLION_VND = 3_000_000_000L;
    private static final List<String> RESPONSE_FIELDS = List.of("id", "positionId", "departmentId", "headcount",
            "reason", "proposedSalaryMin", "proposedSalaryMax", "salaryJustification", "neededBy", "jobDescription",
            "candidateRequirements", "status", "createdBy", "createdAt", "updatedAt");
    private static final List<String> PAGE_FIELDS = List.of("items", "page", "size", "totalElements", "totalPages");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;
    private UUID headId;
    private String headToken;
    private UUID leadId;
    private String leadToken;
    private String hrToken;
    private UUID itId;
    private UUID itDevId;
    private UUID itQaId;
    private UUID salesId;
    private UUID positionId;
    private UUID otherPositionId;

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
        adminId = UUID.fromString(adminLogin.path("user").path("id").asText());
        adminToken = adminLogin.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        leadId = account("lead@example.test", Set.of(Role.HIRING_MANAGER));
        leadToken = token("lead@example.test");
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        UUID qaId = account("qa@example.test", Set.of(Role.HIRING_MANAGER));
        UUID salesManagerId = account("sales@example.test", Set.of(Role.HIRING_MANAGER));
        itId = department("IT", null, headId);
        itDevId = department("IT_DEV", itId, leadId);
        itQaId = department("IT_QA", itDevId, qaId);
        salesId = department("SALES", null, salesManagerId);
        positionId = position("DEV_JUNIOR");
        otherPositionId = position("QA_SENIOR");
    }

    @Test
    void getReturnsTheSavedDraftExactlyAsTheCreateResponseShowedIt() throws Exception {
        Map<String, Object> body = payload(positionId, itId);
        body.put("proposedSalaryMin", 2_000_000_000L);
        body.put("proposedSalaryMax", THREE_BILLION_VND);
        body.put("salaryJustification", "Cần người có kinh nghiệm quản lý dự án lớn.");
        body.put("neededBy", "2026-12-31");
        body.put("jobDescription", "  Phát triển API tuyển dụng.\n\n- Spring Boot\n");
        body.put("candidateRequirements", "Tối thiểu 2 năm kinh nghiệm Java.");
        JsonNode created = createDraft(body, headToken);
        UUID id = id(created);

        var response = request("GET", BASE + "/" + id, null, headToken);
        JsonNode detail = expect(response, 200);
        noStore(response);
        assertThat(detail).isEqualTo(created);
        assertExactFields(detail);
        // ALL callers read the same draft with the same content.
        assertThat(expect(request("GET", BASE + "/" + id, null, hrToken), 200)).isEqualTo(created);
        assertThat(expect(request("GET", BASE + "/" + id, null, adminToken), 200)).isEqualTo(created);
    }

    @Test
    void allScopeCallersListEveryRequisitionNewestFirstPageByPage() throws Exception {
        clock.set(START.plusSeconds(1));
        UUID oldest = id(createDraft(payload(positionId, itId), headToken));
        clock.set(START.plusSeconds(2));
        UUID middle = id(createDraft(payload(positionId, salesId), hrToken));
        clock.set(START.plusSeconds(3));
        UUID newest = id(createDraft(payload(positionId, itQaId), hrToken));

        var response = request("GET", BASE + "?page=0&size=2", null, hrToken);
        JsonNode first = expect(response, 200);
        noStore(response);
        assertPageFields(first);
        assertThat(ids(first)).containsExactly(newest, middle);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("totalPages").asLong()).isEqualTo(2);
        first.path("items").forEach(this::assertListItemFields);

        JsonNode second = expect(request("GET", BASE + "?page=1&size=2", null, hrToken), 200);
        assertThat(ids(second)).containsExactly(oldest);
        assertThat(second.path("totalElements").asLong()).isEqualTo(3);
        JsonNode beyond = expect(request("GET", BASE + "?page=5&size=2", null, hrToken), 200);
        assertThat(beyond.path("items").size()).isZero();
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(3);

        // Defaults: page 0, 20 per page. ADMIN also has REQUISITIONS_READ_ALL.
        JsonNode defaults = expect(request("GET", BASE, null, adminToken), 200);
        assertThat(ids(defaults)).containsExactly(newest, middle, oldest);
        assertThat(defaults.path("page").asInt()).isZero();
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(defaults.path("totalPages").asLong()).isEqualTo(1);
        JsonNode listItem = defaults.path("items").path(0);
        JsonNode detail = expect(request("GET", BASE + "/" + newest, null, adminToken), 200);
        assertListItemFields(listItem);
        RESPONSE_FIELDS.forEach(field -> assertThat(listItem.path(field)).as(field).isEqualTo(detail.path(field)));
    }

    @Test
    void listMetricsFollowBusinessMidnightAndKeepMissingDeadlinesEmpty() throws Exception {
        Map<String, Object> due = payload(positionId, itId);
        due.put("neededBy", "2026-10-08");
        UUID dueId = id(createDraft(due, headToken));

        clock.set(Instant.parse("2026-10-07T16:30:00Z")); // 23:30 in Vietnam on October 7.
        String token = token("hr@example.test");
        JsonNode beforeMidnight = expect(request("GET", BASE, null, token), 200).path("items").path(0);
        assertThat(id(beforeMidnight)).isEqualTo(dueId);
        assertThat(beforeMidnight.path("daysOpen").asLong()).isZero();
        assertThat(beforeMidnight.path("daysUntilNeededBy").asLong()).isEqualTo(1);
        assertThat(beforeMidnight.path("overdue").asBoolean()).isFalse();

        clock.set(Instant.parse("2026-10-07T17:30:00Z")); // 00:30 in Vietnam on the due date.
        token = token("hr@example.test");
        JsonNode onDueDate = expect(request("GET", BASE, null, token), 200).path("items").path(0);
        assertThat(onDueDate.path("daysOpen").asLong()).isEqualTo(1);
        assertThat(onDueDate.path("daysUntilNeededBy").asLong()).isZero();
        assertThat(onDueDate.path("overdue").asBoolean()).isFalse();

        clock.set(Instant.parse("2026-10-08T17:30:00Z")); // 00:30 in Vietnam the following day.
        token = token("hr@example.test");
        JsonNode overdue = expect(request("GET", BASE, null, token), 200).path("items").path(0);
        assertThat(overdue.path("daysOpen").asLong()).isEqualTo(2);
        assertThat(overdue.path("daysUntilNeededBy").asLong()).isEqualTo(-1);
        assertThat(overdue.path("overdue").asBoolean()).isTrue();

        UUID noDeadlineId = id(createDraft(payload(positionId, itId), token));
        JsonNode noDeadline = expect(request("GET", BASE, null, token), 200).path("items").path(0);
        assertThat(id(noDeadline)).isEqualTo(noDeadlineId);
        assertThat(noDeadline.path("daysOpen").asLong()).isZero();
        assertThat(noDeadline.path("daysUntilNeededBy").isNull()).isTrue();
        assertThat(noDeadline.path("overdue").asBoolean()).isFalse();
        assertExactFields(expect(request("GET", BASE + "/" + noDeadlineId, null, token), 200));
    }

    @Test
    void scopedCallersSeeOnlyRequisitionsOfTheDepartmentsTheyManageIncludingSubDepartments() throws Exception {
        // All four drafts are created by HR, so the result cannot come from "who created it".
        clock.set(START.plusSeconds(1));
        UUID it = id(createDraft(payload(positionId, itId), hrToken));
        clock.set(START.plusSeconds(2));
        UUID dev = id(createDraft(payload(positionId, itDevId), hrToken));
        clock.set(START.plusSeconds(3));
        UUID qa = id(createDraft(payload(positionId, itQaId), hrToken));
        clock.set(START.plusSeconds(4));
        UUID sales = id(createDraft(payload(positionId, salesId), hrToken));

        // head manages IT, so also IT_DEV (child) and IT_QA (grandchild), but not SALES.
        JsonNode headPage = expect(request("GET", BASE, null, headToken), 200);
        assertThat(ids(headPage)).containsExactly(qa, dev, it);
        assertThat(headPage.path("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(expect(request("GET", BASE + "?status=DRAFT&size=2", null, headToken), 200)))
                .containsExactly(qa, dev);
        for (UUID visible : List.of(it, dev, qa)) {
            assertThat(id(expect(request("GET", BASE + "/" + visible, null, headToken), 200))).isEqualTo(visible);
        }
        forbidden(request("GET", BASE + "/" + sales, null, headToken));

        // lead manages IT_DEV only: its sub-department is visible, the parent IT is not.
        JsonNode leadPage = expect(request("GET", BASE, null, leadToken), 200);
        assertThat(ids(leadPage)).containsExactly(qa, dev);
        assertThat(leadPage.path("totalElements").asLong()).isEqualTo(2);
        forbidden(request("GET", BASE + "/" + it, null, leadToken));
        forbidden(request("GET", BASE + "/" + sales, null, leadToken));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"RECRUITER", "APPROVER", "HIRING_MANAGER"})
    void scopedCallersWhoManageNoDepartmentSeeAnEmptyListAndCannotOpenOrEditAnything(Role role) throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        Map<String, Object> before = row(draft);
        account("nobody@example.test", Set.of(role));
        String token = token("nobody@example.test");

        JsonNode page = expect(request("GET", BASE, null, token), 200);
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("totalElements").asLong()).isZero();
        assertThat(page.path("totalPages").asLong()).isZero();
        forbidden(request("GET", BASE + "/" + draft, null, token));
        forbidden(put(draft, changed(positionId, itId), token));
        assertThat(row(draft)).isEqualTo(before);
    }

    @Test
    void accessFollowsTheDepartmentManagerNotTheCreatorOfTheDraft() throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        Map<String, Object> before = row(draft);
        // HR hands IT over to lead. Department tree changes are tested with the department API; SQL is enough here.
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", leadId, itId);

        assertThat(expect(request("GET", BASE, null, headToken), 200).path("items").size()).isZero();
        forbidden(request("GET", BASE + "/" + draft, null, headToken));
        forbidden(put(draft, changed(positionId, itId), headToken));
        assertThat(row(draft)).isEqualTo(before);

        // The new manager now reads and edits it; the creator stays head.
        assertThat(ids(expect(request("GET", BASE, null, leadToken), 200))).containsExactly(draft);
        JsonNode updated = expect(put(draft, changed(positionId, itId), leadToken), 200);
        assertThat(updated.path("headcount").asInt()).isEqualTo(5);
        assertThat(updated.path("createdBy").asText()).isEqualTo(headId.toString());
    }

    @Test
    void validatesTheStatusFilterAndPagingParameters() throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        assertThat(ids(expect(request("GET", BASE + "?status=DRAFT", null, hrToken), 200))).containsExactly(draft);
        // An empty status is the same as leaving the filter out.
        assertThat(ids(expect(request("GET", BASE + "?status=", null, hrToken), 200))).containsExactly(draft);
        assertThat(expect(request("GET", BASE + "?size=100", null, hrToken), 200).path("size").asInt()).isEqualTo(100);

        // Status codes are case-sensitive, and only statuses that exist are accepted.
        for (String query : List.of("status=draft", "status=SUBMITTED", "page=abc", "size=1.5", "page=2147483648")) {
            var response = request("GET", BASE + "?" + query, null, hrToken);
            JsonNode error = expect(response, 400);
            assertThat(error.path("code").asText()).as(query).isEqualTo("VALIDATION_ERROR");
            assertThat(error.path("message").asText()).as(query).isEqualTo("Tham số đường dẫn hoặc bộ lọc không hợp lệ.");
            noStore(response);
        }
        for (String query : List.of("page=-1", "size=0", "size=101")) {
            var response = request("GET", BASE + "?" + query, null, hrToken);
            JsonNode error = expect(response, 400);
            assertThat(error.path("code").asText()).as(query).isEqualTo("VALIDATION_ERROR");
            assertThat(error.path("message").asText()).as(query).isEqualTo("Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.");
            noStore(response);
        }

        // page * size is the SQL OFFSET. Up to Integer.MAX_VALUE it is just a page beyond the end, for ALL (HR) and
        // for a SCOPED caller who manages a department (head); above it the request is a 400, not a 500.
        for (String token : List.of(hrToken, headToken)) {
            for (String query : List.of("page=21474836&size=100", "page=2147483647&size=1")) {
                JsonNode beyond = expect(request("GET", BASE + "?" + query, null, token), 200);
                assertThat(beyond.path("items").size()).as(query).isZero();
                assertThat(beyond.path("totalElements").asLong()).as(query).isEqualTo(1);
            }
            for (String query : List.of("page=21474837&size=100", "page=2147483647&size=2", "page=2147483647&size=100")) {
                var response = request("GET", BASE + "?" + query, null, token);
                JsonNode error = expect(response, 400);
                assertThat(error.path("code").asText()).as(query).isEqualTo("VALIDATION_ERROR");
                assertThat(error.path("message").asText()).as(query).isEqualTo("Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.");
                noStore(response);
            }
        }
    }

    @Test
    void unknownRequisitionIsNotFoundAndAMalformedIdIsABadRequest() throws Exception {
        UUID unknown = UUID.randomUUID();
        // The same 404 for ALL and SCOPED callers: there is no department to compare for a missing requisition.
        for (String token : List.of(hrToken, headToken)) {
            var response = request("GET", BASE + "/" + unknown, null, token);
            JsonNode error = expect(response, 404);
            assertThat(error.path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
            assertThat(error.path("message").asText()).isEqualTo("Không tìm thấy yêu cầu tuyển dụng.");
            noStore(response);
            error(put(unknown, changed(positionId, itId), token), 404, "REQUISITION_NOT_FOUND");
        }
        error(request("GET", BASE + "/not-a-uuid", null, hrToken), 400, "VALIDATION_ERROR");
        error(request("PUT", BASE + "/not-a-uuid", json.writeValueAsString(changed(positionId, itId)), hrToken),
                400, "VALIDATION_ERROR");
        assertThat(count()).isZero();
    }

    @Test
    void updateReplacesTheWholeDraftKeepsTheCreatorAndCreationTimeAndRefreshesUpdatedAt() throws Exception {
        Map<String, Object> original = payload(positionId, itId);
        original.put("proposedSalaryMin", 15_000_000L);
        original.put("jobDescription", "Bản đầu.");
        JsonNode created = createDraft(original, headToken);
        UUID id = id(created);
        Instant createdAt = Instant.parse(created.path("createdAt").asText());

        // Nanoseconds in the clock: the response must show the microseconds PostgreSQL keeps.
        clock.set(START.plusSeconds(60).plusNanos(987_654_321));
        Instant firstUpdate = START.plusSeconds(60).plusNanos(987_654_000);
        Map<String, Object> body = changed(otherPositionId, itDevId);
        body.put("proposedSalaryMin", 2_000_000_000L);
        body.put("proposedSalaryMax", THREE_BILLION_VND);
        body.put("salaryJustification", "Thị trường khan hiếm.");
        body.put("neededBy", "2027-01-15");
        body.put("jobDescription", "  Kiểm thử tự động.\n\n- Playwright\n");
        body.put("candidateRequirements", "Tối thiểu 3 năm kinh nghiệm.");
        var response = put(id, body, headToken);
        JsonNode updated = expect(response, 200);
        noStore(response);
        assertExactFields(updated);
        assertThat(id(updated)).isEqualTo(id);
        assertThat(updated.path("positionId").asText()).isEqualTo(otherPositionId.toString());
        assertThat(updated.path("departmentId").asText()).isEqualTo(itDevId.toString());
        assertThat(updated.path("headcount").asInt()).isEqualTo(5);
        assertThat(updated.path("reason").asText()).isEqualTo("REPLACEMENT");
        assertThat(updated.path("proposedSalaryMin").asLong()).isEqualTo(2_000_000_000L);
        assertThat(updated.path("proposedSalaryMax").asLong()).isEqualTo(THREE_BILLION_VND);
        assertThat(updated.path("salaryJustification").asText()).isEqualTo("Thị trường khan hiếm.");
        assertThat(updated.path("neededBy").asText()).isEqualTo("2027-01-15");
        assertThat(updated.path("jobDescription").asText()).isEqualTo("  Kiểm thử tự động.\n\n- Playwright\n");
        assertThat(updated.path("candidateRequirements").asText()).isEqualTo("Tối thiểu 3 năm kinh nghiệm.");
        assertThat(updated.path("status").asText()).isEqualTo("DRAFT");
        assertThat(updated.path("createdBy").asText()).isEqualTo(headId.toString());
        assertThat(Instant.parse(updated.path("createdAt").asText())).isEqualTo(createdAt);
        assertThat(Instant.parse(updated.path("updatedAt").asText())).isEqualTo(firstUpdate);
        // A later read returns exactly what the update answered.
        assertThat(expect(request("GET", BASE + "/" + id, null, headToken), 200)).isEqualTo(updated);

        Map<String, Object> row = row(id);
        assertThat(row.get("position_id")).isEqualTo(otherPositionId);
        assertThat(row.get("department_id")).isEqualTo(itDevId);
        assertThat(row.get("headcount")).isEqualTo(5);
        assertThat(row.get("reason")).isEqualTo("REPLACEMENT");
        assertThat(row.get("proposed_salary_max")).isEqualTo(THREE_BILLION_VND);
        assertThat(row.get("created_by")).isEqualTo(headId);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(createdAt));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(firstUpdate));
        assertThat(jdbc.queryForObject("SELECT needed_by FROM recruitment_requisitions WHERE id = ?", LocalDate.class, id))
                .isEqualTo(LocalDate.of(2027, 1, 15));

        // PUT replaces the whole draft: fields not sent, null or blank are cleared. An ALL caller (HR) may edit
        // the head's draft, and the creator does not change.
        clock.set(START.plusSeconds(120));
        Map<String, Object> minimal = payload(positionId, itId);
        minimal.put("jobDescription", "   ");
        minimal.put("neededBy", null);
        JsonNode cleared = expect(put(id, minimal, hrToken), 200);
        for (String field : List.of("proposedSalaryMin", "proposedSalaryMax", "salaryJustification", "neededBy",
                "jobDescription", "candidateRequirements")) {
            assertThat(cleared.path(field).isNull()).as(field).isTrue();
        }
        assertThat(cleared.path("createdBy").asText()).isEqualTo(headId.toString());
        assertThat(Instant.parse(cleared.path("updatedAt").asText())).isEqualTo(START.plusSeconds(120));
        assertThat(jdbc.queryForObject("""
                SELECT proposed_salary_min IS NULL AND proposed_salary_max IS NULL AND salary_justification IS NULL
                   AND needed_by IS NULL AND job_description IS NULL AND candidate_requirements IS NULL
                   AND created_by = ? AND created_at = ?
                FROM recruitment_requisitions WHERE id = ?
                """, Boolean.class, headId, Timestamp.from(createdAt), id)).isTrue();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void updateRejectsInvalidContentWithTheSameErrorsAsCreateAndLeavesTheDraftUnchanged() throws Exception {
        UUID id = id(createDraft(payload(positionId, itId), headToken));
        Map<String, Object> before = row(id);

        Map<String, Object> missing = changed(positionId, itId);
        missing.remove("headcount");
        missing.put("reason", null);
        fieldErrors(put(id, missing, headToken), "headcount", "reason");
        Map<String, Object> nul = changed(positionId, itId);
        nul.put("jobDescription", "Nội dung" + (char) 0);
        assertThat(fieldErrors(put(id, nul, headToken), "jobDescription").path("jobDescription").asText())
                .isEqualTo("Mô tả công việc chứa ký tự không hợp lệ.");
        // A whole JSON number is required, and fields the server decides cannot be sent.
        Map<String, String> invalidJson = new LinkedHashMap<>();
        invalidJson.put("headcount", "1.5");
        invalidJson.put("proposedSalaryMin", "\"15000000\"");
        invalidJson.put("status", "\"DRAFT\"");
        invalidJson.put("createdBy", "\"" + adminId + "\"");
        invalidJson.put("id", "\"" + UUID.randomUUID() + "\"");
        for (var entry : invalidJson.entrySet()) {
            error(request("PUT", BASE + "/" + id, withRawField(entry.getKey(), entry.getValue()), headToken),
                    400, "INVALID_JSON");
        }

        Map<String, Object> inverted = changed(positionId, itId);
        inverted.put("proposedSalaryMin", 20_000_001L);
        inverted.put("proposedSalaryMax", 20_000_000L);
        var invertedResponse = put(id, inverted, headToken);
        JsonNode salaryError = expect(invertedResponse, 400);
        noStore(invertedResponse);
        assertThat(salaryError.path("code").asText()).isEqualTo("REQUISITION_SALARY_RANGE_INVALID");
        assertThat(salaryError.path("fieldErrors").path("proposedSalaryMax").asText())
                .isEqualTo("Lương đề xuất tối đa phải lớn hơn hoặc bằng lương đề xuất tối thiểu.");
        JsonNode positionError = expect(put(id, changed(UUID.randomUUID(), itId), headToken), 400);
        assertThat(positionError.path("code").asText()).isEqualTo("INVALID_REQUISITION_POSITION");
        assertThat(positionError.path("fieldErrors").path("positionId").asText()).isEqualTo("Chức danh không tồn tại.");
        JsonNode departmentError = expect(put(id, changed(positionId, UUID.randomUUID()), headToken), 400);
        assertThat(departmentError.path("code").asText()).isEqualTo("INVALID_REQUISITION_DEPARTMENT");
        assertThat(departmentError.path("fieldErrors").path("departmentId").asText()).isEqualTo("Phòng ban không tồn tại.");

        assertThat(row(id)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleWithRequisitionPermissionsReadsAndEditsDraftsOfItsOwnDepartmentAndOthersAreForbidden(Role role)
            throws Exception {
        UUID caller = account("role@example.test", Set.of(role));
        String token = token("role@example.test");
        // The caller manages OWN, so SCOPED roles reach this draft too. HR creates it, so it exists for every role.
        UUID own = department("OWN", null, caller);
        UUID draft = id(createDraft(payload(positionId, own), hrToken));
        Map<String, Object> before = row(draft);
        Set<String> grants = RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name());
        boolean reader = grants.contains("REQUISITIONS_READ_ALL") || grants.contains("REQUISITIONS_READ_SCOPED");
        boolean writer = grants.contains("REQUISITIONS_WRITE_ALL") || grants.contains("REQUISITIONS_WRITE_SCOPED");
        // Written out as well, so this test does not only repeat the seed: only INTERVIEWER has no requisition rights.
        assertThat(reader).isEqualTo(role != Role.INTERVIEWER);
        assertThat(writer).isEqualTo(role != Role.INTERVIEWER);

        if (reader) {
            assertThat(ids(expect(request("GET", BASE, null, token), 200))).contains(draft);
            assertThat(id(expect(request("GET", BASE + "/" + draft, null, token), 200))).isEqualTo(draft);
        } else {
            forbidden(request("GET", BASE, null, token));
            forbidden(request("GET", BASE + "/" + draft, null, token));
        }
        if (writer) {
            assertThat(expect(put(draft, changed(positionId, own), token), 200).path("headcount").asInt()).isEqualTo(5);
            assertThat(row(draft).get("headcount")).isEqualTo(5);
        } else {
            forbidden(put(draft, changed(positionId, own), token));
            assertThat(row(draft)).isEqualTo(before);
        }
    }

    @Test
    void anonymousCallersAndAccountsWithoutRolesCannotReadOrUpdate() throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        Map<String, Object> before = row(draft);
        for (var response : List.of(request("GET", BASE, null, null), request("GET", BASE + "/" + draft, null, null),
                put(draft, changed(positionId, itId), null))) {
            assertThat(expect(response, 401).path("code").asText()).isEqualTo("UNAUTHORIZED");
            assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
        }
        account("norole@example.test", Set.of());
        String token = token("norole@example.test");
        forbidden(request("GET", BASE, null, token));
        forbidden(request("GET", BASE + "/" + draft, null, token));
        forbidden(put(draft, changed(positionId, itId), token));
        assertThat(row(draft)).isEqualTo(before);
    }

    @Test
    void readAndWritePermissionsAreCheckedSeparatelyOnTheNextRequestWithTheSameToken() throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_READ_SCOPED'");
        try {
            forbidden(request("GET", BASE, null, headToken));
            forbidden(request("GET", BASE + "/" + draft, null, headToken));
        } finally {
            grant("HIRING_MANAGER", "REQUISITIONS_READ_SCOPED");
        }
        Map<String, Object> before = row(draft);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
        try {
            // Reading never allows writing.
            forbidden(put(draft, changed(positionId, itId), headToken));
            assertThat(row(draft)).isEqualTo(before);
            assertThat(id(expect(request("GET", BASE + "/" + draft, null, headToken), 200))).isEqualTo(draft);
        } finally {
            grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
        }
        expect(put(draft, changed(positionId, itId), headToken), 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "expired-jwt", "locked-actor", "revoked-session"})
    void updateRechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        Map<String, Object> before = row(draft);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockRow(connection, "user_accounts", headId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> put(draft, changed(positionId, itId), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "lost-permission" -> jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
                        case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                                + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), adminId, headId);
                        default -> execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
                                Timestamp.from(START), headId);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("lost-permission")) {
                        forbidden(result);
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
        }
        assertThat(row(draft)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manager-changed", "edited-meanwhile"})
    void updateWaitsForTheRequisitionLockAndChecksTheDepartmentScopeAfterIt(String change) throws Exception {
        UUID draft = id(createDraft(payload(positionId, itId), headToken));
        Map<String, Object> before = row(draft);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockRow(connection, "recruitment_requisitions", draft);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> put(draft, changed(positionId, itId), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (change.equals("manager-changed")) {
                        execute(connection, "UPDATE departments SET manager_user_id = ? WHERE id = ?", leadId, itId);
                    } else {
                        execute(connection, "UPDATE recruitment_requisitions SET headcount = 7 WHERE id = ?", draft);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("manager-changed")) {
                        // head lost IT while waiting, so the draft is now outside head's scope.
                        forbidden(result);
                        assertThat(row(draft)).isEqualTo(before);
                    } else {
                        // The edits run one after the other; the request that waited is saved last.
                        assertThat(expect(result, 200).path("headcount").asInt()).isEqualTo(5);
                        assertThat(row(draft).get("headcount")).isEqualTo(5);
                        assertThat(row(draft).get("reason")).isEqualTo("REPLACEMENT");
                    }
                } finally {
                    connection.rollback();
                }
            }
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

    // A valid body as JSON text with one field written raw, so the test controls the exact JSON value.
    private String withRawField(String field, String rawJson) throws Exception {
        Map<String, Object> body = changed(positionId, itId);
        body.remove(field);
        String base = json.writeValueAsString(body);
        return base.substring(0, base.length() - 1) + ",\"" + field + "\":" + rawJson + "}";
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
                VALUES (?, ?, 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, id, code, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private void grant(String role, String permission) {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?) ON CONFLICT DO NOTHING",
                role, permission);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", id);
    }

    private JsonNode createDraft(Map<String, Object> body, String token) throws Exception {
        return expect(request("POST", BASE, json.writeValueAsString(body), token), 201);
    }

    private HttpResponse<String> put(UUID id, Map<String, Object> body, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(body), token);
    }

    private static UUID id(JsonNode requisition) {
        return UUID.fromString(requisition.path("id").asText());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(id(item)));
        return result;
    }

    // Exactly these fields: no standard salary band of the position, nothing else from the server.
    private void assertExactFields(JsonNode requisition) {
        assertThat(requisition.size()).isEqualTo(RESPONSE_FIELDS.size());
        RESPONSE_FIELDS.forEach(field -> assertThat(requisition.has(field)).as(field).isTrue());
    }

    private void assertListItemFields(JsonNode requisition) {
        assertThat(requisition.size()).isEqualTo(RESPONSE_FIELDS.size() + 3);
        RESPONSE_FIELDS.forEach(field -> assertThat(requisition.has(field)).as(field).isTrue());
        for (String field : List.of("daysOpen", "daysUntilNeededBy", "overdue")) {
            assertThat(requisition.has(field)).as(field).isTrue();
        }
    }

    private void assertPageFields(JsonNode page) {
        assertThat(page.size()).isEqualTo(PAGE_FIELDS.size());
        PAGE_FIELDS.forEach(field -> assertThat(page.has(field)).as(field).isTrue());
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

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403);
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        noStore(response);
    }

    // A 400 VALIDATION_ERROR whose fieldErrors names exactly these request fields.
    private JsonNode fieldErrors(HttpResponse<String> response, String... fields) {
        JsonNode body = expect(response, 400);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(fields.length);
        for (String field : fields) {
            assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        }
        return body.path("fieldErrors");
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    // Locks one row from a separate connection and returns that connection's PostgreSQL process id.
    private int lockRow(Connection connection, String table, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM " + table + " WHERE id = ? FOR UPDATE")) {
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
